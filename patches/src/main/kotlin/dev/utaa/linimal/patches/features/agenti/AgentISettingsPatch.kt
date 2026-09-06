package dev.utaa.linimal.patches.features.agenti

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.BOXED_BOOLEAN
import dev.utaa.linimal.patches.util.boxedBooleanReturnGateShape
import dev.utaa.linimal.patches.util.debugMetadataSource
import dev.utaa.linimal.patches.util.resolveDebugMetadataType

private const val SETTINGS_AGENT_I_TITLE = 0x7f151f62  // line_settings_title_agenti
private const val SETTINGS_LINE_AI_SERVICES_TITLE = 0x7f151f65  // line_settings_title_lineaiservices
private const val SETTINGS_TARGET_LINE_AI_SERVICE = "TARGET_LINE_AI_SERVICE"
private const val MAIN_SETTINGS_CATEGORY_SOURCE = "LineUserMainSettingsCategory.kt"
private const val MAIN_SETTINGS_FRAGMENT =
    "Lcom/linecorp/line/settings/main/LineUserMainSettingsFragment;"
private const val SETTINGS_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentISettingsHooks;->adjustVisibility(Z)Z"
private const val BOOLEAN_UNBOX = "$BOXED_BOOLEAN->booleanValue()$BOOLEAN"
private const val BOOLEAN_BOX = "$BOXED_BOOLEAN->valueOf($BOOLEAN)$BOXED_BOOLEAN"

/**
 * Main Settings static catalog の 2 variant entry。
 *
 * 26.11.0 では catalog item の型 `Lpx4/v;` も条件でしたが、26.14.0 で `Lm55/v;` に変わる難読化名です。
 * 2 つの title resource と `<clinit>` だけで全 DEX 中 1 件に絞れるため落とし、item の型は
 * 一致した `<clinit>` の中から実行時に導出します。
 */
private val settingsCatalogFingerprint = Fingerprint(
    name = "<clinit>",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        literal(SETTINGS_AGENT_I_TITLE),
        literal(SETTINGS_LINE_AI_SERVICES_TITLE),
    ),
)

/**
 * Both catalog click actions share the stable TARGET_LINE_AI_SERVICE telemetry target.
 *
 * enum 定数名 `TARGET_LINE_AI_SERVICE` は難読化されないため、26.11.0 で条件にしていた宣言クラス
 * (`Llx4/m0;` → 26.14.0 は `Li55/s0;`)、遷移 method 名 (`H3` → `J3`) とその引数型、
 * `Lvb8/l;` interface 判定はいずれも落とします。
 */
private val settingsAgentActionFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        fieldAccess(
            name = SETTINGS_TARGET_LINE_AI_SERVICE,
            opcode = Opcode.SGET_OBJECT,
        ),
        methodCall(
            definingClass = MAIN_SETTINGS_FRAGMENT,
            parameters = listOf("L"),
            returnType = "V",
            opcode = Opcode.INVOKE_STATIC,
        ),
    ),
)

/**
 * The two async predicates execute normal product visibility logic first. Debug metadata confirms they are
 * source-generated predicate continuations, then the catalog association below selects only the two entries.
 *
 * 26.11.0 で条件にしていた `Lpg0/e;` / `Lug0/s;` / `Ljw4/m2;` は 26.14.0 でそれぞれ `Lui0/f;` /
 * `Lb3/hb;` / `Lg45/m2;` へ変わる難読化名です。難読化されない `isEnabled` と呼び出し形だけを残します。
 */
private fun settingsVisibilityPredicateFingerprint(debugMetadataType: String) = Fingerprint(
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            name = "isEnabled",
            returnType = "Z",
            opcode = Opcode.INVOKE_INTERFACE,
        ),
        methodCall(
            parameters = listOf("L"),
            returnType = "Z",
            opcode = Opcode.INVOKE_STATIC,
        ),
        methodCall(
            definingClass = "Ljava/lang/Boolean;",
            name = "valueOf",
            parameters = listOf("Z"),
            returnType = "Ljava/lang/Boolean;",
            opcode = Opcode.INVOKE_STATIC,
        ),
    ),
    custom = { _, classDef ->
        debugMetadataSource(classDef, debugMetadataType)?.sourceFile == MAIN_SETTINGS_CATEGORY_SOURCE
    },
)

/**
 * Main Settings static catalog から Agent i / LINE AI Services の両 visible predicate を導出し、
 * each predicate's boxed result at the single return site を runtime setting で調整します。
 * Navigator, destination, remote predicate sources, and subscription state still execute unchanged.
 */
val agentISettingsPatch = bytecodePatch(
    name = "設定画面の Agent i",
    description = "LINE の設定画面にある Agent i と LINE AI Services の入口を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(agentIWalletHeaderPatch)

    execute {
        val debugMetadataType = resolveDebugMetadataType()
        if (debugMetadataType == null) {
            recordSettingsUnapplied(0, "AgentISettingsDebugMetadataNotResolved")
            return@execute
        }

        val catalogMatches = settingsCatalogFingerprint.matchAllOrNull().orEmpty()
        if (catalogMatches.size != 1) {
            recordSettingsUnapplied(catalogMatches.size, "AgentISettingsCatalogNotUnique")
            return@execute
        }

        val actionMatches = settingsAgentActionFingerprint.matchAllOrNull().orEmpty()
        if (actionMatches.size != 2) {
            recordSettingsUnapplied(actionMatches.size, "AgentISettingsActionsNotResolved")
            return@execute
        }

        val predicateMatches = settingsVisibilityPredicateFingerprint(debugMetadataType)
            .matchAllOrNull()
            .orEmpty()
        if (predicateMatches.isEmpty()) {
            recordSettingsUnapplied(0, "AgentISettingsPredicatesNotResolved")
            return@execute
        }

        val variants = resolveSettingsVariants(
            catalogMatches.single(),
            actionMatches,
            predicateMatches,
        )
        if (variants == null || variants.size != 2) {
            patchStatusCollector.record(
                agentISettingsShapeMismatchRecord(
                    rawPredicateCount = predicateMatches.size,
                    reason = "AgentISettingsCatalogShapeMismatch",
                ),
            )
            return@execute
        }

        val predicateTargets = variants.map { it.predicateMatch }
        if (predicateTargets.distinctBy { it.originalClassDef.type }.size != 2) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_SETTINGS),
                expectedTargetCount = 2,
                actualTargetCount = predicateTargets.distinctBy { it.originalClassDef.type }.size,
                reason = "AgentISettingsPredicateVariantMismatch",
            )
            return@execute
        }

        // Build every injection plan before changing either variant; a malformed one leaves both untouched.
        val gates = predicateTargets.map(::visibilityGate)
        if (gates.any { it == null }) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_SETTINGS),
                expectedTargetCount = 2,
                actualTargetCount = predicateTargets.size,
                reason = "AgentISettingsPredicateShapeMismatch",
            )
            return@execute
        }

        // 分岐合流点である boxing 命令の手前ではなく、単一の return 直前で box 済みの値を通します。
        gates.filterNotNull().forEach { gate ->
            gate.match.method.addInstructions(
                gate.insertionIndex,
                """
                    invoke-virtual { v${gate.booleanRegister} }, $BOOLEAN_UNBOX
                    move-result v${gate.booleanRegister}
                    invoke-static { v${gate.booleanRegister} }, $SETTINGS_HOOK
                    move-result v${gate.booleanRegister}
                    invoke-static { v${gate.booleanRegister} }, $BOOLEAN_BOX
                    move-result-object v${gate.booleanRegister}
                """.trimIndent(),
            )
        }
        patchStatusCollector.record(
            patchId = PatchId.AGENT_I_SETTINGS,
            expectedTargetCount = 2,
            actualTargetCount = 2,
            reason = "AgentISettingsVariantPredicatesAdjusted",
        )
    }
}

private data class SettingsVariant(
    val titleResource: Int,
    val actionType: String,
    val predicateMatch: Match,
)

private data class VisibilityGate(
    val match: Match,
    val insertionIndex: Int,
    val booleanRegister: Int,
)

/**
 * catalog `<clinit>` から catalog item の型を導出します。
 *
 * 2 つの title literal それぞれの直後にある最初の range constructor 呼び出しが同じ型であることを
 * 要求します（26.11.0 は `Lpx4/v;`、26.14.0 は `Lm55/v;`）。
 */
private fun settingsItemType(instructions: List<Instruction>): String? {
    val titles = listOf(SETTINGS_AGENT_I_TITLE, SETTINGS_LINE_AI_SERVICES_TITLE)
    val types = titles.map { title ->
        val titleIndex = instructions.indices.singleOrNull { index ->
            (instructions[index] as? NarrowLiteralInstruction)?.narrowLiteral == title
        } ?: return null
        val constructorIndex = (titleIndex + 1 until instructions.size).firstOrNull { index ->
            val reference = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
            instructions[index].opcode == Opcode.INVOKE_DIRECT_RANGE &&
                reference?.name == "<init>" &&
                reference.returnType == "V"
        } ?: return null
        val reference = (instructions[constructorIndex] as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass ?: return null
    }
    return types.distinct().singleOrNull()
}

/** Associates each title-bearing catalog-item construction segment with one action and one generated predicate. */
private fun resolveSettingsVariants(
    catalogMatch: Match,
    actionMatches: List<Match>,
    predicateMatches: List<Match>,
): List<SettingsVariant>? {
    val instructions = catalogMatch.method.implementation?.instructions?.toList() ?: return null
    val itemType = settingsItemType(instructions) ?: return null
    val actionTypes = actionMatches.map { it.originalClassDef.type }.toSet()
    val predicatesByType = predicateMatches.groupBy { it.originalClassDef.type }
    val itemConstructorIndices = instructions.mapIndexedNotNull { index, instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        index.takeIf {
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                reference?.definingClass == itemType &&
                reference.name == "<init>" &&
                reference.returnType == "V"
        }
    }

    val variants = itemConstructorIndices.mapNotNull { constructorIndex ->
        val itemStart = (constructorIndex - 1 downTo 0).firstOrNull { index ->
            instructions[index].opcode == Opcode.NEW_INSTANCE &&
                ((instructions[index] as? ReferenceInstruction)?.reference as? TypeReference)?.type == itemType
        } ?: return@mapNotNull null
        val segment = instructions.subList(itemStart, constructorIndex + 1)
        val titles = segment.mapNotNull { instruction ->
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral
                ?.takeIf { it == SETTINGS_AGENT_I_TITLE || it == SETTINGS_LINE_AI_SERVICES_TITLE }
        }.distinct()
        val createdTypes = segment.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.NEW_INSTANCE) {
                null
            } else {
                ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type
            }
        }.toSet()
        val actions = createdTypes.intersect(actionTypes)
        val predicates = createdTypes.intersect(predicatesByType.keys)

        if (titles.size != 1 || actions.size != 1 || predicates.size != 1) {
            return@mapNotNull null
        }
        val predicateMatch = predicatesByType.getValue(predicates.single()).singleOrNull() ?: return@mapNotNull null
        SettingsVariant(titles.single(), actions.single(), predicateMatch)
    }.filter { variant ->
        variant.titleResource == SETTINGS_AGENT_I_TITLE || variant.titleResource == SETTINGS_LINE_AI_SERVICES_TITLE
    }

    return variants.takeIf { resolved ->
        resolved.size == 2 &&
            resolved.map { it.titleResource }.toSet() == setOf(
                SETTINGS_AGENT_I_TITLE,
                SETTINGS_LINE_AI_SERVICES_TITLE,
            ) &&
            resolved.map { it.actionType }.toSet() == actionTypes &&
            resolved.map { it.predicateMatch.originalClassDef.type }.toSet().size == 2
    }
}

/** The hook is inserted after original remote/product visibility evaluation, at the predicate's single return site. */
private fun visibilityGate(match: Match): VisibilityGate? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val shape = boxedBooleanReturnGateShape(
        instructions = implementation.instructions.toList(),
        parameterTypes = method.parameterTypes.map { it.toString() },
        registerCount = implementation.registerCount,
        hasTryBlocks = implementation.tryBlocks.isNotEmpty(),
    ) ?: return null
    return VisibilityGate(match, shape.insertionIndex, shape.booleanRegister)
}

private fun recordSettingsUnapplied(matchCount: Int, reason: String) {
    patchStatusCollector.record(agentISettingsUnappliedRecord(matchCount, reason))
}

/**
 * Catalog association failed after predicate anchors were found. Keep the raw non-zero candidate count so the
 * runtime parser can distinguish an unsafe shape from a target that did not exist at all.
 */
internal fun agentISettingsShapeMismatchRecord(
    rawPredicateCount: Int,
    reason: String,
): PatchStatusRecord {
    require(rawPredicateCount > 0) { "shape mismatch requires at least one raw predicate" }
    return PatchStatusRecord(
        patchId = PatchId.AGENT_I_SETTINGS,
        status = PatchStatus.ERROR,
        expectedTargetCount = 2,
        actualTargetCount = rawPredicateCount,
        reason = reason,
    )
}

/** A dual-variant feature cannot safely accept one resolved predicate, so partial is always an ERROR. */
internal fun agentISettingsUnappliedRecord(matchCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.AGENT_I_SETTINGS,
    status = if (matchCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = 2,
    actualTargetCount = matchCount,
    reason = reason,
)

package dev.utaa.linimal.patches.features.agenti

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val SETTINGS_AGENT_I_TITLE = 0x7f151e38
private const val SETTINGS_LINE_AI_SERVICES_TITLE = 0x7f151e3b
private const val SETTINGS_ITEM = "Lpx4/v;"
private const val SETTINGS_TARGET = "Llx4/m0;"
private const val SETTINGS_TARGET_LINE_AI_SERVICE = "TARGET_LINE_AI_SERVICE"
private const val DEBUG_METADATA = "Llb8/e;"
private const val MAIN_SETTINGS_CATEGORY_SOURCE = "LineUserMainSettingsCategory.kt"
private const val SETTINGS_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentISettingsHooks;->adjustVisibility(Z)Z"
private const val BOOLEAN_UNBOX = "Ljava/lang/Boolean;->booleanValue()Z"
private const val BOOLEAN_BOX = "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"

/**
 * Main Settings static catalog の 2 variant entry。title resource と catalog item constructor を合わせ、
 * owner の難読化名は識別条件にしません。
 */
private val settingsCatalogFingerprint = Fingerprint(
    name = "<clinit>",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        literal(SETTINGS_AGENT_I_TITLE),
        literal(SETTINGS_LINE_AI_SERVICES_TITLE),
        methodCall(
            definingClass = SETTINGS_ITEM,
            name = "<init>",
            returnType = "V",
            opcode = Opcode.INVOKE_DIRECT_RANGE,
        ),
    ),
)

/** Both catalog click actions share the stable TARGET_LINE_AI_SERVICE telemetry target. */
private val settingsAgentActionFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        fieldAccess(
            definingClass = SETTINGS_TARGET,
            name = SETTINGS_TARGET_LINE_AI_SERVICE,
            type = SETTINGS_TARGET,
            opcode = Opcode.SGET_OBJECT,
        ),
        methodCall(
            definingClass = "Lcom/linecorp/line/settings/main/LineUserMainSettingsFragment;",
            name = "H3",
            parameters = listOf("Lb18/c;"),
            returnType = "V",
            opcode = Opcode.INVOKE_STATIC,
        ),
    ),
    custom = { _, classDef -> classDef.interfaces.contains("Lvb8/l;") },
)

/**
 * The two async predicates execute normal product visibility logic first. Debug metadata confirms they are
 * source-generated predicate continuations, then the catalog association below selects only the two entries.
 */
private val settingsVisibilityPredicateFingerprint = Fingerprint(
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            definingClass = "Lpg0/e;",
            name = "isEnabled",
            returnType = "Z",
            opcode = Opcode.INVOKE_INTERFACE,
        ),
        methodCall(
            definingClass = "Lug0/s;",
            name = "a",
            parameters = listOf("Ljw4/m2;"),
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
    custom = { _, classDef -> isMainSettingsCategoryContinuation(classDef) },
)

/**
 * Main Settings static catalog から Agent i / LINE AI Services の両 visible predicate を導出し、
 * each predicate's boxed result at the single return site を runtime setting で調整します。
 * Navigator, destination, remote predicate sources, and subscription state still execute unchanged.
 */
val agentISettingsPatch = bytecodePatch {
    dependsOn(agentIWalletHeaderPatch)

    execute {
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

        val predicateMatches = settingsVisibilityPredicateFingerprint.matchAllOrNull().orEmpty()
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

/** 注入位置と、そこで `Ljava/lang/Boolean;` を保持している register。 */
internal data class SettingsVisibilityGateShape(
    val insertionIndex: Int,
    val booleanRegister: Int,
)

/** Associates each title-bearing px4/v construction segment with one action and one generated predicate. */
private fun resolveSettingsVariants(
    catalogMatch: Match,
    actionMatches: List<Match>,
    predicateMatches: List<Match>,
): List<SettingsVariant>? {
    val instructions = catalogMatch.method.implementation?.instructions?.toList() ?: return null
    val actionTypes = actionMatches.map { it.originalClassDef.type }.toSet()
    val predicatesByType = predicateMatches.groupBy { it.originalClassDef.type }
    val itemConstructorIndices = instructions.mapIndexedNotNull { index, instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        index.takeIf {
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                reference?.definingClass == SETTINGS_ITEM &&
                reference.name == "<init>" &&
                reference.returnType == "V"
        }
    }

    val variants = itemConstructorIndices.mapNotNull { constructorIndex ->
        val itemStart = (constructorIndex - 1 downTo 0).firstOrNull { index ->
            instructions[index].opcode == Opcode.NEW_INSTANCE &&
                ((instructions[index] as? ReferenceInstruction)?.reference as? TypeReference)?.type == SETTINGS_ITEM
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
    val shape = settingsVisibilityGateShape(
        instructions = implementation.instructions.toList(),
        parameterTypes = method.parameterTypes.map { it.toString() },
        registerCount = implementation.registerCount,
        hasTryBlocks = implementation.tryBlocks.isNotEmpty(),
    ) ?: return null
    return VisibilityGate(match, shape.insertionIndex, shape.booleanRegister)
}

/**
 * predicate は product 側の visibility 判定を実行したあと、結果を `Boolean` へ box して返します。
 * この box 命令は `if`/`goto` の合流点であり、その手前へ注入すると true 側の `goto` が hook を飛び越え、
 * 抑制が実行時にまったく効きません。そのため box 済みの値を単一の `return-object` 直前で unbox し、
 * hook を通してから box し直します。挿入位置がどの分岐先でもないことも明示的に検証します。
 */
internal fun settingsVisibilityGateShape(
    instructions: List<Instruction>,
    parameterTypes: List<String>,
    registerCount: Int,
    hasTryBlocks: Boolean,
): SettingsVisibilityGateShape? {
    if (
        parameterTypes != listOf("Ljava/lang/Object;") ||
        registerCount < 2 ||
        hasTryBlocks ||
        instructions.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    ) {
        return null
    }

    val returnIndices = instructions.mapIndexedNotNull { index, instruction ->
        index.takeIf { instruction.opcode == Opcode.RETURN_OBJECT }
    }
    val returnIndex = returnIndices.singleOrNull() ?: return null
    val returnedValue = instructions.getOrNull(returnIndex) as? OneRegisterInstruction ?: return null
    val resultMove = instructions.getOrNull(returnIndex - 1) as? OneRegisterInstruction ?: return null
    val boxing = instructions.getOrNull(returnIndex - 2) as? FiveRegisterInstruction ?: return null
    val boxingReference = (instructions.getOrNull(returnIndex - 2) as? ReferenceInstruction)
        ?.reference as? MethodReference ?: return null

    if (
        resultMove.opcode != Opcode.MOVE_RESULT_OBJECT ||
        resultMove.registerA != returnedValue.registerA ||
        returnedValue.registerA !in 0..15 ||
        boxing.opcode != Opcode.INVOKE_STATIC ||
        boxing.registerCount != 1 ||
        boxing.registerC !in 0..15 ||
        boxingReference.definingClass != "Ljava/lang/Boolean;" ||
        boxingReference.name != "valueOf" ||
        boxingReference.parameterTypes != listOf("Z") ||
        boxingReference.returnType != "Ljava/lang/Boolean;"
    ) {
        return null
    }

    // 挿入位置が分岐先だと、その分岐だけが hook を飛び越えます。
    val insertionAddress = instructionAddress(instructions, returnIndex)
    if (instructions.indices.any { branchTargetAddress(instructions, it) == insertionAddress }) {
        return null
    }

    return SettingsVisibilityGateShape(returnIndex, returnedValue.registerA)
}

private fun instructionAddress(instructions: List<Instruction>, index: Int): Int =
    instructions.take(index).sumOf { it.codeUnits }

private fun branchTargetAddress(instructions: List<Instruction>, index: Int): Int? {
    val branch = instructions.getOrNull(index) as? OffsetInstruction ?: return null
    return instructionAddress(instructions, index) + branch.codeOffset
}

private fun isMainSettingsCategoryContinuation(classDef: ClassDef): Boolean = classDef.annotations.any { annotation ->
    annotation.type == DEBUG_METADATA &&
        (annotation.elements.firstOrNull { it.name == "f" }?.value as? StringEncodedValue)?.value ==
            MAIN_SETTINGS_CATEGORY_SOURCE
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

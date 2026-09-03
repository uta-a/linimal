package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex

private const val DEBUG_METADATA = "Llb8/e;"
private const val COMPOSER = "Lh3/t;"
private const val COMPOSER_IMPL = "Lh3/f1;"
private const val END_RESTART_GROUP_RESULT = "Lh3/p3;"
private const val FEED_MODULE_STATE = "Ll72/f;"
private const val HOME_TRENDING_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeTrendingHooks;->shouldSuppress()Z"

/** Home26 の話題枠を描く module controller。source metadata で特定します。 */
private const val MATOME_CONTROLLER_SOURCE = "GcsHomeFeedMatomeSingleModuleController"
private const val MATOME_CONTROLLER_FILE = "GcsHomeFeedMatomeSingleModuleController.kt"

/** 話題枠の module renderer は 1 件だけです。解決できなければ一切注入しません。 */
internal const val HOME_TRENDING_TARGET_COUNT = 1

/**
 * 話題 module の stateful continuation。source metadata は R8 後も残るため、
 * 難読化された class / method 名を anchor にせず、Home の話題枠だけに絞り込みます。
 */
private val matomeSourceMetadataFingerprint = Fingerprint(
    custom = { _, classDef ->
        classDef.annotations.any { annotation ->
            if (annotation.type != DEBUG_METADATA) {
                false
            } else {
                val declaringController = annotation.elements.firstOrNull { it.name == "c" }
                    ?.value
                    .let { it as? StringEncodedValue }
                    ?.value
                    ?.contains(MATOME_CONTROLLER_SOURCE) == true
                val sourceFile = annotation.elements.firstOrNull { it.name == "f" }
                    ?.value
                    .let { it as? StringEncodedValue }
                    ?.value == MATOME_CONTROLLER_FILE
                declaringController && sourceFile
            }
        }
    },
)

/**
 * 話題 module の restartable composable。難読化された class / method 名ではなく、
 * module renderer の引数の並びと composer lifecycle の呼出しで絞り込みます。
 */
private fun matomeRendererFingerprint(ownerType: String) = Fingerprint(
    definingClass = ownerType,
    returnType = VOID,
    custom = { method, _ -> isMatomeModuleRendererSignature(method) },
    filters = listOf(
        methodCall(
            definingClass = COMPOSER_IMPL,
            name = "A",
            parameters = listOf("I", BOOLEAN),
            returnType = BOOLEAN,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        methodCall(
            definingClass = COMPOSER_IMPL,
            name = "l",
            parameters = emptyList(),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        methodCall(
            definingClass = COMPOSER_IMPL,
            name = "Y",
            parameters = emptyList(),
            returnType = END_RESTART_GROUP_RESULT,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
)

/**
 * Home の話題枠を、LINE 自身の skip 経路へ倒して描かせません。
 *
 * <p>composable が本体を実行するかどうかを決める `Composer.shouldExecute` の結果だけを書き換えます。
 * 抑制時に通るのは、LINE が再 composition で本体を省くときと同じ `skipToGroupEnd` と
 * `endRestartGroup` の経路です。新しい control flow を作らないため、composer の group 整合は
 * 変わらず、Home が composed のまま設定を切り替えても経路は分岐しません。
 * 設定 OFF・未初期化・例外時は元の結果をそのまま使います。</p>
 */
val homeTrendingPatch = bytecodePatch(
    name = "ホームの話題枠",
    description = "ホームの話題・トレンド枠を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(homeContentsRecommendationPatch)

    execute {
        val owners = matomeSourceMetadataFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
            .let(::matomeRendererOwnerCandidates)
        if (owners.isEmpty()) {
            patchStatusCollector.record(
                homeTrendingUnappliedRecord(0, "HomeMatomeModuleContinuationNotUnique"),
            )
            return@execute
        }

        // continuation の enclosing type は nest の深さが version で変わり得るため、
        // 直接の enclosing から最外殻までを候補にし、合計 1 件に絞れた場合だけ注入します。
        val renderers = owners.flatMap { owner ->
            matomeRendererFingerprint(owner).matchAllOrNull().orEmpty()
        }
        if (renderers.size != HOME_TRENDING_TARGET_COUNT) {
            patchStatusCollector.record(
                homeTrendingUnappliedRecord(renderers.size, "HomeMatomeModuleRendererNotUnique"),
            )
            return@execute
        }

        val method = renderers.single().method
        val gate = homeTrendingModuleGate(method)
        if (gate == null) {
            // cardinality は揃っていても注入位置の shape が崩れている場合は、何も変更しません。
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.HOME_MATOME_SINGLE_MODULE,
                    expectedTargetCount = HOME_TRENDING_TARGET_COUNT,
                    actualTargetCount = renderers.size,
                    reason = "HomeMatomeModuleShapeMismatch",
                ),
            )
            return@execute
        }

        // 元の結果が false のときは何もしません。true のときだけ hook を読み、
        // 抑制時は 0、非抑制時は shouldExecute が返すのと同じ 1 に戻します。
        method.addInstructionsWithLabels(
            gate.branchIndex,
            """
                if-eqz v${gate.shouldExecuteRegister}, :linimalKeep
                invoke-static { }, $HOME_TRENDING_HOOK
                move-result v${gate.shouldExecuteRegister}
                if-eqz v${gate.shouldExecuteRegister}, :linimalRestore
                const/4 v${gate.shouldExecuteRegister}, 0x0
                goto :linimalKeep
                :linimalRestore
                const/4 v${gate.shouldExecuteRegister}, 0x1
                :linimalKeep
                nop
            """.trimIndent(),
        )

        patchStatusCollector.record(
            patchId = PatchId.HOME_MATOME_SINGLE_MODULE,
            expectedTargetCount = HOME_TRENDING_TARGET_COUNT,
            actualTargetCount = HOME_TRENDING_TARGET_COUNT,
            reason = "HomeMatomeModuleSuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、複数見つかった場合は
 * 意図した 1 件に絞り込めていないため ERROR として raw match count を残します。
 */
internal fun homeTrendingUnappliedRecord(resolvedCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.HOME_MATOME_SINGLE_MODULE,
    status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = HOME_TRENDING_TARGET_COUNT,
    actualTargetCount = resolvedCount,
    reason = reason,
)

/**
 * continuation を囲む type を、直接の enclosing から最外殻まで列挙します。continuation が
 * 0 件、複数件、または nest されていない場合は owner を確定できないため、候補を返しません。
 */
internal fun matomeRendererOwnerCandidates(continuationTypes: Set<String>): Set<String> {
    val continuation = continuationTypes.singleOrNull() ?: return emptySet()
    if (!continuation.startsWith("L") || !continuation.endsWith(";")) {
        return emptySet()
    }

    val body = continuation.substring(1, continuation.length - 1)
    return generateSequence(body) { type ->
        type.substringBeforeLast('$', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    }
        .drop(1)
        .map { "L$it;" }
        .toSet()
}

/**
 * module renderer の引数の並び。view data の型だけが module ごとに変わるため、その位置は
 * 型を問わず、feed module state・composer・changed flag の並びだけを検証します。
 */
internal fun isMatomeModuleRendererSignature(method: Method): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size == 5 &&
        parameters[0] == "Ljava/lang/String;" &&
        parameters[1].startsWith("L") &&
        parameters[2] == FEED_MODULE_STATE &&
        parameters[3] == COMPOSER &&
        parameters[4] == "I"
}

internal data class HomeTrendingModuleGate(
    val branchIndex: Int,
    val shouldExecuteRegister: Int,
)

private fun homeTrendingModuleGate(method: Method): HomeTrendingModuleGate? {
    val implementation = method.implementation ?: return null
    return homeTrendingModuleGateShape(
        instructions = implementation.instructions.toList(),
        hasTryBlocks = implementation.tryBlocks.isNotEmpty(),
    )
}

/**
 * `shouldExecute` → `move-result` → `if-eqz` の並びを検証します。
 *
 * <p>`shouldExecute` の戻り値は `Z` なので元の値は 0 か 1 に限られ、注入後に 1 へ戻しても
 * 情報は失われません。分岐先が `if-eqz` と一致する場合は注入が飛び越される可能性があるため、
 * その shape は意図的に拒否します。try block を持つ実装も対象外です。</p>
 */
internal fun homeTrendingModuleGateShape(
    instructions: List<Instruction>,
    hasTryBlocks: Boolean,
): HomeTrendingModuleGate? {
    if (hasTryBlocks) {
        return null
    }
    if (composerCallIndices(instructions, "l", VOID).size != 1) {
        return null
    }
    if (composerCallIndices(instructions, "Y", END_RESTART_GROUP_RESULT).size != 1) {
        return null
    }

    val shouldExecuteIndex = composerCallIndices(instructions, "A", BOOLEAN).singleOrNull() ?: return null
    val resultMove = instructions.getOrNull(shouldExecuteIndex + 1) as? OneRegisterInstruction ?: return null
    val branchIndex = shouldExecuteIndex + 2
    val branch = instructions.getOrNull(branchIndex) as? OneRegisterInstruction ?: return null

    if (
        instructions[shouldExecuteIndex + 1].opcode != Opcode.MOVE_RESULT ||
        instructions[branchIndex].opcode != Opcode.IF_EQZ ||
        branch.registerA != resultMove.registerA ||
        // 抑制と復元に使う const/4 は 4bit register しか取れません。
        resultMove.registerA !in 0..15
    ) {
        return null
    }

    if (isDivertedInjectionIndex(instructions, branchIndex)) {
        return null
    }
    return HomeTrendingModuleGate(branchIndex, resultMove.registerA)
}

private fun composerCallIndices(
    instructions: List<Instruction>,
    name: String,
    returnType: String,
): List<Int> = instructions.indices.filter { index ->
    val instruction = instructions[index]
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) {
        false
    } else {
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass == COMPOSER_IMPL &&
            reference.name == name &&
            reference.returnType == returnType
    }
}

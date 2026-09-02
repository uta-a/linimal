package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus

private const val DEBUG_METADATA = "Llb8/e;"
private const val VOID = "V"
private const val BOOLEAN = "Z"
private const val INT = "I"
private const val COMPOSER = "Lh3/t;"
private const val COMPOSER_IMPL = "Lh3/f1;"
private const val END_RESTART_GROUP_RESULT = "Lh3/p3;"
private const val HOME_FEED_LOADING_INDICATOR_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeFeedLoadingIndicatorHooks;->shouldSuppress()Z"

/**
 * ホームフィードの既定ページのモジュール群を表す coroutine continuation を、DebugMetadata の
 * 元 class 名から特定するための anchor です。R8 後もこの文字列は残るため、難読化された
 * `Lve2/...` パッケージ名を anchor にせずに済みます。
 */
/**
 * Material3 の CircularProgressIndicator の引数の並び。難読化名を書かずに関数を特定できます。
 * 実 APK ではこの並びを持つ `V` 戻り値のメソッドは1件だけです。
 */
private val PROGRESS_INDICATOR_PARAMETERS = listOf(
    "Lvb8/a;", "Ly3/j;", "J", "J", INT, "F", "Lvb8/l;", COMPOSER, INT,
)

private const val MODIFIER = "Ly3/j;"
private const val LOADING_HOST_PARAMETER_COUNT = 8

/** 読み込み表示の renderer は 1 件だけです。解決できなければ一切注入しません。 */
internal const val HOME_FEED_LOADING_INDICATOR_TARGET_COUNT = 1

/**
 * ホームフィードの既定ページのモジュール群を持つ package を、coroutine の DebugMetadata から
 * 導きます。source metadata は R8 後も残るため、難読化された class 名を anchor にせずに済みます。
 */
/** CircularProgressIndicator 本体。引数の並びだけで一意に決まります。 */
private val progressIndicatorFingerprint = Fingerprint(
    returnType = VOID,
    parameters = PROGRESS_INDICATOR_PARAMETERS,
)

/**
 * フィードが空のときに読み込み表示を描く composable。CircularProgressIndicator を呼ぶメソッドのうち、
 * 引数の並びが `(Z, Z, ..., Modifier, Composer, I)` のものは実 APK で1件だけです。
 */
internal fun isLoadingHostSignature(method: Method): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size == LOADING_HOST_PARAMETER_COUNT &&
        parameters[0] == BOOLEAN &&
        parameters[1] == BOOLEAN &&
        parameters[5] == MODIFIER &&
        parameters[6] == COMPOSER &&
        parameters[7] == INT
}

val homeFeedLoadingIndicatorPatch = bytecodePatch {
    // 機能パッチは単一の直列チェーンを成し、この patch の後段に noOpProbePatch が続きます。
    dependsOn(homeFeaturedCollectionsPatch)

    execute {
        val indicators = progressIndicatorFingerprint.matchAllOrNull().orEmpty()
        if (indicators.size != 1) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(
                    indicators.size,
                    "HomeFeedLoadingIndicatorProgressIndicatorNotUnique",
                ),
            )
            return@execute
        }
        val indicator = indicators.single().originalMethod

        // 読み込み表示を描く composable を、CircularProgressIndicator の呼出しと引数の並びで絞り込みます。
        val rendererFingerprint = Fingerprint(
            returnType = VOID,
            custom = { method, _ -> isLoadingHostSignature(method) },
            filters = listOf(
                methodCall(
                    definingClass = indicator.definingClass,
                    name = indicator.name,
                    parameters = PROGRESS_INDICATOR_PARAMETERS,
                    returnType = VOID,
                ),
            ),
        )
        val renderers = rendererFingerprint.matchAllOrNull().orEmpty()
        if (renderers.size != HOME_FEED_LOADING_INDICATOR_TARGET_COUNT) {
            patchStatusCollector.record(
                homeFeedLoadingIndicatorUnappliedRecord(
                    renderers.size,
                    "HomeFeedLoadingIndicatorRendererNotUnique",
                ),
            )
            return@execute
        }

        val method = renderers.single().method
        val gate = homeFeedLoadingIndicatorGate(method)
        if (gate == null) {
            // cardinality は揃っていても注入位置の shape が崩れている場合は、何も変更しません。
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.HOME_FEED_LOADING_INDICATOR,
                    expectedTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
                    actualTargetCount = renderers.size,
                    reason = "HomeFeedLoadingIndicatorShapeMismatch",
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
                invoke-static { }, $HOME_FEED_LOADING_INDICATOR_HOOK
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
            patchId = PatchId.HOME_FEED_LOADING_INDICATOR,
            expectedTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
            actualTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
            reason = "HomeFeedLoadingIndicatorSuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、複数見つかった場合は
 * 意図した 1 件に絞り込めていないため ERROR として raw match count を残します。
 */
internal fun homeFeedLoadingIndicatorUnappliedRecord(resolvedCount: Int, reason: String): PatchStatusRecord =
    PatchStatusRecord(
        patchId = PatchId.HOME_FEED_LOADING_INDICATOR,
        status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
        expectedTargetCount = HOME_FEED_LOADING_INDICATOR_TARGET_COUNT,
        actualTargetCount = resolvedCount,
        reason = reason,
    )

/** DebugMetadata が指す class の package。source file が 1 つに収まらなければ何も注入しません。 */
internal fun homeFeedLoadingIndicatorPackagePrefixes(sourceTypes: Set<String>): Set<String> = sourceTypes
    .mapNotNull { type ->
        val separator = type.lastIndexOf('/')
        if (separator > 0) type.substring(0, separator + 1) else null
    }
    .toSet()

/**
 * renderer の引数の並び。読み込み表示の composable は view data を持たず、sync key の `I` と
 * `Composer` だけを取ります。
 */
internal fun isLoadingIndicatorRendererSignature(method: Method): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters == listOf(INT, COMPOSER)
}

internal data class HomeFeedLoadingIndicatorGate(
    val branchIndex: Int,
    val shouldExecuteRegister: Int,
)

private fun homeFeedLoadingIndicatorGate(method: Method): HomeFeedLoadingIndicatorGate? {
    val implementation = method.implementation ?: return null
    return homeFeedLoadingIndicatorGateShape(
        instructions = implementation.instructions.toList(),
        hasTryBlocks = implementation.tryBlocks.isNotEmpty(),
    )
}

/**
 * `shouldExecute` → `move-result` → `if-eqz` の並びを検証します。
 *
 * <p>`shouldExecute` の戻り値は `Z` なので元の値は 0 か 1 に限られ、注入後に 1 へ戻しても
 * 情報は失われません。分岐先が `if-eqz` と一致する場合は注入が飛び越される可能性があるため、
 * その shape は意図的に拒否します。</p>
 */
internal fun homeFeedLoadingIndicatorGateShape(
    instructions: List<Instruction>,
    hasTryBlocks: Boolean,
): HomeFeedLoadingIndicatorGate? {
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

    val branchAddress = instructionAddress(instructions, branchIndex)
    if (instructions.indices.any { index -> branchTargetAddress(instructions, index) == branchAddress }) {
        return null
    }
    return HomeFeedLoadingIndicatorGate(branchIndex, resultMove.registerA)
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

private fun instructionAddress(instructions: List<Instruction>, index: Int): Int =
    instructions.take(index).sumOf { it.codeUnits }

private fun branchTargetAddress(instructions: List<Instruction>, index: Int): Int? {
    val branch = instructions.getOrNull(index) as? OffsetInstruction ?: return null
    return instructionAddress(instructions, index) + branch.codeOffset
}

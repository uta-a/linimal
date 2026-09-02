package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.utaa.linimal.patches.features.agenti.agentIChatListSearchPatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex

private const val DEBUG_METADATA = "Llb8/e;"
private const val VOID = "V"
private const val BOOLEAN = "Z"
private const val COMPOSER = "Lh3/t;"
private const val COMPOSER_IMPL = "Lh3/f1;"
private const val END_RESTART_GROUP_RESULT = "Lh3/p3;"
private const val FEED_MODULE_STATE = "Ll72/f;"
private const val HOME_FEED_POST_CARD_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeFeedPostCardHooks;->shouldSuppress()Z"

/**
 * Home Feed の下部に投稿カードを描く module controller。error module と、別 feature が扱う
 * Matome module は含めません。
 */
private val HOME_FEED_POST_MODULE_CONTROLLERS = listOf(
    "GcsHomeFeedPostModuleController",
    "GcsHomeFeedUnitSingleModuleController",
    "GcsHomeFeedUnitBigVisualModuleController",
)

/** card を描く module controller の数。1件でも解決できなければ一切注入しません。 */
internal val HOME_FEED_POST_CARDS_TARGET_COUNT = HOME_FEED_POST_MODULE_CONTROLLERS.size

/**
 * 各 module controller の stateful continuation。source metadata は R8 後も残るため、
 * 難読化された class / method 名を anchor にせず、Home 下部の投稿カードにだけ絞り込みます。
 */
private fun moduleSourceMetadataFingerprint(controller: String) = Fingerprint(
    custom = { _, classDef ->
        classDef.annotations.any { annotation ->
            if (annotation.type != DEBUG_METADATA) {
                false
            } else {
                val declaringController = annotation.elements.firstOrNull { it.name == "c" }
                    ?.value
                    .let { it as? StringEncodedValue }
                    ?.value
                    ?.contains(controller) == true
                val sourceFile = annotation.elements.firstOrNull { it.name == "f" }
                    ?.value
                    .let { it as? StringEncodedValue }
                    ?.value == "$controller.kt"
                declaringController && sourceFile
            }
        }
    },
)

/**
 * module の restartable composable。引数は module ごとに view data の型だけが変わるため、
 * その位置は型を問わず、残りの並びと composer lifecycle の呼出しで一意に絞り込みます。
 */
private fun moduleRendererFingerprint(ownerType: String) = Fingerprint(
    definingClass = ownerType,
    returnType = VOID,
    custom = { method, _ -> isModuleRendererSignature(method) },
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
 * Home 下部の投稿カードを、LINE 自身の skip 経路へ倒して描かせません。
 *
 * <p>composable が本体を実行するかどうかを決める `Composer.shouldExecute` の結果だけを書き換えます。
 * 抑制時に通るのは、LINE が再 composition で本体を省くときと同じ `skipToGroupEnd` と
 * `endRestartGroup` の経路です。設定 OFF・未初期化・例外時は元の結果をそのまま使います。</p>
 */
val homeFeedPostCardsPatch = bytecodePatch {
    // 機能パッチは単一の直列チェーンを成し、この patch の後段に premiumSettingsRowPatch が続きます。
    dependsOn(agentIChatListSearchPatch)

    execute {
        val owners = HOME_FEED_POST_MODULE_CONTROLLERS.map { controller ->
            moduleSourceMetadataFingerprint(controller).matchAllOrNull().orEmpty()
                .map { it.originalClassDef.type }
                .toSet()
                .let(::homeFeedPostRendererOwner)
        }
        if (owners.any { it == null }) {
            patchStatusCollector.record(
                homeFeedPostCardsUnappliedRecord(
                    owners.count { it != null },
                    "HomeFeedPostModuleContinuationNotUnique",
                ),
            )
            return@execute
        }

        val renderers = owners.filterNotNull().map { owner ->
            moduleRendererFingerprint(owner).matchAllOrNull().orEmpty()
        }
        if (renderers.any { it.size != 1 }) {
            patchStatusCollector.record(
                homeFeedPostCardsUnappliedRecord(
                    renderers.count { it.size == 1 },
                    "HomeFeedPostRendererNotUnique",
                ),
            )
            return@execute
        }

        val methods = renderers.map { it.single().method }
        val gates = methods.map { homeFeedPostModuleGate(it) }
        if (gates.any { it == null }) {
            // cardinality は揃っていても注入位置の shape が崩れている場合は、1件も変更しません。
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.HOME_FEED_POST_CARDS,
                    expectedTargetCount = HOME_FEED_POST_CARDS_TARGET_COUNT,
                    actualTargetCount = HOME_FEED_POST_CARDS_TARGET_COUNT,
                    reason = "HomeFeedPostCardShapeMismatch",
                ),
            )
            return@execute
        }

        methods.zip(gates.filterNotNull()).forEach { (method, gate) ->
            // 元の結果が false のときは何もしません。true のときだけ hook を読み、
            // 抑制時は 0、非抑制時は shouldExecute が返すのと同じ 1 に戻します。
            method.addInstructionsWithLabels(
                gate.branchIndex,
                """
                    if-eqz v${gate.shouldExecuteRegister}, :linimalKeep
                    invoke-static { }, $HOME_FEED_POST_CARD_HOOK
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
        }

        patchStatusCollector.record(
            patchId = PatchId.HOME_FEED_POST_CARDS,
            expectedTargetCount = HOME_FEED_POST_CARDS_TARGET_COUNT,
            actualTargetCount = HOME_FEED_POST_CARDS_TARGET_COUNT,
            reason = "HomeFeedPostCardSuppressionGuarded",
        )
    }
}

/**
 * 対象を見つけられなかった場合の記録。0 件は TARGET_NOT_FOUND、一部だけ解決できた場合は
 * PARTIAL とし、解決できた module 数をそのまま残します。
 */
internal fun homeFeedPostCardsUnappliedRecord(resolvedCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.HOME_FEED_POST_CARDS,
    status = if (resolvedCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.PARTIAL,
    expectedTargetCount = HOME_FEED_POST_CARDS_TARGET_COUNT,
    actualTargetCount = resolvedCount,
    reason = reason,
)

/** A missing, malformed, or ambiguous continuation intentionally leaves the target unmodified. */
internal fun homeFeedPostRendererOwner(continuationTypes: Set<String>): String? =
    continuationTypes.singleOrNull()?.let(::directEnclosingType)

/** view data の型だけが module ごとに変わるため、その位置は型を問わず並びだけを検証します。 */
internal fun isModuleRendererSignature(method: Method): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size == 5 &&
        parameters[0] == "Ljava/lang/String;" &&
        parameters[1].startsWith("L") &&
        parameters[2] == FEED_MODULE_STATE &&
        parameters[3] == COMPOSER &&
        parameters[4] == "I"
}

internal data class HomeFeedPostModuleGate(
    val branchIndex: Int,
    val shouldExecuteRegister: Int,
)

private fun homeFeedPostModuleGate(method: Method): HomeFeedPostModuleGate? {
    val implementation = method.implementation ?: return null
    return homeFeedPostModuleGateShape(
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
internal fun homeFeedPostModuleGateShape(
    instructions: List<Instruction>,
    hasTryBlocks: Boolean,
): HomeFeedPostModuleGate? {
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
    return HomeFeedPostModuleGate(branchIndex, resultMove.registerA)
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

private fun directEnclosingType(type: String): String? {
    val separator = type.lastIndexOf('$')
    return if (separator > 1 && type.endsWith(';')) type.substring(0, separator) + ";" else null
}

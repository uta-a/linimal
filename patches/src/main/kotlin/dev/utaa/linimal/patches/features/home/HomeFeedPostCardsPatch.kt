package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import dev.utaa.linimal.patches.features.agenti.agentIChatListSearchPatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.INT
import dev.utaa.linimal.patches.util.STRING
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.composeShouldExecuteGate
import dev.utaa.linimal.patches.util.composeShouldExecuteSuppression
import dev.utaa.linimal.patches.util.resolveComposeRuntime

private const val HOME_FEED_POST_CARD_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeFeedPostCardHooks;->shouldSuppress()Z"

/**
 * Home Feed の下部に投稿カードを描く module の view data が `toString()` に必ず残す marker。
 *
 * <p>26.11.0 では module controller の coroutine DebugMetadata（`GcsHomeFeedPostModuleController` など）
 * を anchor にしていましたが、26.14.0 では single / bigVisual の controller から suspend lambda が
 * 消え、DebugMetadata そのものが存在しなくなりました。module ごとの view data は data class のまま
 * なので、`toString()` に残るこの marker を anchor にします。error module と、別 feature が扱う
 * Matome module は含めません。</p>
 */
private val HOME_FEED_POST_VIEW_DATA_MARKERS = listOf(
    "GcsHomeFeedPost(postId=",
    "GcsHomeFeedUnitSingle(id=",
    "HomeFeedUnitBigVisual(homeFeedUnitBigVisual=",
)

/** card を描く module の数。1件でも解決できなければ一切注入しません。 */
internal val HOME_FEED_POST_CARDS_TARGET_COUNT = HOME_FEED_POST_VIEW_DATA_MARKERS.size

/** module の view data。`toString()` の marker だけを anchor にします。 */
private fun moduleViewDataFingerprint(marker: String) = Fingerprint(strings = listOf(marker))

/**
 * module の restartable composable。引数は module ごとに view data の型だけが変わるため、
 * その型を anchor にし、残りの並びと composer lifecycle の呼出しで一意に絞り込みます。
 */
private fun moduleRendererFingerprint(
    viewDataType: String,
    composer: String,
    composerImpl: String,
    shouldExecute: String,
    skipToGroupEnd: String,
) = Fingerprint(
    returnType = VOID,
    custom = { method, _ -> isModuleRendererSignature(method, viewDataType, composer) },
    // `endRestartGroup` は method 名を安定して導出できないため filter から外し、
    // 注入前の shape 判定側で「呼出しが 1 件だけあること」を確認します。
    filters = listOf(
        methodCall(
            definingClass = composerImpl,
            name = shouldExecute,
            parameters = listOf(INT, BOOLEAN),
            returnType = BOOLEAN,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        methodCall(
            definingClass = composerImpl,
            name = skipToGroupEnd,
            parameters = emptyList(),
            returnType = VOID,
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
val homeFeedPostCardsPatch = bytecodePatch(
    name = "ホームの投稿カード",
    description = "ホーム下部にある投稿カードを、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    // 機能パッチは単一の直列チェーンを成し、この patch の後段に premiumSettingsRowPatch が続きます。
    dependsOn(agentIChatListSearchPatch)

    execute {
        val composeRuntime = resolveComposeRuntime()
        if (composeRuntime == null) {
            patchStatusCollector.record(
                homeFeedPostCardsUnappliedRecord(0, "HomeFeedPostCardsRuntimeAnchorNotResolved"),
            )
            return@execute
        }

        val viewDataTypes = HOME_FEED_POST_VIEW_DATA_MARKERS.map { marker ->
            moduleViewDataFingerprint(marker).matchAllOrNull().orEmpty()
                .map { it.originalClassDef.type }
                .toSet()
                .singleOrNull()
        }
        if (viewDataTypes.any { it == null }) {
            patchStatusCollector.record(
                homeFeedPostCardsUnappliedRecord(
                    viewDataTypes.count { it != null },
                    "HomeFeedPostModuleViewDataNotUnique",
                ),
            )
            return@execute
        }

        val renderers = viewDataTypes.filterNotNull().map { viewDataType ->
            moduleRendererFingerprint(
                viewDataType = viewDataType,
                composer = composeRuntime.composer,
                composerImpl = composeRuntime.composerImpl,
                shouldExecute = composeRuntime.shouldExecute,
                skipToGroupEnd = composeRuntime.skipToGroupEnd,
            ).matchAllOrNull().orEmpty()
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
        val gates = methods.map { composeShouldExecuteGate(it, composeRuntime) }
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
            method.addInstructionsWithLabels(
                gate.branchIndex,
                composeShouldExecuteSuppression(gate, HOME_FEED_POST_CARD_HOOK),
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

/**
 * module renderer の引数の並び。module state の型は版ごとに変わるため、その位置は型を問わず、
 * module id・view data・composer・changed flag の並びだけを検証します。
 */
internal fun isModuleRendererSignature(method: Method, viewDataType: String, composer: String): Boolean {
    val parameters = method.parameterTypes.map { it.toString() }
    return parameters.size == 5 &&
        parameters[0] == STRING &&
        parameters[1] == viewDataType &&
        parameters[2].startsWith("L") &&
        parameters[3] == composer &&
        parameters[4] == INT
}

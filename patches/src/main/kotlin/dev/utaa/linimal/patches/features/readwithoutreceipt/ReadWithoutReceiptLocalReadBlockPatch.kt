package dev.utaa.linimal.patches.features.readwithoutreceipt

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.Method
import dev.utaa.linimal.patches.features.readreceipts.outboundGateFingerprint
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val READ_WITHOUT_RECEIPT_HOOKS =
    "Ldev/utaa/linimal/extension/features/readwithoutreceipt/ReadWithoutReceiptHooks;"
private const val SHOULD_BLOCK =
    "$READ_WITHOUT_RECEIPT_HOOKS->shouldBlockMarkAsRead(Ljava/lang/String;)Z"

/** this + J + String + Z の 4 parameter に対する register 数。J が 2 つ占めるため 5 です。 */
private const val PARAMETER_REGISTER_COUNT = 5

/** parameter 列の中で chatId (String) が何番目の register か。this, J, J, chatId の順です。 */
private const val CHAT_ID_PARAMETER_OFFSET = 3

internal data class MainChatMarkAsReadShape(val chatIdRegister: Int)

/**
 * 「既読をつけずに読む」で開いたトークについて、ローカルの未読クリアごと既読処理を止めます。
 *
 * <p>メイン 1:1 / グループの「既読にする」は 1 つのメソッド
 * （`q33.e.d(J, String, Z)V` 相当）に集約されており、その中で次の順に実行されます。</p>
 *
 * <pre>
 *   Lu13/l;->Y(chatId)V           ローカル未読のクリア（トーク一覧と下部タブのバッジを消す）
 *   Lu13/l;->Q0(msgId, chatId)V   既読位置 read_up の前進
 *   TalkServiceClient->j1(...)V   sendChatChecked（相手へ既読を伝える RPC）
 * </pre>
 *
 * <p>[readWithoutReceiptMarkAsReadBlockPatch] が止めているのは最後の RPC だけで、その手前で
 * 先に走るローカル更新は素通りします。そのため「既読をつけずに読む」で開いても自分側の未読
 * バッジが消えていました。本 patch はこのメソッドの入口で、対象トークだけ本体を実行せず
 * 即座に return void し、ローカル更新と RPC をまとめて止めます。</p>
 *
 * <p>対象の特定には「自動既読の停止」と同じ [outboundGateFingerprint] を共有します。fingerprint
 * を複製すると、片方だけ別のメソッドへ当たる余地が残るためです。あちらは同じメソッドの
 * 命令 index 5（local update と chat-list Runnable の合流点）へ注入するので、素の命令列を先に
 * 検証できるよう `readReceiptOutboundGatePatch` が先に走る位置へ本 patch を置いています。本 patch
 * 側は命令 0 の同一性を前提にせず、署名と register 割り当てだけを検証します。</p>
 *
 * <p>registerCount から parameter 列の開始位置を求め、chatId の register を決めます。scratch に
 * 使う v0 はメソッド入口では未初期化で、注入するブロックの外へ値を持ち出さないため安全です
 * （[readWithoutReceiptMarkAsReadBlockPatch] と同じ根拠）。local register が 1 つも無い
 * （parameterStart が 0 の）場合は v0 が parameter と衝突するため、注入せず Patch Status を
 * 記録して終了します。</p>
 */
val readWithoutReceiptLocalReadBlockPatch = bytecodePatch(
    name = "既読をつけずに読むの未読維持",
    description = "「既読をつけずに読む」で開いたトークだけ、ローカルの未読クリアも実行しないようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(readWithoutReceiptMarkAsReadBlockPatch)

    execute {
        val matches = outboundGateFingerprint.matchAllOrNull().orEmpty()
        if (matches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_LOCAL_READ_BLOCK),
                expectedTargetCount = 1,
                actualTargetCount = matches.size,
                reason = "ReadWithoutReceiptMarkAsReadNotUnique",
            )
            return@execute
        }

        val match = matches.single()
        val shape = mainChatMarkAsReadShape(match.originalMethod)
        if (shape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_LOCAL_READ_BLOCK),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadWithoutReceiptMarkAsReadRegisterShapeMismatch",
            )
            return@execute
        }

        match.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { v${shape.chatIdRegister} }, $SHOULD_BLOCK
                move-result v0
                if-eqz v0, :rwrLocalReadContinue
                return-void
                :rwrLocalReadContinue
                nop
            """.trimIndent(),
        )

        recordFeatureStatus(
            listOf(PatchId.READ_WITHOUT_RECEIPT_LOCAL_READ_BLOCK),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ReadWithoutReceiptLocalReadBlocked",
        )
    }
}

/**
 * chatId を保持する register を求めます。`outboundGateFingerprint` が署名を保証しているため、
 * ここでは register 割り当てだけを検証します。scratch の v0 を parameter と衝突させないよう、
 * local register が 1 つ以上あることを必須にします。
 */
internal fun mainChatMarkAsReadShape(method: Method): MainChatMarkAsReadShape? {
    val implementation = method.implementation ?: return null
    val parameterStart = implementation.registerCount - PARAMETER_REGISTER_COUNT
    if (parameterStart < 1) {
        return null
    }
    return MainChatMarkAsReadShape(chatIdRegister = parameterStart + CHAT_ID_PARAMETER_OFFSET)
}

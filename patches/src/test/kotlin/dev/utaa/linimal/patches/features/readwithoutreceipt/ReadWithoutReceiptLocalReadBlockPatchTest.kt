package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * chatId のレジスタ計算と、scratch に使う v0 が parameter と衝突しないことの検証です。
 * 命令列そのものは `outboundGateFingerprint` が保証するため、ここでは register 割り当てだけを見ます。
 */
class ReadWithoutReceiptLocalReadBlockPatchTest {

    @Test
    fun `the exact apk register layout resolves the chat id to v8`() {
        // 実測: registers=10, ins=5 (this + J + String + Z)。parameter 列は v5 から始まります。
        val shape = mainChatMarkAsReadShape(markAsReadMethod(registerCount = 10))

        assertEquals(8, shape?.chatIdRegister)
    }

    @Test
    fun `additional local registers shift the chat id register`() {
        val shape = mainChatMarkAsReadShape(markAsReadMethod(registerCount = 12))

        assertEquals(10, shape?.chatIdRegister)
    }

    @Test
    fun `a single local register is still enough for the scratch register`() {
        val shape = mainChatMarkAsReadShape(markAsReadMethod(registerCount = 6))

        assertEquals(4, shape?.chatIdRegister)
    }

    @Test
    fun `without a local register the scratch register would collide with a parameter`() {
        // registerCount が parameter 数ちょうどだと v0 が this になるため、注入しません。
        assertNull(mainChatMarkAsReadShape(markAsReadMethod(registerCount = 5)))
    }

    @Test
    fun `a method without an implementation is not injected`() {
        assertNull(mainChatMarkAsReadShape(abstractMarkAsReadMethod()))
    }

    private fun markAsReadMethod(registerCount: Int) = ImmutableMethod(
        OWNER_TYPE,
        "d",
        listOf(
            ImmutableMethodParameter("J", null, null),
            ImmutableMethodParameter("Ljava/lang/String;", null, null),
            ImmutableMethodParameter("Z", null, null),
        ),
        "V",
        0,
        null,
        null,
        ImmutableMethodImplementation(
            registerCount,
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            emptyList(),
            emptyList(),
        ),
    )

    private fun abstractMarkAsReadMethod() = ImmutableMethod(
        OWNER_TYPE,
        "d",
        listOf(
            ImmutableMethodParameter("J", null, null),
            ImmutableMethodParameter("Ljava/lang/String;", null, null),
            ImmutableMethodParameter("Z", null, null),
        ),
        "V",
        0,
        null,
        null,
        null,
    )

    private companion object {
        const val OWNER_TYPE = "Lq33/e;"
    }
}

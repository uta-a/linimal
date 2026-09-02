package dev.utaa.linimal.patches.features.chat

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * トーク一覧上部のボタン一覧を組み立てる suspend メソッドは、途中にも coroutine の suspend 復帰用の
 * `return-object` を持ちます。注入してよいのは「配列 → List の変換」から連続する末尾の 1 箇所だけなので、
 * ここではその shape 判定と、ボタン配列を作るメソッドの判定を固定します。
 */
class ChatListHeaderButtonsPatchTest {

    @Test
    fun `the button list builder must be a single target`() {
        assertEquals(1, CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT)
    }

    @Test
    fun `only a new-array of the button base type identifies the builder`() {
        assertTrue(createsChatListHeaderButtonArray(builder(BUTTON_ARRAY), BUTTON_ARRAY))
        // 別の型の配列を作るメソッドは対象外です。
        assertFalse(createsChatListHeaderButtonArray(builder("[Ljava/lang/Object;"), BUTTON_ARRAY))
    }

    @Test
    fun `the trailing conversion to a list is the injection point`() {
        val injection = chatListHeaderButtonsInjection(builderBody(resultRegister = 0), hasTryBlocks = false)
        assertEquals(ChatListHeaderButtonsInjection(index = 3, register = 0), injection)
    }

    @Test
    fun `a suspend return that is not the trailing conversion is rejected`() {
        // coroutine の suspend 復帰用の return-object だけで終わる形は対象外です。
        assertNull(
            chatListHeaderButtonsInjection(
                listOf(
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ),
                hasTryBlocks = false,
            ),
        )
        // 変換の返り値ではない register を返す形も拒否します。
        assertNull(
            chatListHeaderButtonsInjection(
                builderBody(resultRegister = 0, returnRegister = 1),
                hasTryBlocks = false,
            ),
        )
    }

    @Test
    fun `a conversion with a different signature is rejected`() {
        assertNull(
            chatListHeaderButtonsInjection(
                builderBody(
                    resultRegister = 0,
                    conversionParameters = listOf("Ljava/util/Collection;"),
                ),
                hasTryBlocks = false,
            ),
        )
        assertNull(
            chatListHeaderButtonsInjection(
                builderBody(resultRegister = 0, conversionReturnType = "Ljava/util/Set;"),
                hasTryBlocks = false,
            ),
        )
    }

    @Test
    fun `try blocks and a register the invocation cannot address are rejected`() {
        assertNull(chatListHeaderButtonsInjection(builderBody(resultRegister = 0), hasTryBlocks = true))
        // invoke-static は 4bit register しか取れないため、v16 以降は注入できません。
        assertNull(chatListHeaderButtonsInjection(builderBody(resultRegister = 16), hasTryBlocks = false))
    }

    @Test
    fun `a trailing return that is also a branch target is rejected`() {
        // 末尾の return-object (addr 0008) へ直接飛ぶ経路を足します。dexlib2 は注入位置へ新しい
        // location を挿入し、既存 location は Label を保持したまま後ろへずれるため、この経路だけが
        // 絞り込みを飛び越して元の List をそのまま返します。
        val diverted = guarded(returnJumpOffset = 8)

        assertTrue(isDivertedInjectionIndex(diverted, diverted.lastIndex))
        assertNull(chatListHeaderButtonsInjection(diverted, hasTryBlocks = false))
    }

    @Test
    fun `a branch that lands before the trailing conversion keeps the injection point`() {
        // 分岐先が new-array (addr 0002) なら末尾は分岐先ではなく、注入位置は末尾のままです。
        val safe = guarded(returnJumpOffset = 2)

        assertFalse(isDivertedInjectionIndex(safe, safe.lastIndex))
        assertEquals(
            ChatListHeaderButtonsInjection(index = 4, register = 0),
            chatListHeaderButtonsInjection(safe, hasTryBlocks = false),
        )
    }

    /**
     * 先頭に if-eqz を置いた形。addr 0000 の if-eqz から [returnJumpOffset] だけ進んだ address が
     * 分岐先になります。命令境界は addr 0000 / 0002 / 0004 / 0007 / 0008 です。
     */
    private fun guarded(returnJumpOffset: Int): List<Instruction> =
        listOf<Instruction>(ImmutableInstruction21t(Opcode.IF_EQZ, 1, returnJumpOffset)) +
            builderBody(resultRegister = 0)

    private fun builderBody(
        resultRegister: Int,
        returnRegister: Int = resultRegister,
        conversionParameters: List<String> = listOf("[Ljava/lang/Object;"),
        conversionReturnType: String = "Ljava/util/List;",
    ): List<Instruction> = listOf(
        ImmutableInstruction22c(
            Opcode.NEW_ARRAY,
            14,
            0,
            ImmutableTypeReference(BUTTON_ARRAY),
        ),
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            1,
            14, 0, 0, 0, 0,
            ImmutableMethodReference("Leb8/v;", "h", conversionParameters, conversionReturnType),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, resultRegister),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, returnRegister),
    )

    private fun builder(arrayType: String) = ImmutableMethod(
        "Lgv1/j0;",
        "r7",
        listOf(ImmutableMethodParameter("Lkotlin/coroutines/Continuation;", null, null)),
        "Ljava/lang/Object;",
        0,
        null,
        null,
        ImmutableMethodImplementation(
            16,
            listOf(
                ImmutableInstruction22c(Opcode.NEW_ARRAY, 14, 0, ImmutableTypeReference(arrayType)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
            null,
            null,
        ),
    )

    private companion object {
        const val BUTTON_ARRAY = "[Lgv1/a;"
    }
}

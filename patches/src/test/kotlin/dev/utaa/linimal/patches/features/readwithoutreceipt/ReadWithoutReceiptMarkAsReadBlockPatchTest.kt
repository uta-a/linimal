package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 対象の特定（`sendChatChecked` の所属クラス → それを `new-instance` する唯一のメソッド → 命令 4 つの
 * shape 検証）と、注入位置のレジスタ計算を、実測どおりの opcode / reference の並びで再現して検証します。
 */
class ReadWithoutReceiptMarkAsReadBlockPatchTest {

    // --- rpcArgWriterOwnerType: sendChatChecked を持つ候補から toString を除外して 1 件に絞る ---

    @Test
    fun `toString is excluded and the remaining single owner class is returned`() {
        val candidates = listOf(
            plainMethod(definingClass = ARG_WRITER_TYPE, name = "toString"),
            plainMethod(definingClass = ARG_WRITER_TYPE, name = "b"),
        )

        assertEquals(ARG_WRITER_TYPE, rpcArgWriterOwnerType(candidates))
    }

    @Test
    fun `only a toString candidate resolves to no owner class`() {
        val candidates = listOf(plainMethod(definingClass = ARG_WRITER_TYPE, name = "toString"))

        assertNull(rpcArgWriterOwnerType(candidates))
    }

    @Test
    fun `two owner classes after excluding toString are not unique`() {
        val candidates = listOf(
            plainMethod(definingClass = ARG_WRITER_TYPE, name = "toString"),
            plainMethod(definingClass = ARG_WRITER_TYPE, name = "b"),
            plainMethod(definingClass = "Lother/Writer;", name = "b"),
        )

        assertNull(rpcArgWriterOwnerType(candidates))
    }

    // --- methodHasNewInstanceOf: 特定の型への new-instance を持つかどうか ---

    @Test
    fun `a method that new-instances the argument writer type matches`() {
        val method = plainMethod(
            instructions = listOf(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(ARG_WRITER_TYPE))),
        )

        assertEquals(true, methodHasNewInstanceOf(method, ARG_WRITER_TYPE))
    }

    @Test
    fun `a method that new-instances a different type does not match`() {
        val method = plainMethod(
            instructions = listOf(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Lother/Type;"))),
        )

        assertEquals(false, methodHasNewInstanceOf(method, ARG_WRITER_TYPE))
    }

    // --- legacyTalkServiceRpcShape: j1 相当メソッドの shape 検証 ---

    @Test
    fun `the measured j1 shape resolves the chat id register`() {
        val shape = legacyTalkServiceRpcShape(rpcMethod(), ARG_WRITER_TYPE)

        // registerCount=5, parameterStart=1 (this=v1, I=v2, chatId=v3, messageId=v4)
        assertEquals(LegacyTalkServiceRpcShape(chatIdRegister = 3), shape)
    }

    @Test
    fun `a register count other than 5 is rejected`() {
        assertNull(legacyTalkServiceRpcShape(rpcMethod(registerCount = 6), ARG_WRITER_TYPE))
    }

    @Test
    fun `parameters other than (I, String, String) are rejected`() {
        assertNull(legacyTalkServiceRpcShape(rpcMethod(parameterTypes = listOf("I", STRING_TYPE)), ARG_WRITER_TYPE))
    }

    @Test
    fun `an extra instruction beyond the 4-instruction shape is rejected`() {
        val instructions = rpcInstructions() + ImmutableInstruction10x(Opcode.NOP)
        assertNull(legacyTalkServiceRpcShape(rpcMethod(instructions = instructions), ARG_WRITER_TYPE))
    }

    @Test
    fun `a try block on the call site is rejected`() {
        val implementation = ImmutableMethodImplementation(
            5,
            rpcInstructions(),
            listOf(ImmutableTryBlock(0, 1, emptyList())),
            emptyList(),
        )
        assertNull(legacyTalkServiceRpcShape(rpcMethod(implementation = implementation), ARG_WRITER_TYPE))
    }

    @Test
    fun `a new-instance of a different type than the resolved owner class is rejected`() {
        val instructions = listOf(
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Lother/Writer;")),
            constructorInvoke("Lother/Writer;"),
            rpcInvoke(),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertNull(legacyTalkServiceRpcShape(rpcMethod(instructions = instructions), ARG_WRITER_TYPE))
    }

    @Test
    fun `an invoke-interface RPC call instead of invoke-virtual is rejected`() {
        val instructions = listOf(
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(ARG_WRITER_TYPE)),
            constructorInvoke(ARG_WRITER_TYPE),
            ImmutableInstruction35c(
                Opcode.INVOKE_INTERFACE,
                1,
                0, 0, 0, 0, 0,
                ImmutableMethodReference(RPC_EXEC_TYPE, "a", emptyList(), "Ljava/lang/Object;"),
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertNull(legacyTalkServiceRpcShape(rpcMethod(instructions = instructions), ARG_WRITER_TYPE))
    }

    @Test
    fun `a constructor call that does not pass the outer instance and all three arguments is rejected`() {
        val instructions = listOf(
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(ARG_WRITER_TYPE)),
            ImmutableInstruction35c(
                Opcode.INVOKE_DIRECT,
                3,
                0, 1, 2, 0, 0,
                ImmutableMethodReference(ARG_WRITER_TYPE, "<init>", listOf(OUTER_TYPE, "I"), "V"),
            ),
            rpcInvoke(),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertNull(legacyTalkServiceRpcShape(rpcMethod(instructions = instructions), ARG_WRITER_TYPE))
    }

    private fun plainMethod(
        definingClass: String = ARG_WRITER_TYPE,
        name: String = "b",
        instructions: List<Instruction> = emptyList(),
    ) = ImmutableMethod(
        definingClass,
        name,
        emptyList(),
        "Ljava/lang/Object;",
        0,
        null,
        null,
        ImmutableMethodImplementation(1, instructions, emptyList(), emptyList()),
    )

    /**
     * 実測どおりの `j1` 相当メソッド: `(I, String, String)V`, registerCount=5,
     * `new-instance v0 → invoke-direct <init> → invoke-virtual (引数なし) → return-void`。
     */
    private fun rpcMethod(
        registerCount: Int = 5,
        parameterTypes: List<String> = listOf("I", STRING_TYPE, STRING_TYPE),
        instructions: List<Instruction> = rpcInstructions(),
        implementation: ImmutableMethodImplementation = ImmutableMethodImplementation(
            registerCount,
            instructions,
            emptyList(),
            emptyList(),
        ),
    ) = ImmutableMethod(
        OUTER_TYPE,
        "j1",
        parameterTypes.map { ImmutableMethodParameter(it, null, null) },
        "V",
        0,
        null,
        null,
        implementation,
    )

    private fun rpcInstructions(): List<Instruction> = listOf(
        // v0 = new $e(this=v1, p1=v2, chatId=v3, messageId=v4)
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(ARG_WRITER_TYPE)),
        constructorInvoke(ARG_WRITER_TYPE),
        rpcInvoke(),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

    private fun constructorInvoke(argWriterType: String) = ImmutableInstruction35c(
        Opcode.INVOKE_DIRECT,
        5,
        0, 1, 2, 3, 4,
        ImmutableMethodReference(argWriterType, "<init>", listOf(OUTER_TYPE, "I", STRING_TYPE, STRING_TYPE), "V"),
    )

    private fun rpcInvoke() = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1,
        0, 0, 0, 0, 0,
        ImmutableMethodReference(RPC_EXEC_TYPE, "a", emptyList(), "Ljava/lang/Object;"),
    )

    private companion object {
        const val ARG_WRITER_TYPE = "Lexample/LegacyTalkServiceClientImpl\$e;"
        const val OUTER_TYPE = "Lexample/LegacyTalkServiceClientImpl;"
        const val RPC_EXEC_TYPE = "Lexample/d\$d;"
        const val STRING_TYPE = "Ljava/lang/String;"
    }
}

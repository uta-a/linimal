package dev.utaa.linimal.patches.util

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 注入前のレジスタ検査が wide 命令の pair 上位半分を数えることを固定します。
 *
 * <p>この見落としは実際に起きました。退避に使った v4 が直後の `const-wide/16 v3` に潰され、
 * ART の verifier がクラスごと拒否してプロセスが落ちています。判定を別々に実装すると
 * 片方だけ直る事故が起きるため、共通実装をここで検証します。</p>
 */
class RegistersTest {
    @Test
    fun `a wide destination also occupies the following register`() {
        val constWide = ImmutableInstruction21s(Opcode.CONST_WIDE_16, 3, 0)

        assertTrue(instructionWritesRegister(constWide, 3))
        assertTrue(instructionWritesRegister(constWide, 4))
        assertFalse(instructionWritesRegister(constWide, 2))
        assertFalse(instructionWritesRegister(constWide, 5))
    }

    @Test
    fun `a narrow destination occupies only its own register`() {
        val const4 = ImmutableInstruction11n(Opcode.CONST_4, 3, 1)

        assertTrue(instructionWritesRegister(const4, 3))
        assertFalse(instructionWritesRegister(const4, 4))
    }

    @Test
    fun `an instruction that writes nothing is not a write`() {
        // invoke の結果は move-result が受け取るため、invoke 自体は register を潰しません。
        val invoke = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            3, 4, 0, 0, 0,
            ImmutableMethodReference("Lexample/A;", "a", listOf("Ljava/lang/String;"), "V"),
        )

        assertFalse(instructionWritesRegister(invoke, 3))
        assertFalse(instructionWritesRegister(invoke, 4))
        assertFalse(instructionWritesRegister(ImmutableInstruction10x(Opcode.NOP), 0))
    }

    @Test
    fun `a read is not a write`() {
        // iget-object v5, v5, field は v5 へ書きますが、source としての v5 は write ではありません。
        val iget = ImmutableInstruction22c(
            Opcode.IGET_OBJECT,
            5,
            9,
            ImmutableFieldReference("Lexample/A;", "a", "Ljava/lang/String;"),
        )

        assertTrue(instructionWritesRegister(iget, 5))
        assertFalse(instructionWritesRegister(iget, 9))
    }

    @Test
    fun `writing to any tracked register is detected through the wide pair`() {
        val constWide = ImmutableInstruction21s(Opcode.CONST_WIDE_16, 3, 0)

        assertTrue(instructionWritesAnyRegister(constWide, setOf(4)))
        assertTrue(instructionWritesAnyRegister(constWide, setOf(0, 3)))
        assertFalse(instructionWritesAnyRegister(constWide, setOf(0, 5)))
        assertFalse(instructionWritesAnyRegister(constWide, emptySet()))
    }

    @Test
    fun `a value does not survive a wide write to the pair upper half`() {
        val instructions = listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 4, 1),
            ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 3, 0),
            ImmutableInstruction10x(Opcode.NOP),
        )

        assertFalse(registerSurvivesBetween(instructions, 4, 0, 3))
        // 潰される前に読み出すなら生き残ります。
        assertTrue(registerSurvivesBetween(instructions, 4, 0, 2))
    }
}

package dev.utaa.linimal.patches.util

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSparseSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 注入位置が既存の経路に飲まれないことを固定します。
 *
 * <p>dexlib2 の `addInstructions(index, ...)` は新しい MethodLocation を挿入し、既存 location は Label を
 * 保持したまま後ろへずれます。そのため注入位置が既存の分岐先や例外 handler の先頭と一致すると、その
 * 経路だけが注入コードを飛び越します。実際に抑制が効かない不具合として現れています。</p>
 *
 * <p>`packed-switch` / `sparse-switch` の case 先は命令自身ではなく payload が持つため、
 * `OffsetInstruction` だけを見る判定では取りこぼします。その回帰もここで固定します。</p>
 */
class InstructionsTest {
    @Test
    fun `a branch target is rejected as an injection site`() {
        // if-eqz の分岐先 addr 0004 (index 3) へ注入すると、分岐した経路だけが注入を飛び越します。
        val instructions = branchInstructions()

        assertEquals(0x04, instructionAddress(instructions, 3))
        assertTrue(isDivertedInjectionIndex(instructions, 3))
    }

    @Test
    fun `the instruction after a branch target is a valid injection site`() {
        // 分岐先の 1 つ後ろなら、fall-through と分岐の双方が注入を通ります。
        val instructions = branchInstructions()

        assertFalse(isDivertedInjectionIndex(instructions, 4))
        assertFalse(isDivertedInjectionIndex(instructions, 2))
    }

    @Test
    fun `an exception handler head is rejected as an injection site`() {
        val implementation = handlerImplementation()
        val instructions = handlerInstructions()
        val handlerAddresses = exceptionHandlerAddresses(implementation)

        assertEquals(setOf(0x02), handlerAddresses)
        assertTrue(isDivertedInjectionIndex(instructions, 2, handlerAddresses))
        assertFalse(isDivertedInjectionIndex(instructions, 3, handlerAddresses))
    }

    @Test
    fun `packed-switch case targets are branch targets`() {
        // case 先は payload が持ちます。SwitchElement.offset は payload ではなく switch 命令の
        // address 基準なので、payload の address へ加えると別の位置を指してしまいます。
        val instructions = packedSwitchInstructions()

        assertEquals(setOf(0x08, 0x04, 0x06), branchTargetAddresses(instructions))
        assertTrue(isDivertedInjectionIndex(instructions, 2))
        assertTrue(isDivertedInjectionIndex(instructions, 4))
        assertFalse(isDivertedInjectionIndex(instructions, 3))
    }

    @Test
    fun `sparse-switch case targets are branch targets`() {
        val instructions = sparseSwitchInstructions()

        assertEquals(setOf(0x08, 0x04, 0x06), branchTargetAddresses(instructions))
        assertTrue(isDivertedInjectionIndex(instructions, 2))
        assertTrue(isDivertedInjectionIndex(instructions, 4))
        assertFalse(isDivertedInjectionIndex(instructions, 3))
    }

    @Test
    fun `switch case targets are resolved in the mutable representation too`() {
        // 実際の注入は MutableMethodImplementation 上で行われます。builder 側の SwitchElement も
        // switch 命令の address 基準で offset を返すことを、同じ結果になることで確かめます。
        val instructions = MutableMethodImplementation(packedSwitchImplementation()).instructions.toList()

        assertEquals(setOf(0x08, 0x04, 0x06), branchTargetAddresses(instructions))
    }

    @Test
    fun `an index outside the instruction list is rejected`() {
        // 位置を導けない以上、注入が飛び越されないことを保証できません。安全側で拒否します。
        val instructions = branchInstructions()

        assertTrue(isDivertedInjectionIndex(instructions, instructions.size))
        assertTrue(isDivertedInjectionIndex(instructions, -1))
        assertTrue(isDivertedInjectionIndex(emptyList(), 0))
    }

    /** addr 0000 の if-eqz が addr 0004 (index 3) へ分岐する命令列。 */
    private fun branchInstructions(): List<Instruction> = listOf(
        // addr 0000
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
        // addr 0002
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0003
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0004: 分岐先
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0005
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

    /** addr 0002 の move-exception を handler 先頭に持つ命令列。 */
    private fun handlerInstructions(): List<Instruction> = listOf(
        // addr 0000
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0001
        ImmutableInstruction10x(Opcode.RETURN_VOID),
        // addr 0002: handler の先頭
        ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 0),
        // addr 0003
        ImmutableInstruction11x(Opcode.THROW, 0),
    )

    private fun handlerImplementation(): MethodImplementation {
        val tryBlocks: List<TryBlock<out ExceptionHandler>> = listOf(
            ImmutableTryBlock(0, 2, listOf(ImmutableExceptionHandler(null, 0x02))),
        )
        return ImmutableMethodImplementation(1, handlerInstructions(), tryBlocks, emptyList())
    }

    /**
     * addr 0000 の packed-switch が addr 0008 の payload を指し、payload の case が addr 0004 と
     * addr 0006 へ飛ぶ命令列。case の offset は switch 命令の address 基準です。
     */
    private fun packedSwitchInstructions(): List<Instruction> = switchInstructions(
        Opcode.PACKED_SWITCH,
        ImmutablePackedSwitchPayload(
            listOf(ImmutableSwitchElement(0, 4), ImmutableSwitchElement(1, 6)),
        ),
    )

    /** packed-switch と同じ形の sparse-switch 版。key は連続しません。 */
    private fun sparseSwitchInstructions(): List<Instruction> = switchInstructions(
        Opcode.SPARSE_SWITCH,
        ImmutableSparseSwitchPayload(
            listOf(ImmutableSwitchElement(10, 4), ImmutableSwitchElement(20, 6)),
        ),
    )

    private fun switchInstructions(opcode: Opcode, payload: Instruction): List<Instruction> = listOf(
        // addr 0000: payload は addr 0008
        ImmutableInstruction31t(opcode, 0, 8),
        // addr 0003
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0004: case の飛び先
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0005
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0006: case の飛び先
        ImmutableInstruction10x(Opcode.RETURN_VOID),
        // addr 0007: payload を 4byte 境界へ揃える padding
        ImmutableInstruction10x(Opcode.NOP),
        // addr 0008
        payload,
    )

    private fun packedSwitchImplementation(): MethodImplementation =
        ImmutableMethodImplementation(1, packedSwitchInstructions(), emptyList(), emptyList())
}

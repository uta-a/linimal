package dev.utaa.linimal.patches.util

import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * この命令が [register] へ書き込むかどうか。
 *
 * <p>wide 命令は宛先 register と**その次の register** の pair へ書き込みます。上位半分は smali 上に
 * 現れないため、`registerA` だけを見る判定では見落とします。実際に `const-wide/16 v3` が v4 を潰し、
 * 退避しておいた値が読み出し前に破壊されて ART の verifier がクラスごと拒否した例があります。</p>
 */
fun instructionWritesRegister(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister() && !instruction.opcode.setsWideRegister()) {
        return false
    }
    val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register ||
        (instruction.opcode.setsWideRegister() && destination + 1 == register)
}

/** この命令が [registers] のいずれかへ書き込むかどうか。wide の上位半分も数えます。 */
fun instructionWritesAnyRegister(instruction: Instruction, registers: Set<Int>): Boolean =
    registers.any { instructionWritesRegister(instruction, it) }

/**
 * [definitionIndex] で [register] に書いた値が [useIndex] まで生き残るかどうか。
 * 間の命令が上位半分としてであれ同じ register へ書けば、値は失われます。
 */
fun registerSurvivesBetween(
    instructions: List<Instruction>,
    register: Int,
    definitionIndex: Int,
    useIndex: Int,
): Boolean = (definitionIndex + 1 until useIndex).none { index ->
    instructionWritesRegister(instructions[index], register)
}

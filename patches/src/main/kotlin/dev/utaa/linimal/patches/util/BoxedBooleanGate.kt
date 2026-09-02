package dev.utaa.linimal.patches.util

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** 注入位置と、そこで `Ljava/lang/Boolean;` を保持している register。 */
internal data class BoxedBooleanReturnGateShape(
    val insertionIndex: Int,
    val booleanRegister: Int,
)

/**
 * `Boolean.valueOf(結果)` → `move-result-object` → `return-object` で終わる suspend predicate を検証し、
 * その `return-object` の直前を注入位置として返します。
 *
 * <p>predicate は product 側の visibility 判定を実行したあと、結果を `Boolean` へ box して返します。
 * この box 命令は `if`/`goto` の合流点であり、その手前へ注入すると true 側の `goto` が hook を飛び越え、
 * 抑制が実行時にまったく効きません。そのため box 済みの値を単一の `return-object` 直前で unbox し、
 * hook を通してから box し直します。挿入位置がどの分岐先でもないことも明示的に検証します。</p>
 *
 * <p>Agent i 設定行と Premium 設定行はどちらも同じ shape の generated predicate を対象にするため、
 * この検証を共有します。片方だけの追加条件はありません。</p>
 */
internal fun boxedBooleanReturnGateShape(
    instructions: List<Instruction>,
    parameterTypes: List<String>,
    registerCount: Int,
    hasTryBlocks: Boolean,
): BoxedBooleanReturnGateShape? {
    if (
        parameterTypes != listOf(OBJECT) ||
        registerCount < 2 ||
        hasTryBlocks ||
        instructions.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    ) {
        return null
    }

    val returnIndex = instructions.indices.singleOrNull { index ->
        instructions[index].opcode == Opcode.RETURN_OBJECT
    } ?: return null
    val returnedValue = instructions[returnIndex] as? OneRegisterInstruction ?: return null
    val resultMove = instructions.getOrNull(returnIndex - 1) as? OneRegisterInstruction ?: return null
    val boxing = instructions.getOrNull(returnIndex - 2) as? FiveRegisterInstruction ?: return null
    val boxingReference = (instructions.getOrNull(returnIndex - 2) as? ReferenceInstruction)
        ?.reference as? MethodReference ?: return null

    if (
        resultMove.opcode != Opcode.MOVE_RESULT_OBJECT ||
        resultMove.registerA != returnedValue.registerA ||
        returnedValue.registerA !in 0..15 ||
        boxing.opcode != Opcode.INVOKE_STATIC ||
        boxing.registerCount != 1 ||
        boxing.registerC !in 0..15 ||
        boxingReference.definingClass != BOXED_BOOLEAN ||
        boxingReference.name != "valueOf" ||
        boxingReference.parameterTypes != listOf(BOOLEAN) ||
        boxingReference.returnType != BOXED_BOOLEAN
    ) {
        return null
    }

    // 挿入位置が分岐先だと、その分岐だけが hook を飛び越えます。
    if (isDivertedInjectionIndex(instructions, returnIndex)) {
        return null
    }

    return BoxedBooleanReturnGateShape(returnIndex, returnedValue.registerA)
}

package dev.utaa.linimal.patches.features.agenti

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.util.boxedBooleanReturnGateShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentISettingsPatchTest {
    @Test
    fun `missing dual variant predicates report target not found`() {
        val record = agentISettingsUnappliedRecord(0, "AgentISettingsPredicatesNotResolved")

        assertEquals(PatchId.AGENT_I_SETTINGS, record.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(2, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `incomplete dual variant predicates report error instead of partial injection`() {
        val record = agentISettingsUnappliedRecord(1, "AgentISettingsPredicatesNotResolved")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(2, record.expectedTargetCount)
        assertEquals(1, record.actualTargetCount)
    }

    @Test
    fun `additional dual variant predicate candidates report error`() {
        val record = agentISettingsUnappliedRecord(3, "AgentISettingsPredicatesNotResolved")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(3, record.actualTargetCount)
    }

    @Test
    fun `catalog shape mismatch preserves the raw predicate count`() {
        val record = agentISettingsShapeMismatchRecord(1, "AgentISettingsCatalogShapeMismatch")

        assertEquals(PatchId.AGENT_I_SETTINGS, record.patchId)
        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(2, record.expectedTargetCount)
        assertEquals(1, record.actualTargetCount)
    }

    @Test
    fun `gate targets the return site instead of the boxing branch target`() {
        // Boxing (index 4) は true 側の goto の分岐先です。そこへ注入すると true 分岐が hook を
        // 飛び越えるため、注入位置は必ず return-object (index 6) でなければなりません。
        val instructions = predicateInstructions()

        val shape = assertNotNull(gateShape(instructions))

        assertEquals(instructions.lastIndex, shape.insertionIndex)
        assertEquals(0, shape.booleanRegister)
    }

    @Test
    fun `gate is rejected when the return site is itself a branch target`() {
        // return-object が分岐先だと、その分岐だけ hook を飛び越えるため注入しません。
        val instructions = predicateInstructions(gotoOffset = 6)

        assertNull(gateShape(instructions))
    }

    @Test
    fun `gate is rejected when the predicate does not box its result`() {
        val instructions = predicateInstructions().toMutableList().apply {
            this[4] = ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                0,
                0,
                0,
                0,
                0,
                ImmutableMethodReference("Ljava/lang/Integer;", "valueOf", listOf("I"), "Ljava/lang/Integer;"),
            )
        }

        assertNull(gateShape(instructions))
    }

    @Test
    fun `gate is rejected for unexpected parameters registers and try blocks`() {
        val instructions = predicateInstructions()

        assertNull(gateShape(instructions, parameterTypes = listOf("Landroid/content/Context;")))
        assertNull(gateShape(instructions, registerCount = 1))
        assertNull(gateShape(instructions, hasTryBlocks = true))
    }

    @Test
    fun `gate is rejected for multiple returns and for switch dispatch`() {
        val instructions = predicateInstructions()

        assertNull(gateShape(listOf<Instruction>(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)) + instructions))
        assertNull(
            gateShape(listOf<Instruction>(ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 32)) + instructions),
        )
    }

    private fun gateShape(
        instructions: List<Instruction>,
        parameterTypes: List<String> = listOf("Ljava/lang/Object;"),
        registerCount: Int = 2,
        hasTryBlocks: Boolean = false,
    ) = boxedBooleanReturnGateShape(instructions, parameterTypes, registerCount, hasTryBlocks)

    /**
     * `LineUserMainSettingsCategory` の visible predicate と同じ shape。
     * false 側は boxing へ fall through し、true 側は goto で boxing へ合流します。
     */
    private fun predicateInstructions(gotoOffset: Int = 2): List<Instruction> = listOf(
        // addr 0: 判定が false なら addr 4 (const/4 v0, 0) へ。
        ImmutableInstruction21t(Opcode.IF_EQZ, 1, 4),
        // addr 2
        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
        // addr 3: 既定では addr 5 (boxing) へ合流します。
        ImmutableInstruction10t(Opcode.GOTO, gotoOffset),
        // addr 4
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
        // addr 5
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            1,
            0,
            0,
            0,
            0,
            0,
            ImmutableMethodReference("Ljava/lang/Boolean;", "valueOf", listOf("Z"), "Ljava/lang/Boolean;"),
        ),
        // addr 8
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        // addr 9
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    )
}

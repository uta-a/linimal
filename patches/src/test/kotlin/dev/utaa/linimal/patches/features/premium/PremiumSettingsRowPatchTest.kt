package dev.utaa.linimal.patches.features.premium

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PremiumSettingsRowPatchTest {
    @Test
    fun `both market variants are required`() {
        assertEquals(2, PREMIUM_SETTINGS_ROW_TARGET_COUNT)
    }

    @Test
    fun `unresolved variants report target not found`() {
        val record = premiumSettingsRowUnappliedRecord(0, "PremiumSettingsVariantsUnresolved")

        assertEquals(PatchId.PREMIUM_SETTINGS_ROW, record.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(PREMIUM_SETTINGS_ROW_TARGET_COUNT, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `a single resolved variant reports partial`() {
        val record = premiumSettingsRowUnappliedRecord(1, "PremiumSettingsPredicateNotUnique")

        assertEquals(PatchStatus.PARTIAL, record.status)
        assertEquals(1, record.actualTargetCount)
    }

    @Test
    fun `both variants resolved but unapplied reports error`() {
        val record = premiumSettingsRowUnappliedRecord(
            PREMIUM_SETTINGS_ROW_TARGET_COUNT,
            "PremiumSettingsGateShapeMismatch",
        )

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(PREMIUM_SETTINGS_ROW_TARGET_COUNT, record.actualTargetCount)
    }

    @Test
    fun `premium settings row keeps its own feature id`() {
        assertEquals(FeatureId.PREMIUM_SETTINGS_ROW, PatchId.PREMIUM_SETTINGS_ROW.featureId)
        assertEquals("linimal.premium-settings-row", PatchId.PREMIUM_SETTINGS_ROW.featureId.value)
        assertEquals("linimal.patch.premium-settings-row", PatchId.PREMIUM_SETTINGS_ROW.value)
    }

    @Test
    fun `gate adjusts the boxed result at its sole return`() {
        val instructions = predicateInstructions()

        val shape = assertNotNull(gateShape(instructions))

        assertEquals(instructions.lastIndex, shape.insertionIndex)
        assertEquals(0, shape.booleanRegister)
    }

    @Test
    fun `gate is rejected when a branch can skip it`() {
        // `return-object` が分岐先ならその経路は hook を通らないため、注入を見送ります。
        assertNull(gateShape(predicateInstructions(gotoOffset = 6)))
    }

    @Test
    fun `gate is rejected when return is not a Boolean box`() {
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
    fun `gate is rejected for unexpected method shape`() {
        val instructions = predicateInstructions()

        assertNull(gateShape(instructions, parameterTypes = listOf("Landroid/content/Context;")))
        assertNull(gateShape(instructions, registerCount = 1))
        assertNull(gateShape(instructions, hasTryBlocks = true))
        assertNull(gateShape(listOf<Instruction>(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)) + instructions))
        assertNull(gateShape(listOf<Instruction>(ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 32)) + instructions))
    }

    private fun gateShape(
        instructions: List<Instruction>,
        parameterTypes: List<String> = listOf("Ljava/lang/Object;"),
        registerCount: Int = 2,
        hasTryBlocks: Boolean = false,
    ) = premiumSettingsVisibilityGateShape(instructions, parameterTypes, registerCount, hasTryBlocks)

    /** Original predicate branches converge at Boolean boxing before its single object return. */
    private fun predicateInstructions(gotoOffset: Int = 2): List<Instruction> = listOf(
        ImmutableInstruction21t(Opcode.IF_EQZ, 1, 4),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
        ImmutableInstruction10t(Opcode.GOTO, gotoOffset),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
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
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    )
}

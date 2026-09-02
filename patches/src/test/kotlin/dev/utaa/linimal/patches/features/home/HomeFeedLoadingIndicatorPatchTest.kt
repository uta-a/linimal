package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 以前の実装は難読化された class 名（`Lve2/l;`）を fingerprint の主要条件にしていたため、
 * 別 package の全画面動画プレイヤーを誤って対象にしていました。この patch は
 * `GcsHomeFeedDefaultPageErrorModuleController` を指す DebugMetadata から package を裏取りして
 * から candidate を絞り込むため、これらのテストは package 導出と shape 判定の両方を検証します。
 */
class HomeFeedLoadingIndicatorPatchTest {

    @Test
    fun `only the loading host signature is accepted`() {
        assertTrue(
            isLoadingHostSignature(
                renderer(listOf("Z", "Z", "Lexample/State;", "Lexample/A;", "Lexample/B;", "Ly3/j;", "Lh3/t;", "I")),
            ),
        )
        // Modifier / Composer / I の並びが違えば対象外です。
        assertFalse(
            isLoadingHostSignature(
                renderer(listOf("Z", "Z", "Lexample/State;", "Lexample/A;", "Lexample/B;", "Lh3/t;", "I")),
            ),
        )
        // 先頭2つが boolean でなければ対象外です。
        assertFalse(
            isLoadingHostSignature(
                renderer(listOf("Lexample/State;", "Z", "Lexample/A;", "Lexample/B;", "Lexample/C;", "Ly3/j;", "Lh3/t;", "I")),
            ),
        )
    }
    @Test
    fun `the loading indicator renderer must be a single target`() {
        assertEquals(1, HOME_FEED_LOADING_INDICATOR_TARGET_COUNT)
    }

    @Test
    fun `no resolved renderer reports target not found and an ambiguous one reports error`() {
        val notFound = homeFeedLoadingIndicatorUnappliedRecord(0, "HomeFeedLoadingIndicatorSourcePackageNotUnique")
        assertEquals(PatchId.HOME_FEED_LOADING_INDICATOR, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_FEED_LOADING_INDICATOR_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        val ambiguous = homeFeedLoadingIndicatorUnappliedRecord(2, "HomeFeedLoadingIndicatorRendererNotUnique")
        assertEquals(PatchStatus.ERROR, ambiguous.status)
        assertEquals(2, ambiguous.actualTargetCount)
    }

    @Test
    fun `should execute branch is the injection point`() {
        val gate = homeFeedLoadingIndicatorGateShape(gateBody(shouldExecuteRegister = 1), hasTryBlocks = false)
        assertEquals(HomeFeedLoadingIndicatorGate(branchIndex = 2, shouldExecuteRegister = 1), gate)
    }

    @Test
    fun `a register the restore constant cannot address is rejected`() {
        // const/4 は 4bit register しか取れないため、v16 以降は注入できません。
        assertNull(homeFeedLoadingIndicatorGateShape(gateBody(shouldExecuteRegister = 16), hasTryBlocks = false))
    }

    @Test
    fun `try blocks and a missing skip path are rejected`() {
        assertNull(homeFeedLoadingIndicatorGateShape(gateBody(shouldExecuteRegister = 1), hasTryBlocks = true))
        assertNull(
            homeFeedLoadingIndicatorGateShape(
                gateBody(shouldExecuteRegister = 1, skipToGroupEndCount = 0),
                hasTryBlocks = false,
            ),
        )
        assertNull(
            homeFeedLoadingIndicatorGateShape(
                gateBody(shouldExecuteRegister = 1, skipToGroupEndCount = 2),
                hasTryBlocks = false,
            ),
        )
    }

    @Test
    fun `a branch that targets the injection point is rejected`() {
        // 既存の分岐が if-eqz を指していると、注入した gate を飛び越えて元の判定へ戻ります。
        val instructions = gateBody(shouldExecuteRegister = 1).toMutableList()
        // shouldExecute(3) + move-result(1) = 4 code units 先の if-eqz を指す分岐を先頭へ足します。
        instructions.add(0, ImmutableInstruction21t(Opcode.IF_EQZ, 1, 6))
        assertNull(homeFeedLoadingIndicatorGateShape(instructions, hasTryBlocks = false))
    }

    private fun renderer(parameters: List<String>) = ImmutableMethod(
        "Lve2/l;",
        "a",
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        0,
        null,
        null,
        null,
    )

    private fun gateBody(
        shouldExecuteRegister: Int,
        skipToGroupEndCount: Int = 1,
    ): List<Instruction> = buildList {
        add(composerCall("A", listOf("I", "Z"), "Z"))
        add(ImmutableInstruction11x(Opcode.MOVE_RESULT, shouldExecuteRegister))
        add(ImmutableInstruction21t(Opcode.IF_EQZ, shouldExecuteRegister, 4))
        add(ImmutableInstruction10x(Opcode.NOP))
        repeat(skipToGroupEndCount) { add(composerCall("l", emptyList(), "V")) }
        add(composerCall("Y", emptyList(), "Lh3/p3;"))
    }

    private fun composerCall(
        name: String,
        parameters: List<String>,
        returnType: String,
    ) = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1,
        6, 0, 0, 0, 0,
        ImmutableMethodReference("Lh3/f1;", name, parameters, returnType),
    )
}

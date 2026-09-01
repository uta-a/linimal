package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeFeedPostCardsPatchTest {
    @Test
    fun `metadata continuation resolves only its direct enclosing renderer owner`() {
        assertEquals(
            "Lexample/home/PostModule;",
            homeFeedPostRendererOwner(setOf("Lexample/home/PostModule\$1;")),
        )
        // 入れ子が深い continuation でも、最外殻ではなく直接の enclosing type だけを owner にします。
        assertEquals(
            "Lexample/home/PostModule\$Stateful;",
            homeFeedPostRendererOwner(setOf("Lexample/home/PostModule\$Stateful\$1;")),
        )
    }

    @Test
    fun `missing ambiguous or malformed continuation leaves the target unresolved`() {
        assertNull(homeFeedPostRendererOwner(emptySet()))
        assertNull(
            homeFeedPostRendererOwner(
                setOf("Lexample/home/First\$1;", "Lexample/home/Second\$1;"),
            ),
        )
        assertNull(homeFeedPostRendererOwner(setOf("Lexample/home/PostModule")))
    }

    @Test
    fun `every post module controller must resolve before anything is injected`() {
        assertEquals(3, HOME_FEED_POST_CARDS_TARGET_COUNT)
    }

    @Test
    fun `no resolved module reports target not found and a partial one reports partial`() {
        val notFound = homeFeedPostCardsUnappliedRecord(0, "HomeFeedPostModuleContinuationNotUnique")
        assertEquals(PatchId.HOME_FEED_POST_CARDS, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_FEED_POST_CARDS_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        val partial = homeFeedPostCardsUnappliedRecord(2, "HomeFeedPostRendererNotUnique")
        assertEquals(PatchStatus.PARTIAL, partial.status)
        assertEquals(2, partial.actualTargetCount)
    }

    @Test
    fun `should execute branch is the injection point`() {
        val gate = homeFeedPostModuleGateShape(renderer(shouldExecuteRegister = 12), hasTryBlocks = false)
        assertEquals(HomeFeedPostModuleGate(branchIndex = 2, shouldExecuteRegister = 12), gate)
    }

    @Test
    fun `a register the restore constant cannot address is rejected`() {
        // const/4 は 4bit register しか取れないため、v16 以降は注入できません。
        assertNull(homeFeedPostModuleGateShape(renderer(shouldExecuteRegister = 16), hasTryBlocks = false))
    }

    @Test
    fun `try blocks and a missing skip path are rejected`() {
        assertNull(homeFeedPostModuleGateShape(renderer(shouldExecuteRegister = 12), hasTryBlocks = true))
        assertNull(
            homeFeedPostModuleGateShape(
                renderer(shouldExecuteRegister = 12, skipToGroupEndCount = 0),
                hasTryBlocks = false,
            ),
        )
        assertNull(
            homeFeedPostModuleGateShape(
                renderer(shouldExecuteRegister = 12, skipToGroupEndCount = 2),
                hasTryBlocks = false,
            ),
        )
    }

    @Test
    fun `a branch that targets the injection point is rejected`() {
        // 既存の分岐が if-eqz を指していると、注入した gate を飛び越えて元の判定へ戻ります。
        val instructions = renderer(shouldExecuteRegister = 12).toMutableList()
        // shouldExecute(3) + move-result(1) = 4 code units 先の if-eqz を指す分岐を先頭へ足します。
        instructions.add(0, ImmutableInstruction21t(Opcode.IF_EQZ, 1, 6))
        assertNull(homeFeedPostModuleGateShape(instructions, hasTryBlocks = false))
    }

    private fun renderer(
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

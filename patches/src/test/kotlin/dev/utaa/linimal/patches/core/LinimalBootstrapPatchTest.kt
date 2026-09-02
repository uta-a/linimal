package dev.utaa.linimal.patches.core

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bootstrap は process 判定を通ったすべての経路が通る必要があります。dexlib2 は注入位置へ新しい
 * location を挿入し、既存 location は Label を保持したまま後ろへずれるため、注入位置が既存の
 * 分岐先や例外 handler の先頭と一致すると、その経路だけが設定の初期化を飛び越します。
 */
class LinimalBootstrapPatchTest {
    @Test
    fun `the graph init site is the injection point`() {
        val instructions = initialize()

        assertFalse(isDivertedInjectionIndex(instructions, GRAPH_INIT_INDEX))
        assertEquals(
            GRAPH_INIT_INDEX,
            bootstrapInjectionIndex(instructions, GRAPH_INIT_INDEX, emptySet()),
        )
    }

    @Test
    fun `an injection point that is also a branch target is rejected`() {
        // process 判定の if-eqz が ApplicationGraph.init (addr 0006) そのものへ飛ぶ形にします。
        // その経路だけが bootstrap を飛び越し、設定が未初期化のまま機能 hook が動きます。
        val instructions = initialize(uidGuardJumpOffset = 2)

        assertTrue(isDivertedInjectionIndex(instructions, GRAPH_INIT_INDEX))
        assertNull(bootstrapInjectionIndex(instructions, GRAPH_INIT_INDEX, emptySet()))
    }

    @Test
    fun `an injection point that is an exception handler head is rejected`() {
        assertNull(
            bootstrapInjectionIndex(initialize(), GRAPH_INIT_INDEX, handlerAddresses = setOf(GRAPH_INIT_ADDRESS)),
        )
    }

    @Test
    fun `an index outside the instruction list is rejected`() {
        assertNull(bootstrapInjectionIndex(initialize(), initialize().size, emptySet()))
    }

    /**
     * `LineApplication.initialize` の骨格。process 判定 → ApplicationGraph の初期化、という順序だけを
     * 再現します。命令境界は addr 0000 / 0003 / 0004 / 0006 / 0008 / 000b です。
     */
    private fun initialize(uidGuardJumpOffset: Int = 7): List<Instruction> = listOf(
        // addr 0000
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            0,
            0, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/os/Process;", "myUid", emptyList(), "I"),
        ),
        // addr 0003
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        // addr 0004: main process でなければ初期化を丸ごと飛ばします。
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, uidGuardJumpOffset),
        // addr 0006: 注入位置
        ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("ApplicationGraph.init")),
        // addr 0008
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            1,
            1, 0, 0, 0, 0,
            ImmutableMethodReference("Ljp/naver/line/android/LineApplication;", "a", listOf("Ljava/lang/String;"), "V"),
        ),
        // addr 000b
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

    private companion object {
        const val GRAPH_INIT_INDEX = 3
        const val GRAPH_INIT_ADDRESS = 0x06
    }
}

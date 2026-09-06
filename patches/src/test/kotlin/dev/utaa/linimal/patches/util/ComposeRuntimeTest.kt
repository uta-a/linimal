package dev.utaa.linimal.patches.util

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ホームの Compose 系 patch が共有する gate の shape 判定です。26.11.0 では 5 つの patch が
 * それぞれ同じ判定を持ち、Compose runtime の難読化名も 5 箇所へ書かれていました。
 */
class ComposeRuntimeTest {
    @Test
    fun `should execute branch is the injection point`() {
        assertEquals(
            ComposeShouldExecuteGate(branchIndex = 2, shouldExecuteRegister = 1),
            composeShouldExecuteGateShape(gateBody(shouldExecuteRegister = 1), false, COMPOSE_RUNTIME),
        )
    }

    @Test
    fun `a register the restore constant cannot address is rejected`() {
        // const/4 は 4bit register しか取れないため、v16 以降は注入できません。
        assertNull(composeShouldExecuteGateShape(gateBody(shouldExecuteRegister = 16), false, COMPOSE_RUNTIME))
    }

    @Test
    fun `try blocks are rejected`() {
        assertNull(composeShouldExecuteGateShape(gateBody(shouldExecuteRegister = 1), true, COMPOSE_RUNTIME))
    }

    @Test
    fun `an ambiguous or missing skip path is rejected`() {
        assertNull(
            composeShouldExecuteGateShape(
                gateBody(shouldExecuteRegister = 1, skipToGroupEndCount = 0),
                false,
                COMPOSE_RUNTIME,
            ),
        )
        assertNull(
            composeShouldExecuteGateShape(
                gateBody(shouldExecuteRegister = 1, skipToGroupEndCount = 2),
                false,
                COMPOSE_RUNTIME,
            ),
        )
    }

    @Test
    fun `an ambiguous or missing restart group end is rejected`() {
        assertNull(
            composeShouldExecuteGateShape(
                gateBody(shouldExecuteRegister = 1, endRestartGroupCount = 0),
                false,
                COMPOSE_RUNTIME,
            ),
        )
        assertNull(
            composeShouldExecuteGateShape(
                gateBody(shouldExecuteRegister = 1, endRestartGroupCount = 2),
                false,
                COMPOSE_RUNTIME,
            ),
        )
    }

    @Test
    fun `an ambiguous should execute call is rejected`() {
        assertNull(
            composeShouldExecuteGateShape(
                gateBody(shouldExecuteRegister = 1, shouldExecuteCount = 2),
                false,
                COMPOSE_RUNTIME,
            ),
        )
    }

    @Test
    fun `a shape without the move result and branch sequence is rejected`() {
        val missingMoveResult = gateBody(shouldExecuteRegister = 1).toMutableList()
        // move-result を欠く実装は、判定結果の register を確定できないため対象外です。
        missingMoveResult.removeAt(1)
        assertNull(composeShouldExecuteGateShape(missingMoveResult, false, COMPOSE_RUNTIME))

        val otherRegister = gateBody(shouldExecuteRegister = 1).toMutableList()
        // 分岐が判定結果ではない register を見ている実装も対象外です。
        otherRegister[2] = ImmutableInstruction21t(Opcode.IF_EQZ, 6, 4)
        assertNull(composeShouldExecuteGateShape(otherRegister, false, COMPOSE_RUNTIME))
    }

    @Test
    fun `a branch that targets the injection point is rejected`() {
        // 既存の分岐が if-eqz を指していると、注入した gate を飛び越えて元の判定へ戻ります。
        val instructions = gateBody(shouldExecuteRegister = 1).toMutableList()
        // shouldExecute(3) + move-result(1) = 4 code units 先の if-eqz を指す分岐を先頭へ足します。
        instructions.add(0, ImmutableInstruction21t(Opcode.IF_EQZ, 1, 6))
        assertNull(composeShouldExecuteGateShape(instructions, false, COMPOSE_RUNTIME))
    }

    /** 別の composer 実装の呼出しは、名前が一致していても gate になりません。 */
    @Test
    fun `calls on another composer implementation are ignored`() {
        val otherRuntime = COMPOSE_RUNTIME.copy(composerImpl = "Lexample/compose/OtherComposerImpl;")
        assertNull(composeShouldExecuteGateShape(gateBody(shouldExecuteRegister = 1), false, otherRuntime))
    }

    @Test
    fun `the suppression restores the original result when the setting is off`() {
        val smali = composeShouldExecuteSuppression(
            ComposeShouldExecuteGate(branchIndex = 2, shouldExecuteRegister = 3),
            "Lexample/Hooks;->shouldSuppress()Z",
        )

        assertTrue(smali.contains("invoke-static { }, Lexample/Hooks;->shouldSuppress()Z"))
        // 抑制時は 0、非抑制時は shouldExecute が返すのと同じ 1 に戻します。
        assertTrue(smali.contains("const/4 v3, 0x0"))
        assertTrue(smali.contains("const/4 v3, 0x1"))
    }

    private fun gateBody(
        shouldExecuteRegister: Int,
        shouldExecuteCount: Int = 1,
        skipToGroupEndCount: Int = 1,
        endRestartGroupCount: Int = 1,
    ): List<Instruction> = buildList {
        add(composerCall(COMPOSE_RUNTIME.shouldExecute, listOf("I", "Z"), "Z"))
        add(ImmutableInstruction11x(Opcode.MOVE_RESULT, shouldExecuteRegister))
        add(ImmutableInstruction21t(Opcode.IF_EQZ, shouldExecuteRegister, 4))
        add(ImmutableInstruction10x(Opcode.NOP))
        repeat(shouldExecuteCount - 1) { add(composerCall(COMPOSE_RUNTIME.shouldExecute, listOf("I", "Z"), "Z")) }
        repeat(skipToGroupEndCount) { add(composerCall(COMPOSE_RUNTIME.skipToGroupEnd, emptyList(), "V")) }
        repeat(endRestartGroupCount) {
            // restartable composable 末尾の `endRestartGroup()?.updateScope { ... }` の形です。
            add(composerCall("Z", emptyList(), "Lexample/compose/ScopeUpdateScope;"))
            add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
            add(ImmutableInstruction21t(Opcode.IF_EQZ, 0, 2))
        }
    }

    private fun composerCall(
        name: String,
        parameters: List<String>,
        returnType: String,
    ) = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1,
        6, 0, 0, 0, 0,
        ImmutableMethodReference(COMPOSE_RUNTIME.composerImpl, name, parameters, returnType),
    )

    private companion object {
        /** 難読化名は版ごとに変わるため、テストでは実物ではなく stand-in を使います。 */
        val COMPOSE_RUNTIME = ComposeRuntime(
            composer = "Lexample/compose/Composer;",
            composerImpl = "Lexample/compose/ComposerImpl;",
            shouldExecute = "A",
            skipToGroupEnd = "l",
        )
    }
}

package dev.utaa.linimal.patches.features.readreceipts

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.utaa.linimal.patches.util.fallsThrough
import dev.utaa.linimal.patches.util.instructionWritesRegister
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex
import dev.utaa.linimal.patches.util.registerSurvivesBetween
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 注入位置の回帰テスト。dexlib2 の `addInstructions(index, ...)` は新しい MethodLocation を挿入し、
 * 既存 location は Label を保持したまま後ろへずれるため、既存の分岐先や例外 handler の先頭へ
 * 注入するとその経路だけが注入コードを飛び越します。
 */
class MainChatReadReceiptPatchTest {
    // --- supplier worker (Lq33/c;->run()V) ---

    @Test
    fun `a wide destination also occupies the following register`() {
        // const-wide/16 v3 は v3 と v4 の pair へ書きます。smali 上に v4 は現れません。
        val constWide = ImmutableInstruction21s(Opcode.CONST_WIDE_16, 3, 0)

        assertTrue(instructionWritesRegister(constWide, 3))
        assertTrue(instructionWritesRegister(constWide, 4))
        assertFalse(instructionWritesRegister(constWide, 5))
    }

    @Test
    fun `an invoke writes no register`() {
        // invoke の結果は move-result が受け取ります。invoke 自体は register を潰しません。
        assertFalse(instructionWritesRegister(supplierWorkerInstructions()[2], 4))
    }

    @Test
    fun `the scratch register does not survive across the read point comparison`() {
        // 旧実装は命令 0 で v4 へ this を退避し、命令 9 の直前で読み出していました。間の
        // const-wide/16 v3 が v4 を潰すため、この前提は成り立ちません（実機で VerifyError）。
        val instructions = supplierWorkerInstructions()

        assertFalse(registerSurvivesBetween(instructions, 4, 0, 9))
    }

    @Test
    fun `the scratch register survives when its use is adjacent to its definition`() {
        // 現行実装は v4 へ chatId を読み、次の命令で prepareSupplier へ渡すだけです。
        val instructions = supplierWorkerInstructions()

        assertTrue(registerSurvivesBetween(instructions, 4, 0, 1))
    }

    @Test
    fun `supplier preparation is injected where every path reaches it`() {
        // 注入前の index 0 / 7 / 10 が prefix・早期 return の cleanup・正常終了の cleanup です。
        // 注入によって index はずれますが、判定は必ず注入前の並びと index で行います。
        val instructions = supplierWorkerInstructions()

        assertEquals(listOf(0, 7, 10), SUPPLIER_PREPARATION_INJECTION_INDICES)
        assertFalse(supplierPreparationInjectionDiverted(instructions, emptySet()))
    }

    @Test
    fun `supplier preparation is rejected when a cleanup site is a branch target`() {
        // read point の判定が早期 return (addr 000e) そのものへ飛ぶ形にします。cleanup はその return の
        // 直前へ入るため、この経路だけが one-shot を解放しないまま戻ります。
        val instructions = supplierWorkerInstructions(readPointJumpOffset = 2)

        assertTrue(isDivertedInjectionIndex(instructions, 7))
        assertTrue(supplierPreparationInjectionDiverted(instructions, emptySet()))
    }

    @Test
    fun `supplier preparation is rejected when the prefix site is a branch target`() {
        // 末尾に addr 0000 へ戻る goto を足すと、prefix の注入位置が分岐先と一致し、その経路だけが
        // prepareSupplier を呼ばずに run() 本体へ入ります。
        val instructions = supplierWorkerInstructions(
            trailing = listOf(ImmutableInstruction10t(Opcode.GOTO, -0x14)),
        )

        assertTrue(isDivertedInjectionIndex(instructions, 0))
        assertTrue(supplierPreparationInjectionDiverted(instructions, emptySet()))
    }

    @Test
    fun `supplier preparation is rejected when a cleanup site is an exception handler head`() {
        // addr 0013 は d() 正常終了後の return です。ここが handler 先頭だと例外経路が cleanup を
        // 飛び越します。元の supplier に handler はありませんが、前提が崩れた場合に備えて拒否します。
        val instructions = supplierWorkerInstructions()

        assertTrue(supplierPreparationInjectionDiverted(instructions, handlerAddresses = setOf(0x13)))
    }

    // --- outbound gate (Lq33/e;->d(JLjava/lang/String;Z)V) ---

    @Test
    fun `outbound gate is injected after the branch merge point`() {
        // 合流点 index 5 (addr 000c) は命令 0 の if-eqz の分岐先そのものです。そこへ注入すると
        // 第 4 引数 Z が false の経路だけが gate を飛び越し、抑制されないまま送信へ到達します。
        val instructions = outboundGateInstructions()

        assertTrue(isDivertedInjectionIndex(instructions, 5))
        assertFalse(isDivertedInjectionIndex(instructions, 6))
        assertEquals(6, outboundGateInsertionIndex(instructions, emptySet()))
    }

    @Test
    fun `outbound gate is rejected when the insertion site is another branch target`() {
        // 末尾に addr 000e (index 6) へ戻る goto を足すと、注入位置がその分岐先と一致します。
        val instructions = outboundGateInstructions(trailing = listOf(ImmutableInstruction10t(Opcode.GOTO, -6)))

        assertTrue(isDivertedInjectionIndex(instructions, 6))
        assertNull(outboundGateInsertionIndex(instructions, emptySet()))
    }

    @Test
    fun `outbound gate is rejected when the insertion site is an exception handler head`() {
        val instructions = outboundGateInstructions()

        assertTrue(isDivertedInjectionIndex(instructions, 6, handlerAddresses = setOf(0x0e)))
        assertNull(outboundGateInsertionIndex(instructions, setOf(0x0e)))
    }

    @Test
    fun `outbound gate is rejected when the leading branch does not skip the local update`() {
        // 分岐先が合流点 index 5 でなくなった shape は、注入位置を導けないので拒否します。
        assertNull(outboundGateInsertionIndex(outboundGateInstructions(skipOffset = 14), emptySet()))
        assertNull(
            outboundGateInsertionIndex(outboundGateInstructions().drop(1), emptySet()),
        )
    }

    // --- manual caller (Lv11/a;->a(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;) ---

    @Test
    fun `generic handler cleanup is injected after the handler head`() {
        val instructions = manualCallerInstructions()

        // handler 先頭 (addr 0007 / const/4 v3, 0) へ注入すると例外経路が飛び越します。
        assertTrue(isDivertedInjectionIndex(instructions, GENERIC_HANDLER_INDEX, MANUAL_CALLER_HANDLER_ADDRESSES))
        // さらに直前は return-object なので fall-through も無く、cleanup は一度も実行されません。
        assertFalse(fallsThrough(instructions[GENERIC_HANDLER_INDEX - 1]))

        assertEquals(GENERIC_HANDLER_INDEX + 1, exceptionHandlerCleanupIndex(instructions, GENERIC_HANDLER_INDEX))
    }

    @Test
    fun `cancellation handler cleanup stays right after move-exception`() {
        val instructions = manualCallerInstructions()

        assertEquals(
            CANCELLATION_HANDLER_INDEX + 1,
            exceptionHandlerCleanupIndex(instructions, CANCELLATION_HANDLER_INDEX),
        )
    }

    @Test
    fun `handler cleanup is rejected when the handler head cannot fall through`() {
        val instructions = manualCallerInstructions()

        // return-object / throw の直後へ置いた cleanup は実行されません。
        assertNull(exceptionHandlerCleanupIndex(instructions, GENERIC_HANDLER_INDEX - 1))
        assertNull(exceptionHandlerCleanupIndex(instructions, instructions.lastIndex))
    }

    @Test
    fun `injecting at the handler head is skipped by the exception path`() {
        // dexlib2 の実挙動での再現。handler ラベルは既存 location に残るため、handler 先頭へ
        // 注入した命令は例外経路から到達できません。
        val implementation = MutableMethodImplementation(manualCallerImplementation())
        implementation.addInstruction(GENERIC_HANDLER_INDEX, BuilderInstruction10x(Opcode.NOP))

        val entryIndex = genericHandlerEntryIndex(implementation)

        assertEquals(Opcode.NOP, implementation.instructions[GENERIC_HANDLER_INDEX].opcode)
        assertNotEquals(GENERIC_HANDLER_INDEX, entryIndex)
        assertEquals(Opcode.CONST_4, implementation.instructions[entryIndex].opcode)
    }

    @Test
    fun `injecting after the handler head is reached by the exception path`() {
        val implementation = MutableMethodImplementation(manualCallerImplementation())
        implementation.addInstruction(GENERIC_HANDLER_INDEX + 1, BuilderInstruction10x(Opcode.NOP))

        val entryIndex = genericHandlerEntryIndex(implementation)

        assertEquals(GENERIC_HANDLER_INDEX, entryIndex)
        assertEquals(Opcode.CONST_4, implementation.instructions[entryIndex].opcode)
        assertEquals(Opcode.NOP, implementation.instructions[entryIndex + 1].opcode)
    }

    private fun genericHandlerEntryIndex(implementation: MutableMethodImplementation): Int {
        val handlerAddress = implementation.tryBlocks
            .flatMap { block -> block.exceptionHandlers }
            .first { handler -> handler.exceptionType == null }
            .handlerCodeAddress
        var address = 0
        implementation.instructions.forEachIndexed { index, instruction ->
            if (address == handlerAddress) {
                return index
            }
            address += instruction.codeUnits
        }
        error("handler address $handlerAddress not found")
    }

    /**
     * `Lq33/e;->d` の先頭。index 0 の if-eqz が local update と chat-list Runnable を飛び越し、
     * index 5 (addr 000c) の queue 取得で合流します。
     */
    private fun outboundGateInstructions(
        skipOffset: Int = 12,
        trailing: List<Instruction> = emptyList(),
    ): List<Instruction> = listOf<Instruction>(
        // addr 0000
        ImmutableInstruction21t(Opcode.IF_EQZ, 9, skipOffset),
        // addr 0002
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, field("Lq33/e;", "c", "Lu13/l;")),
        // addr 0004
        ImmutableInstruction35c(
            Opcode.INVOKE_INTERFACE,
            2,
            9,
            8,
            0,
            0,
            0,
            ImmutableMethodReference("Lu13/l;", "Y", listOf("Ljava/lang/String;"), "V"),
        ),
        // addr 0007
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, field("Lq33/e;", "h", "Lq33/b;")),
        // addr 0009
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            1,
            9,
            0,
            0,
            0,
            0,
            ImmutableMethodReference("Lq33/b;", "run", emptyList(), "V"),
        ),
        // addr 000c: 合流点
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, field("Lq33/e;", "b", "Lq33/f;")),
        // addr 000e: 注入位置
        ImmutableInstruction11x(Opcode.MONITOR_ENTER, 9),
        // addr 000f
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            1,
            9,
            0,
            0,
            0,
            0,
            ImmutableMethodReference("Lq33/f;", "a", emptyList(), "V"),
        ),
        // addr 0012
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 9, field("Lq33/f;", "b", "Ljava/util/HashMap;")),
    ) + trailing

    /**
     * `Lv11/a;->a` の末尾。`<any>` handler は addr 0007 の const/4 で始まり、その直前は
     * return-object なので fall-through がありません。
     */
    private fun manualCallerInstructions(): List<Instruction> = listOf(
        // addr 0000
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            2,
            4,
            0,
            0,
            0,
            0,
            ImmutableMethodReference("Lff8/l;", "a", listOf("Lap7/b;", "Llb8/c;"), "Ljava/lang/Object;"),
        ),
        // addr 0003
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
        // addr 0004
        ImmutableInstruction22t(Opcode.IF_NE, 4, 1, 4),
        // addr 0006
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        // addr 0007: <any> handler の先頭
        ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
        // addr 0008: 正常完了の分岐先
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            1,
            3,
            0,
            0,
            0,
            0,
            ImmutableMethodReference("Ljava/lang/Boolean;", "valueOf", listOf("Z"), "Ljava/lang/Boolean;"),
        ),
        // addr 000b
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
        // addr 000c
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 4),
        // addr 000d: CancellationException handler の先頭
        ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 4),
        // addr 000e
        ImmutableInstruction11x(Opcode.THROW, 4),
    )

    private fun manualCallerImplementation(): MethodImplementation {
        val tryBlocks: List<TryBlock<out ExceptionHandler>> = listOf(
            ImmutableTryBlock(
                0,
                4,
                listOf(
                    ImmutableExceptionHandler("Ljava/util/concurrent/CancellationException;", 0x0d),
                    ImmutableExceptionHandler(null, 0x07),
                ),
            ),
        )
        return ImmutableMethodImplementation(7, manualCallerInstructions(), tryBlocks, emptyList())
    }

    /**
     * `Lq33/c;->run()V`（registers 6 / ins 1）の命令列。v5 が p0、命令 1 で chatId に潰されます。
     * v3 と v4 は命令 4 の const-wide/16 が pair として使います。
     *
     * <p>命令境界は addr 0000 / 0002 / 0004 / 0007 / 0008 / 000a / 000c / 000e / 000f / 0010 / 0013 です。
     * 既定の [readPointJumpOffset] は addr 000c の if-nez から addr 000f（const/4 v3, 1）へ飛び、
     * read point が 0 でないときだけ既読送信へ進みます。</p>
     */
    private fun supplierWorkerInstructions(
        readPointJumpOffset: Int = 3,
        trailing: List<Instruction> = emptyList(),
    ): List<Instruction> = listOf<Instruction>(
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 5, field("Lq33/c;", "a", "Lq33/e;")),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 5, 5, field("Lq33/c;", "b", "Ljava/lang/String;")),
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            5,
            0,
            0,
            0,
            ImmutableMethodReference("Lq33/e;", "a", listOf("Ljava/lang/String;"), "J"),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 1),
        ImmutableInstruction21s(Opcode.CONST_WIDE_16, 3, 0),
        ImmutableInstruction23x(Opcode.CMP_LONG, 3, 1, 3),
        ImmutableInstruction21t(Opcode.IF_NEZ, 3, readPointJumpOffset),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
        ImmutableInstruction11n(Opcode.CONST_4, 3, 1),
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            5,
            0,
            1,
            2,
            5,
            3,
            ImmutableMethodReference("Lq33/e;", "d", listOf("J", "Ljava/lang/String;", "Z"), "V"),
        ),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ) + trailing

    private fun field(definingClass: String, name: String, type: String) =
        ImmutableFieldReference(definingClass, name, type)

    private companion object {
        const val GENERIC_HANDLER_INDEX = 4
        const val CANCELLATION_HANDLER_INDEX = 8
        val MANUAL_CALLER_HANDLER_ADDRESSES = setOf(0x07, 0x0d)
    }

    /**
     * prefix の書き込みと読み出しの間に wide 命令が挟まると、scratch が pair の上位半分として
     * 潰されます。区間を固定値で書いていたころ、この検証は恒真で何も守っていませんでした。
     */
    @Test
    fun `a prefix that lets a wide instruction clobber the scratch register is rejected`() {
        val prefix = prefixInstructionsForTest()

        assertTrue(prefixKeepsScratchRegister(prefix))

        val clobbered = listOf(prefix[0], ImmutableInstruction21s(Opcode.CONST_WIDE_16, 3, 0), prefix[1])
        assertFalse(prefixKeepsScratchRegister(clobbered))
    }

    @Test
    fun `a prefix that never reads the scratch register is rejected`() {
        val prefix = prefixInstructionsForTest()

        assertFalse(prefixKeepsScratchRegister(listOf(prefix[0])))
        assertFalse(prefixKeepsScratchRegister(listOf(prefix[1])))
    }

    /** 実装が組み立てるのと同じ並び。scratch へ chatId を読み、直後に prepare へ渡します。 */
    private fun prefixInstructionsForTest(): List<Instruction> = listOf(
        ImmutableInstruction22c(
            Opcode.IGET_OBJECT,
            4,
            5,
            field("Lq33/c;", "b", "Ljava/lang/String;"),
        ),
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            2,
            5, 4, 0, 0, 0,
            ImmutableMethodReference(
                "Ldev/utaa/linimal/extension/features/readreceipts/ReadReceiptHooks;",
                "prepareSupplier",
                listOf("Ljava/lang/Object;", "Ljava/lang/String;"),
                "V",
            ),
        ),
    )
}

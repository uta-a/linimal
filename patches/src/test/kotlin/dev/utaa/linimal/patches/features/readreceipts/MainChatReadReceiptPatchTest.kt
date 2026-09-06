package dev.utaa.linimal.patches.features.readreceipts

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 注入位置の回帰テスト。dexlib2 の `addInstructions(index, ...)` は新しい MethodLocation を挿入し、
 * 既存 location は Label を保持したまま後ろへずれるため、既存の分岐先や例外 handler の先頭へ
 * 注入するとその経路だけが注入コードを飛び越します。
 *
 * <p>命令列は LINE 26.14.0 の実 DEX を写しています。難読化名は当時のもので、条件そのものには
 * 使っていません（patch 側は名前ではなく形と参照の順序で識別します）。</p>
 */
class MainChatReadReceiptPatchTest {
    // --- outbound gate (Lna3/e;->d(JLjava/lang/String;Z)V) ---

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

    // --- supplier worker (Lna3/c;->run()V) ---

    @Test
    fun `supplier preparation prefix and early cleanup are reached by every path`() {
        // prefix は run() の先頭、早期 return の cleanup は read point == 0 の goto の直前です。
        // どちらも既存の分岐先ではないため、その経路は必ず注入コードを通ります。
        val instructions = supplierWorkerInstructions()

        assertFalse(isDivertedInjectionIndex(instructions, SUPPLIER_WORKER_PREFIX_INDEX))
        assertFalse(isDivertedInjectionIndex(instructions, SUPPLIER_WORKER_EARLY_CLEANUP_INDEX))
    }

    @Test
    fun `supplier preparation normal cleanup sits on a branch target on purpose`() {
        // 既読送信後の return-void は早期 return からの goto 先でもあります。この位置へ注入すると
        // goto はずれた return-void 側へ飛びますが、その経路は手前の cleanup を通るため問題ありません。
        val instructions = supplierWorkerInstructions()

        assertTrue(isDivertedInjectionIndex(instructions, SUPPLIER_WORKER_NORMAL_CLEANUP_INDEX))
        assertEquals(
            SUPPLIER_WORKER_EARLY_CLEANUP_INDEX + 3,
            SUPPLIER_WORKER_NORMAL_CLEANUP_INDEX,
        )
    }

    @Test
    fun `supplier preparation is rejected when the prefix site is a branch target`() {
        // 末尾に addr 0000 へ戻る goto を足すと、prefix の注入位置が分岐先と一致し、その経路だけが
        // prepareSupplier を呼ばずに run() 本体へ入ります。
        // payload (addr 001e, 6 code units) の後ろ addr 0024 から addr 0000 へ戻します。
        val instructions = supplierWorkerInstructions(
            trailing = listOf(ImmutableInstruction10t(Opcode.GOTO, -0x24)),
        )

        assertTrue(isDivertedInjectionIndex(instructions, SUPPLIER_WORKER_PREFIX_INDEX))
    }

    @Test
    fun `supplier preparation is rejected when the early cleanup site is an exception handler head`() {
        // 早期 return の goto (addr 0018) が handler 先頭だと、例外経路が cleanup を飛び越します。
        val instructions = supplierWorkerInstructions()

        assertTrue(
            isDivertedInjectionIndex(instructions, SUPPLIER_WORKER_EARLY_CLEANUP_INDEX, setOf(0x18)),
        )
    }

    /**
     * 注入する prefix の命令数。owner の判定 3 命令、chatId の読み出しと prepare の 3 命令、
     * 合流点の nop 1 命令です。cleanup の注入位置をずらす計算がこの値に依存します。
     */
    @Test
    fun `the supplier preparation prefix length matches the injected instructions`() {
        assertEquals(7, SUPPLIER_PREPARATION_PREFIX_LENGTH)
    }

    // --- manual caller (Ll41/a;->a(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;) ---

    @Test
    fun `manual caller injection sites are reached by every path`() {
        // begin は factory 呼び出しの直前、cleanup はその move-result-object の直後です。
        // 26.14.0 の呼び出し元は state machine ではないため、どちらも分岐先になりません。
        val instructions = manualCallerInstructions()

        assertFalse(isDivertedInjectionIndex(instructions, MANUAL_CALLER_FACTORY_INDEX))
        assertFalse(isDivertedInjectionIndex(instructions, MANUAL_CALLER_FACTORY_INDEX + 2))
    }

    @Test
    fun `manual caller is rejected when the begin site is a branch target`() {
        // factory 呼び出し (addr 0002) へ戻る goto を足すと、その経路だけが begin を飛び越して
        // supplier を作り、手動既読の印が付かないまま送信が抑制されます。
        val instructions = manualCallerInstructions(
            trailing = listOf(ImmutableInstruction10t(Opcode.GOTO, -0x12)),
        )

        assertTrue(isDivertedInjectionIndex(instructions, MANUAL_CALLER_FACTORY_INDEX))
    }

    /**
     * `Lna3/e;->d` の先頭。index 0 の if-eqz が local update と chat-list Runnable を飛び越し、
     * index 5 (addr 000c) の queue 取得で合流します。
     */
    private fun outboundGateInstructions(
        skipOffset: Int = 12,
        trailing: List<Instruction> = emptyList(),
    ): List<Instruction> = listOf<Instruction>(
        // addr 0000
        ImmutableInstruction21t(Opcode.IF_EQZ, 9, skipOffset),
        // addr 0002
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, field("Lna3/e;", "c", "Ls83/j;")),
        // addr 0004
        ImmutableInstruction35c(
            Opcode.INVOKE_INTERFACE,
            2,
            9,
            8,
            0,
            0,
            0,
            ImmutableMethodReference("Ls83/j;", "e0", listOf("Ljava/lang/String;"), "V"),
        ),
        // addr 0007
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, field("Lna3/e;", "h", "Lna3/b;")),
        // addr 0009
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            1,
            9,
            0,
            0,
            0,
            0,
            ImmutableMethodReference("Lna3/b;", "run", emptyList(), "V"),
        ),
        // addr 000c: 合流点
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, field("Lna3/e;", "b", "Lna3/f;")),
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
            ImmutableMethodReference("Lna3/f;", "a", emptyList(), "V"),
        ),
        // addr 0012
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 9, field("Lna3/f;", "b", "Ljava/util/HashMap;")),
    ) + trailing

    /**
     * `Ll41/a;->a`。26.14.0 では suspend 関数が `factory(chatId)` を await するだけの tail-call へ
     * 最適化され、coroutine の state machine も例外 handler も生成されません。
     */
    private fun manualCallerInstructions(
        trailing: List<Instruction> = emptyList(),
    ): List<Instruction> = listOf<Instruction>(
        // addr 0000
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 0, field("Ll41/a;", "a", "Lna3/e;")),
        // addr 0002: factory 呼び出し（begin の注入位置）
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            ImmutableMethodReference("Lna3/e;", "e", listOf("Ljava/lang/String;"), "Lyw7/w;"),
        ),
        // addr 0005
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        // addr 0006: cleanup の注入位置
        ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference("Lqi8/d;")),
        // addr 0008
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            2,
            0,
            2,
            0,
            0,
            0,
            ImmutableMethodReference("Lnm8/g;", "a", listOf("Lqw7/b;", "Lqi8/d;"), "Ljava/lang/Object;"),
        ),
        // addr 000b
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        // addr 000c
        ImmutableInstruction21c(
            Opcode.SGET_OBJECT,
            1,
            ImmutableFieldReference("Lpi8/a;", "COROUTINE_SUSPENDED", "Lpi8/a;"),
        ),
        // addr 000e
        ImmutableInstruction22t(Opcode.IF_NE, 0, 1, 3),
        // addr 0010
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        // addr 0011
        ImmutableInstruction21c(
            Opcode.SGET_OBJECT,
            0,
            ImmutableFieldReference("Lkotlin/Unit;", "INSTANCE", "Lkotlin/Unit;"),
        ),
        // addr 0013
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    ) + trailing

    /**
     * `Lna3/c;->run()V`。26.14.0 では R8 が無関係な機能の lambda と 1 クラスへ畳み込んだため、
     * 先頭で int の判別子を読んで packed-switch で分岐し、既読の経路は case の側にあります。
     *
     * <p>命令境界は addr 0000 / 0002 / 0004 / 0006 / 0009 / 000a / 000c / 000e / 0011 / 0012 /
     * 0014 / 0016 / 0018 / 0019 / 001a / 001d / 001e です。case key 0 は switch の addr 0006 から
     * +0004 の addr 000a、つまり index 5 の check-cast へ飛びます。</p>
     */
    private fun supplierWorkerInstructions(
        trailing: List<Instruction> = emptyList(),
    ): List<Instruction> = listOf<Instruction>(
        // addr 0000: 判別子
        ImmutableInstruction22c(Opcode.IGET, 0, 6, field("Lna3/c;", "a", "I")),
        // addr 0002: chatId
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, field("Lna3/c;", "c", "Ljava/io/Serializable;")),
        // addr 0004: owner
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 6, 6, field("Lna3/c;", "b", "Ljava/lang/Object;")),
        // addr 0006
        ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 0x18),
        // addr 0009: 畳み込まれた別機能の経路（既定）
        ImmutableInstruction10x(Opcode.RETURN_VOID),
        // addr 000a: 既読の case
        ImmutableInstruction21c(Opcode.CHECK_CAST, 6, ImmutableTypeReference("Lna3/e;")),
        // addr 000c
        ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference("Ljava/lang/String;")),
        // addr 000e
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            6,
            1,
            0,
            0,
            0,
            ImmutableMethodReference("Lna3/e;", "a", listOf("Ljava/lang/String;"), "J"),
        ),
        // addr 0011
        ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 2),
        // addr 0012
        ImmutableInstruction21s(Opcode.CONST_WIDE_16, 4, 0),
        // addr 0014
        ImmutableInstruction23x(Opcode.CMP_LONG, 0, 2, 4),
        // addr 0016
        ImmutableInstruction21t(Opcode.IF_NEZ, 0, 3),
        // addr 0018: 早期 return の cleanup 注入位置
        ImmutableInstruction10t(Opcode.GOTO, 5),
        // addr 0019
        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
        // addr 001a
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            5,
            6,
            2,
            3,
            1,
            0,
            ImmutableMethodReference("Lna3/e;", "d", listOf("J", "Ljava/lang/String;", "Z"), "V"),
        ),
        // addr 001d: 正常終了の cleanup 注入位置
        ImmutableInstruction10x(Opcode.RETURN_VOID),
        // addr 001e
        ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 0x04))),
    ) + trailing

    private fun field(definingClass: String, name: String, type: String) =
        ImmutableFieldReference(definingClass, name, type)

    private companion object {
        const val SUPPLIER_WORKER_PREFIX_INDEX = 0
        const val SUPPLIER_WORKER_EARLY_CLEANUP_INDEX = 12
        const val SUPPLIER_WORKER_NORMAL_CLEANUP_INDEX = 15
        const val MANUAL_CALLER_FACTORY_INDEX = 1
    }
}

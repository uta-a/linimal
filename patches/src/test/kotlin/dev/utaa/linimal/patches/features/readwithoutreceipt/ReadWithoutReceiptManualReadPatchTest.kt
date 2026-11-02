package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadWithoutReceiptManualReadPatchTest {
    @Test
    fun `bridge preserves wide point and passes original all flag without touching executor registers`() {
        val outbound = method("execute", listOf("J", STRING, "Z"), "V")
        val getter = method("latest", listOf(STRING), "J")
        val bridge = manualReadBridge(outbound, getter)
        val dex = checkNotNull(ImmutableMethodImplementation.of(bridge.implementation))
        val instructions = dex.instructions.toList()
        assertEquals("J", bridge.returnType)
        assertEquals(listOf("J", STRING, "Z"), bridge.parameterTypes.map(CharSequence::toString))
        assertTrue(AccessFlags.PUBLIC.isSet(bridge.accessFlags))
        assertTrue(AccessFlags.FINAL.isSet(bridge.accessFlags))
        assertFalse(AccessFlags.STATIC.isSet(bridge.accessFlags))
        assertEquals(8, dex.registerCount)
        val invoke = instructions.single { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE } as RegisterRangeInstruction
        assertEquals(3, invoke.startRegister)
        assertEquals(5, invoke.registerCount)
        assertEquals(outbound, (invoke as ReferenceInstruction).reference)
        // 正常終了後だけ、渡した point の両 register を戻します。
        assertEquals(1, instructions.count { it.opcode == Opcode.RETURN_WIDE && (it as OneRegisterInstruction).registerA == 4 })
        assertEquals(8, checkNotNull(outbound.implementation).registerCount)
    }

    @Test
    fun `selected read clears stale failure before sending and verifies the result but all does not`() {
        val outbound = method("execute", listOf("J", STRING, "Z"), "V")
        val instructions = checkNotNull(manualReadBridge(outbound, method("latest", listOf(STRING), "J")).implementation)
            .instructions.toList()
        val send = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        val clear = instructions.indexOfFirst { calls(it, CLEAR_STALE_FAILED_READ) }
        val verify = instructions.indexOfFirst { calls(it, WAS_READ_SENT) }
        assertTrue(clear in 0 until send)
        assertEquals(send + 2, verify)
        // clear と確認は p4 (all, v7) が 0 のときだけ実行し、全体既読は従来どおり戻ります。
        assertEquals(Opcode.IF_NEZ, instructions[clear - 1].opcode)
        assertEquals(7, (instructions[clear - 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, instructions[send + 1].opcode)
        assertEquals(7, (instructions[send + 1] as OneRegisterInstruction).registerA)
        // 確認できなければ成功の位置を返しません。
        assertEquals(Opcode.MOVE_RESULT, instructions[verify + 1].opcode)
        assertEquals(Opcode.IF_EQZ, instructions[verify + 2].opcode)
        assertEquals(Opcode.RETURN_WIDE, instructions[verify + 3].opcode)
        // getter は all の分岐の先にあり、部分既読は押した位置を置き換えません。
        val selectedSkip = instructions[1] as OneRegisterInstruction
        assertEquals(Opcode.IF_EQZ, instructions[1].opcode)
        assertEquals(7, selectedSkip.registerA)
        assertTrue(instructions.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL } >= 2)
    }

    @Test
    fun `queue references are found with or without the injected gate`() {
        val body = queueFixture()
        val refs = checkNotNull(manualReadQueueReferences(implementationOf(body)))
        assertEquals("b", refs.map.name)
        assertEquals("Lf\$a;", refs.entryType)
        assertEquals("J", refs.readPoint.returnType)
        assertEquals("Lf\$s;", refs.state.returnType)
        assertEquals("FAILED", refs.failed.name)
        assertEquals("SUCCEEDED", refs.succeeded.name)
        assertEquals("a", refs.load.name)
        assertEquals("prefs", refs.preferences.name)
        // gate は HashMap.remove と Editor.remove を先に置くため、同じ相対位置の参照を読みます。
        val gated = gatePrefix() + body
        assertEquals(refs, manualReadQueueReferences(implementationOf(gated)))
        assertEquals(null, manualReadQueueReferences(implementationOf(body.filterNot {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name == "SUCCEEDED"
        })))
    }

    @Test
    fun `generated queue methods release the monitor on every path`() {
        val refs = checkNotNull(manualReadQueueReferences(implementationOf(queueFixture())))
        for (method in listOf(clearStaleFailedRead("Lexecutor;", refs), wasReadSent("Lexecutor;", refs))) {
            val dex = checkNotNull(ImmutableMethodImplementation.of(method.implementation))
            val instructions = dex.instructions.toList()
            assertEquals(1, instructions.count { it.opcode == Opcode.MONITOR_ENTER })
            assertEquals(2, instructions.count { it.opcode == Opcode.MONITOR_EXIT })
            assertEquals(1, dex.tryBlocks.size)
            assertEquals(null, dex.tryBlocks.single().exceptionHandlers.single().exceptionType)
            assertEquals(listOf(Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW),
                instructions.takeLast(3).map { it.opcode })
            // 非 range invoke に渡す register はすべて 4bit に収まります。
            assertTrue(dex.registerCount <= 16)
        }
    }

    @Test
    fun `clear keeps older failures and removes prefs only after the point comparison`() {
        val refs = checkNotNull(manualReadQueueReferences(implementationOf(queueFixture())))
        val instructions = checkNotNull(clearStaleFailedRead("Lexecutor;", refs).implementation).instructions.toList()
        val compare = instructions.indexOfFirst { it.opcode == Opcode.CMP_LONG }
        assertEquals(Opcode.IF_LTZ, instructions[compare + 1].opcode)
        val failed = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name == "FAILED"
        }
        assertTrue(failed in 0 until compare)
        val remove = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "remove" &&
                (it.reference as MethodReference).definingClass == "Ljava/util/HashMap;"
        }
        assertTrue(remove > compare)
    }

    @Test
    fun `verification requires the same point and succeeded state`() {
        val refs = checkNotNull(manualReadQueueReferences(implementationOf(queueFixture())))
        val instructions = checkNotNull(wasReadSent("Lexecutor;", refs).implementation).instructions.toList()
        val compare = instructions.indexOfFirst { it.opcode == Opcode.CMP_LONG }
        assertEquals(Opcode.IF_NEZ, instructions[compare + 1].opcode)
        assertTrue(instructions.any {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name == "SUCCEEDED"
        })
        assertFalse(instructions.any {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name == "FAILED"
        })
    }

    @Test
    fun `own mid must be the argument passed to the official query`() {
        val account = ImmutableFieldReference("Lexecutor;", "g", "Laccount;")
        val profile = ImmutableMethodReference("Laccount;", "getProfile", emptyList(), "Lprofile;")
        val mid = ImmutableFieldReference("Lprofile;", "b", STRING)
        val query = ImmutableMethodReference("Lrepo;", "c", listOf("J", STRING, STRING), "Ljava/util/List;")
        fun getter(argument: Int) = implementationOf(listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 4, account),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, profile),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 1, mid),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 5, 4, 2, 3, argument, 5, query),
        ), parameters = listOf(STRING))
        assertEquals(ManualReadOwnMidReferences(account, profile, mid), manualReadOwnMidReferences(getter(1)))
        assertEquals(null, manualReadOwnMidReferences(getter(0)))
    }

    @Test
    fun `bus references require the official service lookup and chat list post`() {
        val serviceKey = ImmutableFieldReference("Lbus;", "a", "Lbus\$a;")
        val service = ImmutableMethodReference("Lsc0/f;", "a", listOf("Landroid/content/Context;", "Lsc0/a;"), "Ljava/lang/Object;")
        val chatList = ImmutableFieldReference("Lxe8/b;", "CHAT_LIST", "Lxe8/b;")
        val post = ImmutableMethodReference("Lbus;", "b", listOf("Ljava/lang/Object;"), "V")
        val valid = listOf(
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, serviceKey),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0, service),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference("Lbus;")),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, chatList),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 1, 0, 0, 0, 0, post),
        )
        val refs = checkNotNull(manualReadBusReferences(implementationOf(valid)))
        assertEquals("Lbus;", refs.busType)
        assertEquals(serviceKey, refs.serviceKey)
        assertEquals(null, manualReadBusReferences(implementationOf(valid.dropLast(1))))
        // 生成する通知は同じ bus へ CHAT_LIST と集計イベントを 1 回ずつ投稿します。
        val notify = checkNotNull(notifyChatListUpdated("Lexecutor;",
            ImmutableFieldReference("Lexecutor;", "a", "Landroid/content/Context;"), refs,
            ImmutableFieldReference("Lxe8/a;", "a", "Lxe8/a;")).implementation).instructions.toList()
        assertEquals(2, notify.count { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "b" })
    }

    private fun calls(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction, name: String) =
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.name == name

    private fun implementationOf(
        instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
        parameters: List<String> = listOf("J", STRING, "Z"),
    ) = ImmutableMethod("Lexecutor;", "fixture", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(10, instructions, emptyList(), emptyList()))

    /** 公式 d の FAILED queue 周辺だけを実 APK の順序で再現します。 */
    private fun queueFixture(): List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> {
        val entry = "Lf\$a;"
        val state = "Lf\$s;"
        return listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 9, 5, ImmutableFieldReference("Lexecutor;", "b", "Lf;")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 9, 0, 0, 0, 0,
                ImmutableMethodReference("Lf;", "a", emptyList(), "V")),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 9, ImmutableFieldReference("Lf;", "b", "Ljava/util/HashMap;")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 8, 0, 0, 0,
                ImmutableMethodReference("Ljava/util/HashMap;", "get", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(entry)),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(entry, "a", listOf(entry), "J")),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(entry, "b", listOf(entry), state)),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 7, ImmutableFieldReference(state, "FAILED", state)),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 7, ImmutableFieldReference(state, "SUCCEEDED", state)),
        ) + editorRemove(5, 9)
    }

    private fun editorRemove(target: Int, queue: Int) = listOf(
        ImmutableInstruction22c(Opcode.IGET_OBJECT, target, queue, ImmutableFieldReference("Lf;", "store", "Lstore;")),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, target, target,
            ImmutableFieldReference("Lstore;", "prefs", "Landroid/content/SharedPreferences;")),
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, target, 0, 0, 0, 0, ImmutableMethodReference(
            "Landroid/content/SharedPreferences;", "edit", emptyList(), "Landroid/content/SharedPreferences\$Editor;")),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, target),
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, target, 8, 0, 0, 0, ImmutableMethodReference(
            "Landroid/content/SharedPreferences\$Editor;", "remove", listOf(STRING),
            "Landroid/content/SharedPreferences\$Editor;")),
    )

    private fun gatePrefix() = listOf<com.android.tools.smali.dexlib2.iface.instruction.Instruction>(
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 8, 0, 0, 0, ImmutableMethodReference(
            "Ljava/util/HashMap;", "remove", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;")),
    ) + editorRemove(0, 9)

    @Test
    fun `all reads getter once and keeps observed point when getter is zero or older`() {
        val getter = method("latest", listOf(STRING), "J")
        val instructions = checkNotNull(manualReadBridge(
            method("execute", listOf("J", STRING, "Z"), "V"), getter).implementation).instructions.toList()
        assertEquals(1, instructions.count { (it as? ReferenceInstruction)?.reference == getter })
        val compare = instructions.first { it.opcode == Opcode.CMP_LONG } as ThreeRegisterInstruction
        assertEquals(2, compare.registerA)
        assertEquals(0, compare.registerB)
        assertEquals(4, compare.registerC)
        val move = instructions.single { it.opcode == Opcode.MOVE_WIDE } as TwoRegisterInstruction
        assertEquals(4, move.registerA)
        assertEquals(0, move.registerB)
        // null chat、selected、非正の point の分岐は既存処理の呼び出しより前です。
        assertEquals(Opcode.IF_EQZ, instructions[0].opcode)
        assertEquals(6, (instructions[0] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, instructions[1].opcode)
        assertEquals(7, (instructions[1] as OneRegisterInstruction).registerA)
        assertEquals(2, instructions.count { it.opcode == Opcode.IF_LEZ })
    }

    @Test
    fun `exception returns zero and does not expose a successful point`() {
        val dex = checkNotNull(ImmutableMethodImplementation.of(manualReadBridge(
            method("execute", listOf("J", STRING, "Z"), "V"),
            method("latest", listOf(STRING), "J")).implementation))
        assertEquals(1, dex.tryBlocks.size)
        assertEquals(null, dex.tryBlocks.single().exceptionHandlers.single().exceptionType)
        assertEquals(listOf(Opcode.MOVE_EXCEPTION, Opcode.CONST_WIDE_16, Opcode.RETURN_WIDE),
            dex.instructions.toList().takeLast(3).map { it.opcode })
    }

    @Test
    fun `getter contract uses stable calls rather than executor or helper names`() {
        // fingerprint 用の reference fixture。実行用コードではなく、owner/name の難読化耐性を調べます。
        val calls = listOf(
            ImmutableMethodReference("Ljava/lang/Long;", "parseLong", listOf(STRING), "J"),
            ImmutableMethodReference("Lrenamed/query;", "anything", listOf("J", STRING, STRING), "Ljava/util/List;"),
            ImmutableMethodReference("Ljava/util/List;", "isEmpty", emptyList(), "Z"),
            ImmutableMethodReference("Ljava/util/List;", "get", listOf("I"), "Ljava/lang/Object;"),
            ImmutableMethodReference("Lrenamed/row;", "position", emptyList(), "J"),
        )
        val instructions = calls.map {
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, it)
        } + listOf(ImmutableInstruction11x(Opcode.RETURN_WIDE, 0), ImmutableInstruction11x(Opcode.RETURN_WIDE, 0))
        val getter = ImmutableMethod("Lrenamed/executor;", "anything",
            listOf(ImmutableMethodParameter(STRING, null, null)), "J",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(8, instructions, emptyList(), emptyList()))
        assertTrue(isManualReadPointGetter(getter))
        val incomplete = ImmutableMethod(getter.definingClass, getter.name, getter.parameters, "J",
            getter.accessFlags, null, null,
            ImmutableMethodImplementation(8, instructions.drop(1), emptyList(), emptyList()))
        assertFalse(isManualReadPointGetter(incomplete))
    }

    @Test
    fun `unknown getter signatures and static methods are rejected`() {
        assertFalse(isManualReadPointGetter(method("latest", listOf(STRING), "J")))
        assertFalse(isManualReadPointGetter(method("latest", emptyList(), "J")))
        assertFalse(isManualReadPointGetter(method("latest", listOf(STRING), "J", AccessFlags.STATIC.value)))
    }

    private fun method(name: String, parameters: List<String>, result: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) = ImmutableMethod(
        "Lexecutor;", name, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
        flags, null, null, ImmutableMethodImplementation(8,
            listOf(if (result == "V") com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(
                Opcode.RETURN_VOID) else ImmutableInstruction11x(Opcode.RETURN_WIDE, 0)), emptyList(), emptyList()))

    private companion object {
        const val STRING = "Ljava/lang/String;"
    }
}

package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadWithoutReceiptSessionPatchTest {
    @Test
    fun `range and 35c super preserve both activity parameters`() {
        assertEquals(2, readWithoutReceiptSessionThisRegister(activity(), SUPER))
        assertEquals(2, readWithoutReceiptSessionThisRegister(activity(first = super35c()), SUPER))
        assertEquals(0, readWithoutReceiptSessionThisRegister(activity(registers = 2), SUPER))
    }

    @Test
    fun `high parameter registers remain intact in the injected dex`() {
        val method = activity(registers = 300)
        val register = checkNotNull(readWithoutReceiptSessionThisRegister(method, SUPER))
        val implementation = MutableMethodImplementation(checkNotNull(method.implementation))
        implementation.addInstruction(1, readWithoutReceiptSessionHook(register))
        // 注入後の DEX 表現で p0/p1、署名、元の先頭と SDK 読み出しの順序を確認します。
        val dex = checkNotNull(ImmutableMethodImplementation.of(implementation))
        val instructions = dex.instructions.toList()
        val hook = instructions[1] as RegisterRangeInstruction
        val reference = (hook as ReferenceInstruction).reference as MethodReference
        assertEquals(Opcode.INVOKE_SUPER_RANGE, instructions[0].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals(298, hook.startRegister)
        assertEquals(2, hook.registerCount)
        assertEquals("onChatCreated", reference.name)
        assertEquals("Ldev/utaa/linimal/extension/features/readwithoutreceipt/ReadWithoutReceiptHooks;", reference.definingClass)
        assertEquals(listOf("Landroid/app/Activity;", BUNDLE), reference.parameterTypes)
        assertEquals("V", reference.returnType)
        assertEquals(Opcode.SGET, instructions[2].opcode)
        assertEquals(300, dex.registerCount)
    }

    @Test
    fun `static abstract and wrong lifecycle signatures are rejected`() {
        assertNull(readWithoutReceiptSessionThisRegister(activity(flags = AccessFlags.STATIC.value), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(implementation = null), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(registers = 1, first = superRange(start = 0)), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(name = "onResume"), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(parameter = "Ljava/lang/Object;"), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(returnType = "I"), SUPER))
    }

    @Test
    fun `only direct super onCreate with original p0 p1 is allowed`() {
        val invalid = listOf(
            ImmutableInstruction10x(Opcode.NOP),
            superRange(start = 1),
            superRange(count = 1),
            superRange(opcode = Opcode.INVOKE_VIRTUAL_RANGE),
            superRange(reference = ImmutableMethodReference(SUPER, "onStart", listOf(BUNDLE), "V")),
            superRange(reference = ImmutableMethodReference(SUPER, "onCreate", emptyList(), "V")),
            super35c(receiver = 1),
            super35c(bundle = 1),
        )
        invalid.forEach { assertNull(readWithoutReceiptSessionThisRegister(activity(first = it), SUPER)) }
        assertNull(readWithoutReceiptSessionThisRegister(activity(), "Lanother/Base;"))
    }

    @Test
    fun `unknown SDK entrance is not injected`() {
        assertNull(readWithoutReceiptSessionThisRegister(activity(second = ImmutableInstruction10x(Opcode.NOP)), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(second = sdkRead(name = "PREVIEW_SDK_INT")), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(second = sdkRead(owner = "Lother/Version;")), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(second = sdkRead(type = "Ljava/lang/Integer;")), SUPER))
        assertNull(readWithoutReceiptSessionThisRegister(activity(second = sdkRead(opcode = Opcode.SGET_OBJECT)), SUPER))
    }

    @Test
    fun `branch and switch destinations at index one are rejected`() {
        // super は 3 units、SGET は 2 units。address 5 から address 3 へ戻る分岐です。
        assertNull(readWithoutReceiptSessionThisRegister(activity(tail = listOf(ImmutableInstruction10t(Opcode.GOTO, -2))), SUPER))
        // switch は address 5、payload は address 8、case は address 3 です。
        assertNull(readWithoutReceiptSessionThisRegister(activity(tail = listOf(
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, 0, 3),
            ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, -2))),
        )), SUPER))
    }

    @Test
    fun `exception handler entrance at index one is rejected`() {
        val implementation = ImmutableMethodImplementation(
            4,
            listOf(superRange(), sdkRead(), ImmutableInstruction10x(Opcode.RETURN_VOID)),
            listOf(ImmutableTryBlock(0, 3, listOf(ImmutableExceptionHandler(null, 3)))),
            emptyList(),
        )
        assertNull(readWithoutReceiptSessionThisRegister(activity(implementation = implementation), SUPER))
    }

    @Test
    fun `public simple chatId getter satisfies the reflection contract`() {
        assertTrue(readWithoutReceiptSessionGetterSafe(getter()))
        // iget が this を上書きしても、同じ register を返すだけなら安全です。
        assertTrue(readWithoutReceiptSessionGetterSafe(getter(destination = 1, returned = 1)))
    }

    @Test
    fun `getter rejects wrong field type receiver return or access`() {
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(fieldName = "other")))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(fieldType = "Ljava/lang/Object;")))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(fieldOwner = SUPER)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(receiver = 0)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(returned = 1)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(flags = AccessFlags.STATIC.value or AccessFlags.PUBLIC.value)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(flags = AccessFlags.PRIVATE.value)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(opcode = Opcode.IGET)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(returnOpcode = Opcode.RETURN)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(extra = listOf(ImmutableInstruction10x(Opcode.NOP)))))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(implementation = null)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(name = "getOtherId")))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(owner = SUPER)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(returnType = "Ljava/lang/Object;")))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(parameter = BUNDLE)))
        assertFalse(readWithoutReceiptSessionGetterSafe(getter(tryBlocks = listOf(ImmutableTryBlock(0, 2, listOf(ImmutableExceptionHandler(null, 2)))))))
    }

    private fun activity(
        registers: Int = 4,
        first: Instruction = superRange(start = registers - 2),
        second: Instruction = sdkRead(),
        tail: List<Instruction> = listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
        flags: Int = 0,
        name: String = "onCreate",
        parameter: String = BUNDLE,
        returnType: String = "V",
        implementation: ImmutableMethodImplementation? = ImmutableMethodImplementation(
            registers, listOf(first, second) + tail, emptyList(), emptyList(),
        ),
    ) = ImmutableMethod(
        "Ljp/naver/line/android/activity/chathistory/ChatHistoryActivity;", name,
        listOf(ImmutableMethodParameter(parameter, null, null)), returnType, flags, null, null, implementation,
    )

    private fun superRange(
        start: Int = 2,
        count: Int = 2,
        opcode: Opcode = Opcode.INVOKE_SUPER_RANGE,
        reference: ImmutableMethodReference = SUPER_CREATE,
    ) = ImmutableInstruction3rc(opcode, start, count, reference)

    private fun super35c(receiver: Int = 2, bundle: Int = 3) =
        ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, receiver, bundle, 0, 0, 0, SUPER_CREATE)

    private fun sdkRead(
        name: String = "SDK_INT",
        owner: String = "Landroid/os/Build\$VERSION;",
        type: String = "I",
        opcode: Opcode = Opcode.SGET,
    ) = ImmutableInstruction21c(opcode, 0, ImmutableFieldReference(owner, name, type))

    private fun getter(
        fieldName: String = "chatId",
        fieldType: String = STRING,
        fieldOwner: String = REQUEST,
        receiver: Int = 1,
        destination: Int = 0,
        returned: Int = 0,
        flags: Int = AccessFlags.PUBLIC.value,
        opcode: Opcode = Opcode.IGET_OBJECT,
        returnOpcode: Opcode = Opcode.RETURN_OBJECT,
        extra: List<Instruction> = emptyList(),
        name: String = "getChatId",
        owner: String = REQUEST,
        returnType: String = STRING,
        parameter: String? = null,
        tryBlocks: List<ImmutableTryBlock> = emptyList(),
        implementation: ImmutableMethodImplementation? = ImmutableMethodImplementation(
            2,
            listOf(
                ImmutableInstruction22c(opcode, destination, receiver, ImmutableFieldReference(fieldOwner, fieldName, fieldType)),
                ImmutableInstruction11x(returnOpcode, returned),
            ) + extra,
            tryBlocks,
            emptyList(),
        ),
    ) = ImmutableMethod(
        owner, name, parameter?.let { listOf(ImmutableMethodParameter(it, null, null)) } ?: emptyList(),
        returnType, flags, null, null, implementation,
    )

    private companion object {
        const val BUNDLE = "Landroid/os/Bundle;"
        const val STRING = "Ljava/lang/String;"
        const val SUPER = "Lbv7/b;"
        const val REQUEST = "Lcom/linecorp/line/chat/request/ChatHistoryRequest;"
        val SUPER_CREATE = ImmutableMethodReference(SUPER, "onCreate", listOf(BUNDLE), "V")
    }
}

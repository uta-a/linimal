package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val COMPOSER = "Lh3/t;"
private const val ITEM = "Llz0/m3\$a;"
private const val FUNCTION0 = "Lvb8/a;"
private const val FUNCTION2 = "Lvb8/p;"
private const val MODIFIER = "Ly3/j;"
private const val COMPOSABLE_LAMBDA = "Lr3/j;"
private const val DONOR = "Lmz0/j;"

/**
 * メニュー本体の命令列を、実測した index・register・reference の並びどおりに fixture で再現して
 * 検証します。行 composable を 4 回呼ぶこと、Composer の引数なしメソッドが 2 種類
 * （endReplaceGroup と skipToGroupEnd）現れることまで含めて、実 APK と同じ形にしています。
 */
class ReadWithoutReceiptComposeMenuPatchTest {
    @Test
    fun `the measured menu shape resolves every reference it injects`() {
        val shape = composeMenuShape(menuMethod())

        checkNotNull(shape)
        assertEquals(COMPOSER, shape.composerType)
        assertEquals(ITEM, shape.itemType)
        assertEquals(DONOR, shape.labelDonorType)
        assertEquals(4, shape.composerRegister)
        assertEquals(12, shape.itemRegister)
        assertEquals(8, shape.dismissRegister)
        assertEquals("a", shape.rowComposable.name)
        assertEquals("b", shape.rememberLambda.name)
        assertEquals("p", shape.startReplaceGroup.name)
        assertEquals("m", shape.endReplaceGroup.name)
        assertEquals("l", shape.skipToGroupEnd.name)
        assertEquals("A", shape.shouldExecute.name)
    }

    @Test
    fun `the fingerprint filter accepts the measured shape`() {
        assertTrue(looksLikeComposeMenu(menuMethod()))
    }

    @Test
    fun `a method with a different number of rows is not the menu`() {
        assertFalse(looksLikeComposeMenu(menuMethod(rowCount = 3)))
        assertFalse(looksLikeComposeMenu(menuMethod(rowCount = 5)))
    }

    @Test
    fun `a method that never casts the item type is not the menu`() {
        assertFalse(looksLikeComposeMenu(menuMethod(includeItemCasts = false)))
    }

    @Test
    fun `a missing composer cast is rejected`() {
        assertNull(composeMenuShape(menuMethod(includeComposerCast = false)))
    }

    @Test
    fun `an instance-of on a register other than the item is rejected`() {
        assertNull(composeMenuShape(menuMethod(instanceOfRegister = 11)))
    }

    @Test
    fun `endReplaceGroup is told apart from skipToGroupEnd by where it is called`() {
        // どちらも Composer の `()V` です。行 composable の直後に呼ばれる方だけが endReplaceGroup で、
        // 取り違えると行が閉じられないまま次の行が始まります。
        val shape = checkNotNull(composeMenuShape(menuMethod()))

        assertEquals("m", shape.endReplaceGroup.name)
        assertEquals("l", shape.skipToGroupEnd.name)
    }

    @Test
    fun `a menu without a skipToGroupEnd call is rejected`() {
        assertNull(composeMenuShape(menuMethod(includeSkipToGroupEnd = false)))
    }

    @Test
    fun `the chat id is the only String field of the item type`() {
        assertEquals("a", chatIdField(itemClass())?.name)
        assertNull(chatIdField(itemClass(extraStringField = true)))
        assertNull(chatIdField(null))
    }

    @Test
    fun `the label donor yields the text call and the Unit field`() {
        val donor = checkNotNull(labelDonorShape(listOf(donorInvoke())))

        assertEquals("b", donor.text.name)
        assertEquals("INSTANCE", donor.unitField.name)
    }

    @Test
    fun `a label donor without a 25 register text call is rejected`() {
        assertNull(labelDonorShape(listOf(donorInvoke(textRegisterCount = 24))))
    }

    private fun itemClass(extraStringField: Boolean = false) = ImmutableClassDef(
        ITEM,
        0,
        "Llz0/m3;",
        null,
        null,
        null,
        null,
        buildList {
            add(ImmutableField(ITEM, "a", "Ljava/lang/String;", 0, null, null, null))
            add(ImmutableField(ITEM, "b", "Llz0/y2;", 0, null, null, null))
            if (extraStringField) {
                add(ImmutableField(ITEM, "c", "Ljava/lang/String;", 0, null, null, null))
            }
        },
        null,
        null,
    )

    private fun donorInvoke(textRegisterCount: Int = 25): Method = ImmutableMethod(
        DONOR,
        "invoke",
        listOf(
            ImmutableMethodParameter("Ljava/lang/Object;", null, null),
            ImmutableMethodParameter("Ljava/lang/Object;", null, null),
        ),
        "Ljava/lang/Object;",
        0,
        null,
        null,
        ImmutableMethodImplementation(
            28,
            listOf(
                ImmutableInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    0,
                    textRegisterCount,
                    ImmutableMethodReference("Lc3/gg;", "b", listOf("Ljava/lang/String;"), "V"),
                ),
                ImmutableInstruction21c(
                    Opcode.SGET_OBJECT,
                    0,
                    ImmutableFieldReference("Lkotlin/Unit;", "INSTANCE", "Lkotlin/Unit;"),
                ),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
            null,
            null,
        ),
    )

    private fun menuMethod(
        rowCount: Int = 4,
        includeItemCasts: Boolean = true,
        includeComposerCast: Boolean = true,
        includeSkipToGroupEnd: Boolean = true,
        instanceOfRegister: Int = 12,
    ): Method = ImmutableMethod(
        "Lmz0/f;",
        "i",
        List(3) { ImmutableMethodParameter("Ljava/lang/Object;", null, null) },
        "Ljava/lang/Object;",
        0,
        null,
        null,
        ImmutableMethodImplementation(
            14,
            menuInstructions(
                rowCount = rowCount,
                includeItemCasts = includeItemCasts,
                includeComposerCast = includeComposerCast,
                includeSkipToGroupEnd = includeSkipToGroupEnd,
                instanceOfRegister = instanceOfRegister,
            ),
            null,
            null,
        ),
    )

    /** index 2・15・17・19・20・21 だけを実測どおりに置き、残りは内容を問わない nop で埋めます。 */
    private fun menuInstructions(
        rowCount: Int,
        includeItemCasts: Boolean,
        includeComposerCast: Boolean,
        includeSkipToGroupEnd: Boolean,
        instanceOfRegister: Int,
    ): List<Instruction> = buildList {
        add(ImmutableInstruction10x(Opcode.NOP))
        add(ImmutableInstruction10x(Opcode.NOP))
        if (includeComposerCast) {
            add(ImmutableInstruction21c(Opcode.CHECK_CAST, 4, ImmutableTypeReference(COMPOSER)))
        } else {
            add(ImmutableInstruction10x(Opcode.NOP))
        }
        repeat(12) { add(ImmutableInstruction10x(Opcode.NOP)) }
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_INTERFACE,
                3,
                4, 12, 11, 0, 0,
                ImmutableMethodReference(COMPOSER, "A", listOf("I", "Z"), "Z"),
            ),
        )
        add(ImmutableInstruction11x(Opcode.MOVE_RESULT, 11))
        add(ImmutableInstruction21t(Opcode.IF_EQZ, 11, 4))
        add(ImmutableInstruction10x(Opcode.NOP))
        add(
            ImmutableInstruction22c(
                Opcode.IGET_OBJECT,
                12,
                11,
                ImmutableFieldReference("Lmz0/n;", "b", "Llz0/m3;"),
            ),
        )
        add(
            ImmutableInstruction22c(
                Opcode.INSTANCE_OF,
                13,
                instanceOfRegister,
                ImmutableTypeReference(ITEM),
            ),
        )
        add(
            ImmutableInstruction22c(
                Opcode.IGET_OBJECT,
                8,
                10,
                ImmutableFieldReference("Lmz0/f;", "b", FUNCTION0),
            ),
        )
        add(ImmutableInstruction10x(Opcode.NOP))

        repeat(rowCount) { addAll(rowInstructions(includeItemCasts)) }

        if (includeSkipToGroupEnd) {
            add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_INTERFACE,
                    1,
                    4, 0, 0, 0, 0,
                    ImmutableMethodReference(COMPOSER, "l", emptyList(), "V"),
                ),
            )
        }
    }

    /** 1 行ぶんの発行。startReplaceGroup → lambda 生成 → 行 composable → endReplaceGroup の順です。 */
    private fun rowInstructions(includeItemCast: Boolean): List<Instruction> = buildList {
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_INTERFACE,
                2,
                4, 0, 0, 0, 0,
                ImmutableMethodReference(COMPOSER, "p", listOf("I"), "V"),
            ),
        )
        if (includeItemCast) {
            add(ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(ITEM)))
        }
        add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 13, ImmutableTypeReference(DONOR)))
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                3,
                1, 13, 4, 0, 0,
                ImmutableMethodReference(
                    "Lr3/k;",
                    "b",
                    listOf("I", "Lkotlin/Function;", COMPOSER),
                    COMPOSABLE_LAMBDA,
                ),
            ),
        )
        add(
            ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                0,
                7,
                ImmutableMethodReference(
                    "Lzz1/v;",
                    "a",
                    listOf(FUNCTION0, MODIFIER, FUNCTION2, FUNCTION2, COMPOSER, "I", "I"),
                    "V",
                ),
            ),
        )
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_INTERFACE,
                1,
                4, 0, 0, 0, 0,
                ImmutableMethodReference(COMPOSER, "m", emptyList(), "V"),
            ),
        )
    }
}

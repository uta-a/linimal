package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import dev.utaa.linimal.patches.util.ComposeRuntime
import dev.utaa.linimal.patches.util.instructionAddress
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LINE 26.14.0 の実測値。難読化名は fixture の材料としてだけ使い、判定条件には使いません。 */
private const val COMPOSER = "Lh3/s;"
private const val COMPOSER_IMPL = "Lh3/d1;"
private const val SHOULD_EXECUTE = "B"
private const val SKIP_TO_GROUP_END = "m"
private const val START_REPLACE_GROUP = "q"
private const val END_REPLACE_GROUP = "n"

private const val ITEM = "Lud1/b;"
private const val CHAT_TYPE = "Lud1/a;"
private const val ON_ACTION = "Laj8/l;"
private const val FUNCTION0 = "Laj8/a;"
private const val FUNCTION2 = "Laj8/p;"
private const val MODIFIER = "Lz3/j;"
private const val COMPOSABLE_LAMBDA = "Lr3/k;"
private const val DONOR = "Lc21/d;"
private const val ROW_MODEL = "Lz71/b;"

/** `shouldExecute` の結果を見る if-eqz の index と、行の呼び出しを差し込む index。 */
private const val SHOULD_EXECUTE_BRANCH_INDEX = 6
private const val ROW_INSERTION_INDEX = SHOULD_EXECUTE_BRANCH_INDEX + 1

private val COMPOSE_RUNTIME = ComposeRuntime(
    composer = COMPOSER,
    composerImpl = COMPOSER_IMPL,
    shouldExecute = SHOULD_EXECUTE,
    skipToGroupEnd = SKIP_TO_GROUP_END,
)

private val GROUPS = ComposeMenuGroupShape(
    startReplaceGroup = ImmutableMethodReference(COMPOSER, START_REPLACE_GROUP, listOf("I"), "V"),
    endReplaceGroup = ImmutableMethodReference(COMPOSER, END_REPLACE_GROUP, emptyList(), "V"),
)

/**
 * メニュー本体の命令列を、実測した並び・register・reference どおりに fixture で再現して検証します。
 *
 * <p>26.11.0 のメニュー本体は 4 行を直接展開する `invoke(Object, Object, Object)Object` でしたが、
 * 26.14.0 では `(item, Function1, Composer, int)V` が項目のリストをループで 1 行ずつ描く形に
 * 変わりました。fixture もその形に合わせています。</p>
 */
class ReadWithoutReceiptComposeMenuPatchTest {
    @Test
    fun `the measured menu shape resolves every reference it injects`() {
        val shape = composeMenuShape(menuRowsMethod(), COMPOSE_RUNTIME, GROUPS)

        checkNotNull(shape)
        assertEquals(COMPOSER, shape.composerType)
        assertEquals(ITEM, shape.itemType)
        assertEquals(DONOR, shape.labelDonorType)
        // registers = 16 / parameters = 4 なので、第 1 引数のトーク項目は v12 です。
        assertEquals(12, shape.itemRegister)
        assertEquals(5, shape.composerRegister)
        assertEquals(ROW_INSERTION_INDEX, shape.insertionIndex)
        assertEquals("a", shape.rowComposable.name)
        assertEquals("b", shape.rememberLambda.name)
        assertEquals(START_REPLACE_GROUP, shape.startReplaceGroup.name)
        assertEquals(END_REPLACE_GROUP, shape.endReplaceGroup.name)
        assertEquals("$COMPOSER->$SHOULD_EXECUTE(IZ)Z", shape.shouldExecute)
        assertEquals("$COMPOSER->$SKIP_TO_GROUP_END()V", shape.skipToGroupEnd)
    }

    @Test
    fun `the fingerprint filter accepts the measured shape`() {
        assertTrue(looksLikeComposeMenuRows(menuRowsMethod(), COMPOSER))
    }

    @Test
    fun `a method that draws more than one row is not the menu body`() {
        // 26.14.0 の行はループの中の 1 か所からだけ描かれます。2 か所以上ある composable は別物です。
        assertFalse(looksLikeComposeMenuRows(menuRowsMethod(rowCount = 2), COMPOSER))
        assertFalse(looksLikeComposeMenuRows(menuRowsMethod(rowCount = 0), COMPOSER))
    }

    @Test
    fun `a method that never builds the menu entry list is not the menu body`() {
        // 行 composable を呼ぶだけの composable は APK 内に多数あります。項目リストを組み立てる
        // static があることまで見て、トーク一覧の長押しメニューに絞り込みます。
        assertFalse(looksLikeComposeMenuRows(menuRowsMethod(includeEntryList = false), COMPOSER))
    }

    @Test
    fun `a method without rememberComposableLambda is not the menu body`() {
        assertFalse(looksLikeComposeMenuRows(menuRowsMethod(includeRememberLambda = false), COMPOSER))
    }

    @Test
    fun `a missing shouldExecute gate is rejected`() {
        assertNull(composeMenuShape(menuRowsMethod(includeShouldExecute = false), COMPOSE_RUNTIME, GROUPS))
    }

    @Test
    fun `a menu body without a skipToGroupEnd call is rejected`() {
        assertNull(composeMenuShape(menuRowsMethod(includeSkipToGroupEnd = false), COMPOSE_RUNTIME, GROUPS))
    }

    @Test
    fun `an item register that the row invocation cannot address is rejected`() {
        // 注入する invoke-static は 35c 形式なので、引数の register は 4bit（v0〜v15）に限られます。
        assertNull(composeMenuShape(menuRowsMethod(registerCount = 20), COMPOSE_RUNTIME, GROUPS))
    }

    @Test
    fun `an item register that is overwritten before the injection point is rejected`() {
        // 第 1 引数の register が注入位置より前で潰されると、行へ別の値を渡してしまいます。
        assertNull(composeMenuShape(menuRowsMethod(clobberItemRegister = true), COMPOSE_RUNTIME, GROUPS))
    }

    @Test
    fun `an injection point that is also a branch target is rejected`() {
        // shouldExecute の if-eqz が注入位置そのものへ飛ぶ形にします。dexlib2 は注入位置へ新しい
        // location を挿入し、既存 location は Label を保持したまま後ろへずれるため、この経路だけが
        // 行を飛び越し、recomposition ごとに slot 構造がずれます。
        val instructions = menuRowsMethod(branchTargetIndex = ROW_INSERTION_INDEX)
            .implementation!!
            .instructions
            .toList()

        assertTrue(isDivertedInjectionIndex(instructions, ROW_INSERTION_INDEX))
        assertNull(composeMenuShape(menuRowsMethod(branchTargetIndex = ROW_INSERTION_INDEX), COMPOSE_RUNTIME, GROUPS))
    }

    @Test
    fun `the measured branch target leaves the injection point alone`() {
        // 実 APK と同じく、if-eqz は本体を丸ごと飛ばして末尾の skipToGroupEnd へ向かいます。
        val instructions = menuRowsMethod().implementation!!.instructions.toList()

        assertFalse(isDivertedInjectionIndex(instructions, ROW_INSERTION_INDEX))
    }

    @Test
    fun `the group calls are taken from the container that wraps the menu body`() {
        val groups = checkNotNull(composeMenuGroupShape(containerMethod(), COMPOSE_RUNTIME))

        assertEquals(START_REPLACE_GROUP, groups.startReplaceGroup.name)
        // endReplaceGroup と skipToGroupEnd はどちらも Composer の `()V` です。取り違えると
        // 行が閉じられないまま次の行が始まるため、skipToGroupEnd の名前で除いて 1 件に絞ります。
        assertEquals(END_REPLACE_GROUP, groups.endReplaceGroup.name)
    }

    @Test
    fun `a container without a startReplaceGroup call is rejected`() {
        assertNull(composeMenuGroupShape(containerMethod(includeStartReplaceGroup = false), COMPOSE_RUNTIME))
    }

    @Test
    fun `a container whose only no-arg composer call is skipToGroupEnd is rejected`() {
        assertNull(composeMenuGroupShape(containerMethod(includeEndReplaceGroup = false), COMPOSE_RUNTIME))
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
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        buildList {
            add(ImmutableField(ITEM, "a", "Ljava/lang/String;", 0, null, null, null))
            add(ImmutableField(ITEM, "b", CHAT_TYPE, 0, null, null, null))
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
                    ImmutableMethodReference("Lb3/qg;", "b", listOf("Ljava/lang/String;"), "V"),
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

    /**
     * `Lc21/f;->a(Lud1/b;, Laj8/l;, Lh3/s;, I)V` を模した命令列。registers 16 / parameters 4 なので、
     * トーク項目は v12、Composer 実装は v5 に載ります。
     */
    private fun menuRowsMethod(
        rowCount: Int = 1,
        registerCount: Int = 16,
        includeEntryList: Boolean = true,
        includeRememberLambda: Boolean = true,
        includeShouldExecute: Boolean = true,
        includeSkipToGroupEnd: Boolean = true,
        clobberItemRegister: Boolean = false,
        branchTargetIndex: Int? = null,
    ): Method = ImmutableMethod(
        "Lc21/f;",
        "a",
        listOf(
            ImmutableMethodParameter(ITEM, null, null),
            ImmutableMethodParameter(ON_ACTION, null, null),
            ImmutableMethodParameter(COMPOSER, null, null),
            ImmutableMethodParameter("I", null, null),
        ),
        "V",
        0,
        null,
        null,
        ImmutableMethodImplementation(
            registerCount,
            menuRowsInstructions(
                rowCount = rowCount,
                includeEntryList = includeEntryList,
                includeRememberLambda = includeRememberLambda,
                includeShouldExecute = includeShouldExecute,
                includeSkipToGroupEnd = includeSkipToGroupEnd,
                clobberItemRegister = clobberItemRegister,
                branchTargetIndex = branchTargetIndex,
            ),
            null,
            null,
        ),
    )

    /**
     * shouldExecute の if-eqz は、既定では実 APK と同じく本体を丸ごと飛ばして末尾の skipToGroupEnd へ
     * 向かいます。fixture は実 APK と code address が一致しないため、offset は組み上げた命令列の
     * 実際の address から求め、必ず命令境界に乗せます。
     */
    private fun menuRowsInstructions(
        rowCount: Int,
        includeEntryList: Boolean,
        includeRememberLambda: Boolean,
        includeShouldExecute: Boolean,
        includeSkipToGroupEnd: Boolean,
        clobberItemRegister: Boolean,
        branchTargetIndex: Int?,
    ): List<Instruction> {
        val body = menuRowsBody(
            rowCount = rowCount,
            includeEntryList = includeEntryList,
            includeRememberLambda = includeRememberLambda,
            includeShouldExecute = includeShouldExecute,
            includeSkipToGroupEnd = includeSkipToGroupEnd,
            clobberItemRegister = clobberItemRegister,
        )
        val targetIndex = branchTargetIndex ?: body.lastIndex
        val offset = instructionAddress(body, targetIndex) - instructionAddress(body, SHOULD_EXECUTE_BRANCH_INDEX)
        return body.mapIndexed { index, instruction ->
            if (index == SHOULD_EXECUTE_BRANCH_INDEX) {
                ImmutableInstruction21t(Opcode.IF_EQZ, 1, offset)
            } else {
                instruction
            }
        }
    }

    /** 分岐 offset を確定させる前の命令列。index 5 の if-eqz は暫定の offset を持ちます。 */
    private fun menuRowsBody(
        rowCount: Int,
        includeEntryList: Boolean,
        includeRememberLambda: Boolean,
        includeShouldExecute: Boolean,
        includeSkipToGroupEnd: Boolean,
        clobberItemRegister: Boolean,
    ): List<Instruction> = buildList {
        // startRestartGroup 相当。Composer 実装を v5 へ載せます。
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_INTERFACE,
                2,
                14, 0, 0, 0, 0,
                ImmutableMethodReference(COMPOSER, "x", listOf("I"), COMPOSER_IMPL),
            ),
        )
        add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5))
        add(ImmutableInstruction10x(Opcode.NOP))
        if (clobberItemRegister) {
            add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 12))
        } else {
            add(ImmutableInstruction10x(Opcode.NOP))
        }
        if (includeShouldExecute) {
            add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL,
                    3,
                    5, 2, 1, 0, 0,
                    ImmutableMethodReference(COMPOSER_IMPL, SHOULD_EXECUTE, listOf("I", "Z"), "Z"),
                ),
            )
        } else {
            add(ImmutableInstruction10x(Opcode.NOP))
        }
        add(ImmutableInstruction11x(Opcode.MOVE_RESULT, 1))
        add(ImmutableInstruction21t(Opcode.IF_EQZ, 1, 0))
        // ここが注入位置（index 6）です。以降が本体です。
        if (includeEntryList) {
            add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    12, 0, 0, 0, 0,
                    ImmutableMethodReference("Lc21/f;", "b", listOf(ITEM), "Ljava/util/ArrayList;"),
                ),
            )
        }
        repeat(rowCount) { addAll(rowInstructions(includeRememberLambda)) }
        if (includeSkipToGroupEnd) {
            add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL,
                    1,
                    5, 0, 0, 0, 0,
                    ImmutableMethodReference(COMPOSER_IMPL, SKIP_TO_GROUP_END, emptyList(), "V"),
                ),
            )
        }
        // endRestartGroup の並び（引数なし → move-result-object → if-eqz）。
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                1,
                5, 0, 0, 0, 0,
                ImmutableMethodReference(COMPOSER_IMPL, "Z", emptyList(), "Lh3/p3;"),
            ),
        )
        add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 14))
        add(ImmutableInstruction21t(Opcode.IF_EQZ, 14, 3))
        add(ImmutableInstruction10x(Opcode.NOP))
        add(ImmutableInstruction10x(Opcode.RETURN_VOID))
    }

    /** ループの中で 1 行ぶんを描く並び。lambda 生成 → 行 composable の順です。 */
    private fun rowInstructions(includeRememberLambda: Boolean): List<Instruction> = buildList {
        add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(DONOR)))
        add(
            ImmutableInstruction35c(
                Opcode.INVOKE_DIRECT,
                2,
                2, 1, 0, 0, 0,
                ImmutableMethodReference(DONOR, "<init>", listOf(ROW_MODEL), "V"),
            ),
        )
        if (includeRememberLambda) {
            add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    3,
                    1, 2, 5, 0, 0,
                    ImmutableMethodReference(
                        "Lr3/l;",
                        "b",
                        listOf("I", "Lkotlin/Function;", COMPOSER),
                        COMPOSABLE_LAMBDA,
                    ),
                ),
            )
        }
        add(
            ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                1,
                7,
                ImmutableMethodReference(
                    "Lk32/u;",
                    "a",
                    listOf(FUNCTION0, MODIFIER, FUNCTION2, FUNCTION2, COMPOSER, "I", "I"),
                    "V",
                ),
            ),
        )
        add(ImmutableInstruction10t(Opcode.GOTO, 2))
    }

    /**
     * `Ld21/e;->j(Object, Object, Object)Object` を模した、メニュー本体を呼び出す外側の composable。
     * ここだけが `startReplaceGroup` / `endReplaceGroup` を持ちます。
     */
    private fun containerMethod(
        includeStartReplaceGroup: Boolean = true,
        includeEndReplaceGroup: Boolean = true,
    ): Method = ImmutableMethod(
        "Ld21/e;",
        "j",
        List(3) { ImmutableMethodParameter("Ljava/lang/Object;", null, null) },
        "Ljava/lang/Object;",
        0,
        null,
        null,
        ImmutableMethodImplementation(
            8,
            buildList {
                if (includeStartReplaceGroup) {
                    add(
                        ImmutableInstruction35c(
                            Opcode.INVOKE_INTERFACE,
                            2,
                            6, 0, 0, 0, 0,
                            ImmutableMethodReference(COMPOSER, START_REPLACE_GROUP, listOf("I"), "V"),
                        ),
                    )
                }
                add(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC,
                        4,
                        1, 4, 6, 0, 0,
                        ImmutableMethodReference(
                            "Lc21/f;",
                            "a",
                            listOf(ITEM, ON_ACTION, COMPOSER, "I"),
                            "V",
                        ),
                    ),
                )
                if (includeEndReplaceGroup) {
                    add(
                        ImmutableInstruction35c(
                            Opcode.INVOKE_INTERFACE,
                            1,
                            6, 0, 0, 0, 0,
                            ImmutableMethodReference(COMPOSER, END_REPLACE_GROUP, emptyList(), "V"),
                        ),
                    )
                }
                add(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_INTERFACE,
                        1,
                        6, 0, 0, 0, 0,
                        ImmutableMethodReference(COMPOSER, SKIP_TO_GROUP_END, emptyList(), "V"),
                    ),
                )
                add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            },
            null,
            null,
        ),
    )
}

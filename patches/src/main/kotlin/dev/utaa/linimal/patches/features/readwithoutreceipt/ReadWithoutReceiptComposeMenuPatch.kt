package dev.utaa.linimal.patches.features.readwithoutreceipt

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.ComposeRuntime
import dev.utaa.linimal.patches.util.INT
import dev.utaa.linimal.patches.util.INTEGER
import dev.utaa.linimal.patches.util.OBJECT
import dev.utaa.linimal.patches.util.STRING
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.composeShouldExecuteGate
import dev.utaa.linimal.patches.util.exceptionHandlerAddresses
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex
import dev.utaa.linimal.patches.util.resolveComposeRuntime


private const val EXTENSION_PACKAGE = "Ldev/utaa/linimal/extension/features/readwithoutreceipt"
private const val ACTION_TYPE = "$EXTENSION_PACKAGE/ReadWithoutReceiptAction;"
private const val LABEL_TYPE = "$EXTENSION_PACKAGE/ReadWithoutReceiptMenuLabel;"
private const val ROW_TYPE = "$EXTENSION_PACKAGE/ReadWithoutReceiptMenuRow;"
private const val HOOKS_TYPE = "$EXTENSION_PACKAGE/ChatListMenuHooks;"

private const val SHOULD_SHOW_ROW = "$HOOKS_TYPE->shouldShowRow($STRING)$BOOLEAN"
private const val MENU_LABEL = "$HOOKS_TYPE->menuLabel()$STRING"
private const val ACTION_CONSTRUCTOR = "$ACTION_TYPE-><init>($STRING$OBJECT)$VOID"
private const val LABEL_CONSTRUCTOR = "$LABEL_TYPE-><init>()$VOID"

private const val RENDER_METHOD_NAME = "render"
private const val LABEL_METHOD_NAME = "invoke"

/** メニュー項目のリストを組み立てる static が返す型。共有の descriptor には無いためここで持ちます。 */
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

/**
 * Compose の group key。親 group 内での slot 識別にだけ使われるため、値そのものに意味はありません。
 * 行を出す場合と出さない場合で必ず別の group を発行し、LINE 自身の各行と同じ構造を保ちます。
 */
private const val GROUP_KEY_SHOWN = 0x4C494E31
private const val GROUP_KEY_LAMBDA = 0x4C494E32
private const val GROUP_KEY_HIDDEN = 0x4C494E33

/**
 * 行 composable へ渡す `$$changed` / `$$default`。26.11.0 の LINE 自身の 4 行と、26.14.0 で行を
 * 1 つずつ描くループの双方がこの組み合わせです（`default = 6` は Modifier と 1 つ目の Function2 を
 * 既定値のままにする、という意味）。
 */
private const val ROW_CHANGED = 0xC00
private const val ROW_DEFAULT = 0x6

/** ラベル描画 lambda の register 数。複製元と同じ配置を使うため 28 で固定します。 */
private const val LABEL_REGISTER_COUNT = 28

/** 行描画メソッドの register 数。v0〜v6 を range 呼び出しに、v7 を chatId に充て、v8/v9 が引数です。 */
private const val RENDER_REGISTER_COUNT = 10

/**
 * 行 composable の呼び出し回数。
 *
 * <p>26.11.0 のメニュー本体は 4 行を条件付きで直接展開していたため 4 でした。26.14.0 では
 * メニュー項目が data class のリストになり、行は `Iterator` のループで 1 か所からだけ描かれます。</p>
 */
private const val ROW_COMPOSABLE_CALL_COUNT = 1

/** 行 composable の引数は `(Function0, Modifier, Function2, Function2, Composer, int, int)` です。 */
private const val ROW_COMPOSABLE_PARAMETER_COUNT = 7

/** `rememberComposableLambda` 相当の引数は `(int, Function, Composer)` です。 */
private const val REMEMBER_LAMBDA_PARAMETER_COUNT = 3

/** Compose の `Text` は 25 個の register を並べた range 呼び出しです。 */
private const val TEXT_REGISTER_COUNT = 25

/**
 * トーク一覧の item 型を見分けるための、難読化されないトーク種別 enum の定数名。
 * この enum を持つ data class だけを「長押しメニューが対象にしているトーク項目」として扱います。
 */
private val CHAT_TYPE_CONSTANTS = setOf("SERVICE_CHAT", "MEMO")

/**
 * トーク一覧の長押しメニューで、項目を 1 つずつ描く composable。
 *
 * <p>26.11.0 では `invoke(Object, Object, Object)Object` へ erase された 1 つの lambda が 4 行すべてを
 * 直接展開していました。26.14.0 では構造が変わり、外側の lambda はトーク種別で分岐するだけで、
 * 通常トークの中身は `(item, Function1, Composer, int)V` という別の composable が持ちます。中身は
 * `(item) -> ArrayList` が組み立てたメニュー項目のリストをループで描くだけです。</p>
 *
 * <p>そこで「引数が `(item, Function1, Composer, int)` で、行 composable を 1 回だけ呼び、
 * `rememberComposableLambda` を 1 回だけ呼び、第 1 引数だけを取って `ArrayList` を返す static を
 * 1 回だけ呼ぶ」という形で識別します。難読化名は 1 つも使いません。実 APK の全 DEX を走査して、
 * この条件を満たすメソッドは 1 件だけであることを確認済みです。</p>
 */
private fun menuRowsFingerprint(composerType: String) = Fingerprint(
    returnType = VOID,
    parameters = listOf(
        // 第 1 引数はトーク項目、第 2 引数はメニュー操作の callback。どちらも難読化型なので wildcard。
        "L",
        "L",
        composerType,
        INT,
    ),
    custom = { method, _ -> looksLikeComposeMenuRows(method, composerType) },
)

/**
 * メニュー本体を呼び出す外側の composable。`startReplaceGroup` / `endReplaceGroup` の難読化名を
 * ここから導きます。
 *
 * <p>行を描く側（[menuRowsFingerprint]）は `startRestartGroup` しか使わないため、注入する行の
 * group を発行する 2 つのメソッドをそこからは導けません。呼び出し元はトーク種別ごとの分岐を
 * replace group で囲んでいるので、そちらから借ります。</p>
 */
private fun menuContainerFingerprint(menuRows: Method, composerType: String) = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(OBJECT, OBJECT, OBJECT),
    custom = { method, _ ->
        callsMethod(method, menuRows) &&
            composerCalls(method.instructionsOrEmpty(), composerType, listOf(INT)).isNotEmpty()
    },
)

/** メニュー本体から取り出した、注入に必要な参照一式。すべて実際の命令から導出します。 */
internal data class ComposeMenuShape(
    val composerType: String,
    val itemType: String,
    val itemRegister: Int,
    val composerRegister: Int,
    val insertionIndex: Int,
    val rowComposable: MethodReference,
    val rememberLambda: MethodReference,
    val startReplaceGroup: MethodReference,
    val endReplaceGroup: MethodReference,
    val shouldExecute: String,
    val skipToGroupEnd: String,
    val labelDonorType: String,
)

/** ラベル描画 lambda の複製元から取り出した参照。 */
internal data class LabelDonorShape(
    val text: MethodReference,
    val unitField: FieldReference,
)

/**
 * トーク一覧の長押しメニューの先頭へ「既読をつけずに読む」の行を追加します。
 *
 * <h2>なぜ Compose を直接描くのか</h2>
 * <p>LINE の長押しメニューは Jetpack Compose のダイアログです。View も RecyclerView も介さないため、
 * 「メニュー項目のリストへ要素を足す」という従来の方法は使えません。</p>
 *
 * <p>26.14.0 でメニューは data 駆動になり、項目は `(item) -> ArrayList` が組み立てたリストを
 * ループで描く形になりました。そのリストへ項目を足す方法は、項目のアクションが難読化された
 * enum で、消費側がその enum で分岐しているため使えません（新しいアクションを表現できない）。
 * そこで 26.11.0 と同じく、LINE 自身の行とまったく同じ手順で行を 1 つ描きます。行 composable・
 * `rememberComposableLambda`・`startReplaceGroup` / `endReplaceGroup` の参照はすべて実際の命令列から
 * 取り出すため、難読化名を patch へ書き込みません。</p>
 *
 * <h2>注入するもの</h2>
 * <ol>
 *   <li>extension の {@code ReadWithoutReceiptMenuLabel} へ、ラベルを描く
 *   {@code invoke(Object, Object)Object} を追加します。中身は LINE 自身のラベル lambda から
 *   複製し、文字列 resource の読み出しだけを {@code ChatListMenuHooks.menuLabel()} に差し替えます。
 *   extension は難読化された Compose の型をコンパイル時に参照できないため、この方法を採ります。</li>
 *   <li>extension の {@code ReadWithoutReceiptMenuRow} へ、行を 1 つ描く static メソッドを
 *   追加します。設定 OFF や対象トーク不明のときも、LINE 自身の各行と同じく別 key の空 group を
 *   発行するため、Compose の slot 構造は常に一定です。</li>
 *   <li>メニュー本体の先頭（`shouldExecute` の分岐を通過した直後、LINE の 1 行目より前）へ、その
 *   static メソッドの呼び出しを 1 命令だけ挿入します。</li>
 * </ol>
 *
 * <p>どの段でも導出に失敗した場合は何も注入せず、Patch Status を記録して終了します。</p>
 */
val readWithoutReceiptComposeMenuPatch = bytecodePatch(
    name = "既読をつけずに読む",
    description = "トーク一覧の長押しメニューへ「既読をつけずに読む」の行を追加します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(readWithoutReceiptMenuLabelResourcePatch)

    execute {
        val composeRuntime = resolveComposeRuntime()
        if (composeRuntime == null) {
            recordFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = 0,
                reason = "ReadWithoutReceiptComposeMenuRuntimeUnavailable",
            )
            return@execute
        }

        // 行 composable を呼ぶ形だけでは他機能の composable も引っかかるため、対象のトーク項目型が
        // トーク種別 enum を持つことまで確認して、トーク一覧のメニューに限定します。
        val matches = menuRowsFingerprint(composeRuntime.composer).matchAllOrNull().orEmpty()
            .filter { isChatListItem(it.originalMethod.parameterTypes.first().toString()) }
        if (matches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = matches.size,
                reason = "ReadWithoutReceiptComposeMenuNotUnique",
            )
            return@execute
        }

        val match = matches.single()
        val containers = menuContainerFingerprint(match.originalMethod, composeRuntime.composer)
            .matchAllOrNull().orEmpty()
        if (containers.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = containers.size,
                reason = "ReadWithoutReceiptComposeMenuContainerNotUnique",
            )
            return@execute
        }

        val groups = composeMenuGroupShape(containers.single().originalMethod, composeRuntime)
        if (groups == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadWithoutReceiptComposeMenuGroupCallsNotUnique",
            )
            return@execute
        }

        val shape = composeMenuShape(match.originalMethod, composeRuntime, groups)
        if (shape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadWithoutReceiptComposeMenuShapeMismatch",
            )
            return@execute
        }

        val chatIdField = chatIdField(classDefByOrNull(shape.itemType))
        if (chatIdField == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadWithoutReceiptComposeMenuChatIdFieldNotUnique",
            )
            return@execute
        }

        val donor = labelDonorShape(classDefByOrNull(shape.labelDonorType)?.methods?.toList().orEmpty())
        if (donor == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadWithoutReceiptComposeMenuLabelDonorMismatch",
            )
            return@execute
        }

        val actionClass = mutableClassDefByOrNull(ACTION_TYPE)
        val labelClass = mutableClassDefByOrNull(LABEL_TYPE)
        val rowClass = mutableClassDefByOrNull(ROW_TYPE)
        if (actionClass == null || labelClass == null || rowClass == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadWithoutReceiptComposeMenuExtensionClassMissing",
            )
            return@execute
        }

        val function0Type = shape.rowComposable.parameterTypes[0].toString()
        val function2Type = shape.rowComposable.parameterTypes[2].toString()
        if (!actionClass.interfaces.contains(function0Type)) {
            actionClass.interfaces.add(function0Type)
        }
        if (!labelClass.interfaces.contains(function2Type)) {
            labelClass.interfaces.add(function2Type)
        }

        addLabelInvoke(labelClass, shape, donor)
        addRenderMethod(rowClass, shape, chatIdField)

        match.method.addInstructionsWithLabels(
            shape.insertionIndex,
            "invoke-static { v${shape.itemRegister}, v${shape.composerRegister} }, " +
                "$ROW_TYPE->$RENDER_METHOD_NAME(${shape.itemType}${shape.composerType})$VOID",
        )

        recordFeatureStatus(
            listOf(PatchId.READ_WITHOUT_RECEIPT_COMPOSE_MENU_ROW),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ReadWithoutReceiptComposeMenuRowInjected",
        )
    }
}

/**
 * ラベルを描く `invoke(Object, Object)Object` を extension のクラスへ追加します。
 *
 * <p>命令の並びは LINE 自身のラベル lambda と同一で、文字列 resource の読み出しだけを
 * {@code ChatListMenuHooks.menuLabel()} へ差し替えています。register 配置も複製元に合わせ、
 * 25 引数の `Text` 呼び出しへ v0〜v24 を並べます。26.11.0 と 26.14.0 で複製元の並びは同じです。</p>
 */
private fun addLabelInvoke(labelClass: MutableClass, shape: ComposeMenuShape, donor: LabelDonorShape) {
    val method = newMethod(
        definingClass = labelClass.type,
        name = LABEL_METHOD_NAME,
        parameterTypes = listOf(OBJECT, OBJECT),
        returnType = OBJECT,
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        registerCount = LABEL_REGISTER_COUNT,
        returnsObject = true,
    )

    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, v${LABEL_REGISTER_COUNT - 2}
            check-cast v0, ${shape.composerType}
            move-object/from16 v1, v${LABEL_REGISTER_COUNT - 1}
            check-cast v1, $INTEGER
            invoke-virtual { v1 }, $INTEGER->intValue()$INT
            move-result v1
            and-int/lit8 v2, v1, 0x3
            const/4 v3, 0x2
            const/4 v4, 0x1
            if-eq v2, v3, :rwrLabelSkipFlag
            move v2, v4
            goto :rwrLabelFlagDone
            :rwrLabelSkipFlag
            const/4 v2, 0x0
            :rwrLabelFlagDone
            and-int/2addr v1, v4
            invoke-interface { v0, v1, v2 }, ${shape.shouldExecute}
            move-result v1
            if-eqz v1, :rwrLabelSkip
            invoke-static { }, $MENU_LABEL
            move-result-object v1
            const/16 v23, 0x0
            const v24, 0x3fffe
            move-object/from16 v21, v0
            move-object v0, v1
            const/4 v1, 0x0
            const-wide/16 v2, 0x0
            const/4 v4, 0x0
            const-wide/16 v5, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            const-wide/16 v9, 0x0
            const/4 v11, 0x0
            const/4 v12, 0x0
            const-wide/16 v13, 0x0
            const/4 v15, 0x0
            const/16 v16, 0x0
            const/16 v17, 0x0
            const/16 v18, 0x0
            const/16 v19, 0x0
            const/16 v20, 0x0
            const/16 v22, 0x0
            invoke-static/range { v0 .. v24 }, ${donor.text.smali()}
            goto :rwrLabelDone
            :rwrLabelSkip
            move-object/from16 v21, v0
            invoke-interface/range { v21 .. v21 }, ${shape.skipToGroupEnd}
            :rwrLabelDone
            sget-object v0, ${donor.unitField.smali()}
            return-object v0
        """.trimIndent(),
    )

    labelClass.addMethod(method)
}

/**
 * 行を 1 つ描く static メソッドを extension のクラスへ追加します。
 *
 * <p>行を出す経路と出さない経路の両方で group を発行します。LINE 自身の各行がこの形（条件が
 * 成り立たない場合も別 key の空 group を出す）を採っており、Compose の slot 構造を recomposition
 * のたびに一定へ保つために必要です。</p>
 *
 * <p>26.11.0 はメニューを閉じる Kotlin `Function0` をメニュー本体から取り出して
 * {@code ReadWithoutReceiptAction} へ渡していました。26.14.0 の行を描く composable にはその
 * `Function0` が渡ってこず（受け取るのはメニュー操作の `Function1` だけ）、`Function1` を
 * 呼ぶと LINE 本来のメニュー操作が走ってしまうため、null を渡します。
 * {@code ReadWithoutReceiptAction.dismissMenu} は null を無視するので、トークは開き、
 * メニューだけがその背後に残ります。</p>
 */
private fun addRenderMethod(rowClass: MutableClass, shape: ComposeMenuShape, chatIdField: FieldReference) {
    val method = newMethod(
        definingClass = rowClass.type,
        name = RENDER_METHOD_NAME,
        parameterTypes = listOf(shape.itemType, shape.composerType),
        returnType = VOID,
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
        registerCount = RENDER_REGISTER_COUNT,
        returnsObject = false,
    )

    val item = "v${RENDER_REGISTER_COUNT - 2}"
    val composer = "v${RENDER_REGISTER_COUNT - 1}"

    method.addInstructionsWithLabels(
        0,
        """
            iget-object v7, $item, ${chatIdField.smali()}
            invoke-static { v7 }, $SHOULD_SHOW_ROW
            move-result v0
            if-eqz v0, :rwrRowHidden
            const v0, $GROUP_KEY_SHOWN
            invoke-interface { $composer, v0 }, ${shape.startReplaceGroup.smali()}
            new-instance v0, $ACTION_TYPE
            const/4 v1, 0x0
            invoke-direct { v0, v7, v1 }, $ACTION_CONSTRUCTOR
            new-instance v1, $LABEL_TYPE
            invoke-direct { v1 }, $LABEL_CONSTRUCTOR
            const v2, $GROUP_KEY_LAMBDA
            invoke-static { v2, v1, $composer }, ${shape.rememberLambda.smali()}
            move-result-object v3
            const/4 v1, 0x0
            const/4 v2, 0x0
            move-object v4, $composer
            const/16 v5, $ROW_CHANGED
            const/4 v6, $ROW_DEFAULT
            invoke-static/range { v0 .. v6 }, ${shape.rowComposable.smali()}
            invoke-interface { $composer }, ${shape.endReplaceGroup.smali()}
            goto :rwrRowDone
            :rwrRowHidden
            const v0, $GROUP_KEY_HIDDEN
            invoke-interface { $composer, v0 }, ${shape.startReplaceGroup.smali()}
            invoke-interface { $composer }, ${shape.endReplaceGroup.smali()}
            :rwrRowDone
            nop
        """.trimIndent(),
    )

    rowClass.addMethod(method)
}

/**
 * 指定した register 数を持つ空のメソッドを作ります。`MutableMethodImplementation` の registerCount は
 * 変更できないため、注入したい smali が必要とする register 数をここで確定させます。末尾の 1〜2 命令は
 * 注入後に到達しない dead code として残りますが、型は矛盾しない形にしています。
 */
private fun newMethod(
    definingClass: String,
    name: String,
    parameterTypes: List<String>,
    returnType: String,
    accessFlags: Int,
    registerCount: Int,
    returnsObject: Boolean,
): MutableMethod {
    val implementation = MutableMethodImplementation(registerCount)
    if (returnsObject) {
        implementation.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 0, 0))
        implementation.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
    } else {
        implementation.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
    }

    return MutableMethod(
        ImmutableMethod(
            definingClass,
            name,
            parameterTypes.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            implementation,
        ),
    )
}

/**
 * DEX writer は `methods` ではなく `directMethods` / `virtualMethods` を読むため、両方へ登録します。
 * `MutableMethod` の equals は method reference 由来なので、重複登録は Set 上で無視されます。
 */
private fun MutableClass.addMethod(method: MutableMethod) {
    methods.add(method)
    if (AccessFlags.STATIC.isSet(method.accessFlags)) {
        directMethods.add(method)
    } else {
        virtualMethods.add(method)
    }
}

/**
 * メニュー項目を 1 つずつ描く composable かどうか。fingerprint の絞り込みにだけ使うため、ここでは
 * register や index を検証しません。
 */
internal fun looksLikeComposeMenuRows(method: Method, composerType: String): Boolean {
    val instructions = method.instructionsOrEmpty()
    if (instructions.isEmpty()) {
        return false
    }
    val itemType = method.parameterTypes.firstOrNull()?.toString() ?: return false
    return rowComposableCallIndices(instructions, composerType).size == ROW_COMPOSABLE_CALL_COUNT &&
        rememberLambdaCalls(instructions, composerType).size == 1 &&
        menuEntryListCalls(instructions, itemType).size == 1
}

/** `startReplaceGroup` / `endReplaceGroup` の 1 組。呼び出し元の命令列から導きます。 */
internal data class ComposeMenuGroupShape(
    val startReplaceGroup: MethodReference,
    val endReplaceGroup: MethodReference,
)

/**
 * 呼び出し元から group を発行する 2 つのメソッドを取り出します。
 *
 * <p>`(int)V` を取る Composer のメソッドは `startReplaceGroup` だけです。`()V` は
 * `endReplaceGroup` と `skipToGroupEnd` の 2 つが現れるため、後者を [ComposeRuntime] から
 * 得た名前で除きます。どちらも 1 件に絞れない場合は null を返し、patch は何も注入しません。</p>
 */
internal fun composeMenuGroupShape(container: Method, composeRuntime: ComposeRuntime): ComposeMenuGroupShape? {
    val instructions = container.instructionsOrEmpty()
    val startReplaceGroup = composerCalls(instructions, composeRuntime.composer, listOf(INT))
        .distinct()
        .singleOrNull() ?: return null
    val endReplaceGroup = composerCalls(instructions, composeRuntime.composer, emptyList())
        .distinct()
        .filter { it.name != composeRuntime.skipToGroupEnd }
        .singleOrNull() ?: return null
    return ComposeMenuGroupShape(startReplaceGroup, endReplaceGroup)
}

/**
 * メニュー本体の命令列を検証し、注入に必要な参照と register を取り出します。
 * 実測どおりの並びでなければ null を返し、patch は何も注入しません。
 */
internal fun composeMenuShape(
    method: Method,
    composeRuntime: ComposeRuntime,
    groups: ComposeMenuGroupShape,
): ComposeMenuShape? {
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions.toList()

    // `shouldExecute` の分岐を通過した直後、LINE の 1 行目より前へ注入します。分岐そのものの位置
    // （if-eqz）は [composeShouldExecuteGate] が「既存の分岐先ではないこと」まで検証しています。
    val gate = composeShouldExecuteGate(method, composeRuntime) ?: return null
    val insertionIndex = gate.branchIndex + 1
    if (isDivertedInjectionIndex(instructions, insertionIndex, exceptionHandlerAddresses(implementation))) {
        return null
    }

    // gate は「shouldExecute → move-result → if-eqz」の並びを保証するので、if-eqz の 2 つ前が
    // shouldExecute の呼び出しです。その receiver が Composer を保持する register です。
    val shouldExecuteCall = instructions.getOrNull(gate.branchIndex - 2)
    val composerRegister = (shouldExecuteCall as? FiveRegisterInstruction)?.registerC ?: return null
    val shouldExecuteReference = methodReference(shouldExecuteCall) ?: return null
    if (
        shouldExecuteCall.opcode != Opcode.INVOKE_VIRTUAL ||
        shouldExecuteReference.definingClass != composeRuntime.composerImpl ||
        shouldExecuteReference.name != composeRuntime.shouldExecute
    ) {
        return null
    }

    // 26.11.0 はトーク項目をメニュー本体の field から読み出して instance-of で確かめていましたが、
    // 26.14.0 では第 1 引数として型付きで渡ってくるため、その register をそのまま使います。
    val itemType = method.parameterTypes.firstOrNull()?.toString() ?: return null
    val itemRegister = implementation.registerCount - method.parameterTypes.size
    if (itemRegister < 0) {
        return null
    }
    // 引数の値が注入位置まで生き残っていることを、実際の並びで確かめます。
    if ((0 until insertionIndex).any { index -> writesRegister(instructions[index], itemRegister) }) {
        return null
    }

    // 行の呼び出しは 35c 形式の invoke-static なので、引数の register は 4bit（v0〜v15）しか取れません。
    if (composerRegister !in 0..15 || itemRegister !in 0..15) {
        return null
    }

    val rowCallIndices = rowComposableCallIndices(instructions, composeRuntime.composer)
    if (rowCallIndices.size != ROW_COMPOSABLE_CALL_COUNT) {
        return null
    }
    val rowComposable = methodReference(instructions[rowCallIndices.single()]) ?: return null
    val rememberLambda = rememberLambdaCalls(instructions, composeRuntime.composer).singleOrNull() ?: return null
    val labelDonorType = labelDonorType(instructions, rememberLambda) ?: return null

    return ComposeMenuShape(
        composerType = composeRuntime.composer,
        itemType = itemType,
        itemRegister = itemRegister,
        composerRegister = composerRegister,
        insertionIndex = insertionIndex,
        rowComposable = rowComposable,
        rememberLambda = rememberLambda,
        startReplaceGroup = groups.startReplaceGroup,
        endReplaceGroup = groups.endReplaceGroup,
        shouldExecute = "${composeRuntime.composer}->${composeRuntime.shouldExecute}($INT$BOOLEAN)$BOOLEAN",
        skipToGroupEnd = "${composeRuntime.composer}->${composeRuntime.skipToGroupEnd}()$VOID",
        labelDonorType = labelDonorType,
    )
}

/**
 * `rememberComposableLambda` へ渡される lambda の型。直前の `new-instance` から取り出します。
 * この lambda が、複製元とするラベル描画の実装を持ちます。
 */
private fun labelDonorType(instructions: List<Instruction>, rememberLambda: MethodReference): String? {
    for (index in instructions.indices) {
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INVOKE_STATIC) continue
        if (methodReference(instruction) != rememberLambda) continue
        val lambdaRegister = (instruction as? Instruction35c)?.registerD ?: continue

        for (previous in index - 1 downTo 0) {
            val candidate = instructions[previous]
            if (candidate.opcode != Opcode.NEW_INSTANCE) continue
            if ((candidate as OneRegisterInstruction).registerA != lambdaRegister) continue
            return typeReference(candidate)
        }
    }
    return null
}

/**
 * 複製元のラベル描画 lambda から、`Text` の method reference と `kotlin.Unit.INSTANCE` の
 * field reference を取り出します。どちらも 1 件に絞れない場合は null を返します。
 */
internal fun labelDonorShape(methods: List<Method>): LabelDonorShape? {
    val invoke = methods.singleOrNull { candidate ->
        candidate.name == LABEL_METHOD_NAME &&
            candidate.returnType == OBJECT &&
            candidate.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT)
    } ?: return null
    val instructions = invoke.implementation?.instructions?.toList() ?: return null

    val text = instructions
        .filter { it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .filter { (it as RegisterRangeInstruction).registerCount == TEXT_REGISTER_COUNT }
        .mapNotNull { methodReference(it) }
        .distinct()
        .singleOrNull() ?: return null

    val unitField = instructions
        .filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { fieldReference(it) }
        .distinct()
        .singleOrNull() ?: return null

    return LabelDonorShape(text = text, unitField = unitField)
}

/** トーク項目の型が持つ、ただ 1 つの String instance field。これがトーク ID です。 */
internal fun chatIdField(itemClass: com.android.tools.smali.dexlib2.iface.ClassDef?): FieldReference? =
    itemClass?.instanceFields?.singleOrNull { it.type == STRING }

/**
 * トーク一覧の項目型かどうか。String の field を 1 つだけ持ち、かつトーク種別 enum
 * （難読化されない `SERVICE_CHAT` / `MEMO` を持つ型）の field を持つことを求めます。
 * 行 composable の形だけでは他機能のメニューと区別できないため、この意味づけを anchor にします。
 */
private fun BytecodePatchContext.isChatListItem(itemType: String): Boolean {
    val itemClass = classDefByOrNull(itemType) ?: return false
    if (itemClass.instanceFields.count { it.type == STRING } != 1) {
        return false
    }
    return itemClass.instanceFields.any { field ->
        val fieldClass = classDefByOrNull(field.type) ?: return@any false
        val names = fieldClass.staticFields.map { it.name }.toSet()
        CHAT_TYPE_CONSTANTS.all { it in names }
    }
}

/**
 * 行 composable の呼び出し位置。引数は
 * `(Function0, Modifier, Function2, Function2, Composer, int, int)` を返り値 void で取ります。
 */
private fun rowComposableCallIndices(instructions: List<Instruction>, composerType: String): List<Int> =
    instructions.indices.filter { index ->
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
            return@filter false
        }
        val reference = methodReference(instruction) ?: return@filter false
        val parameters = reference.parameterTypes.map(CharSequence::toString)
        reference.returnType == VOID &&
            parameters.size == ROW_COMPOSABLE_PARAMETER_COUNT &&
            parameters[4] == composerType &&
            parameters[5] == INT &&
            parameters[6] == INT
    }

/** `rememberComposableLambda` 相当の呼び出し。`(int, Function, Composer)` を取り値を返します。 */
private fun rememberLambdaCalls(instructions: List<Instruction>, composerType: String): List<MethodReference> =
    instructions
        .filter { it.opcode == Opcode.INVOKE_STATIC }
        .mapNotNull { methodReference(it) }
        .filter { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            parameters.size == REMEMBER_LAMBDA_PARAMETER_COUNT &&
                parameters[0] == INT &&
                parameters[2] == composerType &&
                reference.returnType != VOID
        }
        .distinct()

/** メニュー項目のリストを組み立てる static。トーク項目だけを取り `ArrayList` を返します。 */
private fun menuEntryListCalls(instructions: List<Instruction>, itemType: String): List<MethodReference> =
    instructions
        .filter { it.opcode == Opcode.INVOKE_STATIC }
        .mapNotNull { methodReference(it) }
        .filter { reference ->
            reference.returnType == ARRAY_LIST &&
                reference.parameterTypes.map(CharSequence::toString) == listOf(itemType)
        }
        .distinct()

/** Composer の interface メソッド呼び出しのうち、引数と返り値 void が一致するもの。 */
private fun composerCalls(
    instructions: List<Instruction>,
    composerType: String,
    parameters: List<String>,
): List<MethodReference> = instructions
    .filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }
    .mapNotNull { methodReference(it) }
    .filter {
        it.definingClass == composerType &&
            it.returnType == VOID &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    }

/** [method] が [target] を呼ぶかどうか。難読化名ではなく解決済みの参照そのもので突き合わせます。 */
private fun callsMethod(method: Method, target: Method): Boolean =
    method.instructionsOrEmpty().any { instruction ->
        methodReference(instruction)?.let { reference ->
            reference.definingClass == target.definingClass &&
                reference.name == target.name &&
                reference.parameterTypes.map(CharSequence::toString) ==
                    target.parameterTypes.map(CharSequence::toString) &&
                reference.returnType == target.returnType
        } == true
    }

/** wide 命令は宛先とその次の register の pair へ書き込むため、上位半分も数えます。 */
private fun writesRegister(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister() && !instruction.opcode.setsWideRegister()) {
        return false
    }
    val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register ||
        (instruction.opcode.setsWideRegister() && destination + 1 == register)
}

private fun Method.instructionsOrEmpty(): List<Instruction> =
    implementation?.instructions?.toList().orEmpty()

private fun MethodReference.smali(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.smali(): String = "$definingClass->$name:$type"

private fun methodReference(instruction: Instruction?): MethodReference? =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

private fun fieldReference(instruction: Instruction?): FieldReference? =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

private fun typeReference(instruction: Instruction?): String? =
    ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type

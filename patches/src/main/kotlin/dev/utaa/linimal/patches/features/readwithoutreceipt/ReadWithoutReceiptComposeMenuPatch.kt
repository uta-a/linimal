package dev.utaa.linimal.patches.features.readwithoutreceipt

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val VOID = "V"
private const val INT = "I"
private const val BOOLEAN = "Z"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val INTEGER = "Ljava/lang/Integer;"

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

/**
 * Compose の group key。親 group 内での slot 識別にだけ使われるため、値そのものに意味はありません。
 * 行を出す場合と出さない場合で必ず別の group を発行し、LINE 自身の各行と同じ構造を保ちます。
 */
private const val GROUP_KEY_SHOWN = 0x4C494E31
private const val GROUP_KEY_LAMBDA = 0x4C494E32
private const val GROUP_KEY_HIDDEN = 0x4C494E33

/** 行 composable へ渡す `$$changed` / `$$default`。LINE 自身の 4 行がすべてこの組み合わせです。 */
private const val ROW_CHANGED = 0xC00
private const val ROW_DEFAULT = 0x6

/** ラベル描画 lambda の register 数。複製元と同じ配置を使うため 28 で固定します。 */
private const val LABEL_REGISTER_COUNT = 28

/** 行描画メソッドの register 数。v0〜v6 を range 呼び出しに使い、v7 を chatId に充てます。 */
private const val RENDER_REGISTER_COUNT = 11

/** メニュー本体の命令列で確定している index。[composeMenuShape] がすべて検証します。 */
private const val COMPOSER_CAST_INDEX = 2
private const val SHOULD_EXECUTE_INDEX = 15
private const val SHOULD_EXECUTE_BRANCH_INDEX = 17
private const val ITEM_READ_INDEX = 19
private const val ITEM_INSTANCE_OF_INDEX = 20
private const val DISMISS_READ_INDEX = 21
private const val ROW_INSERTION_INDEX = 22

/** 行 composable は 4 回呼ばれます（非表示 / 通知 / ピン留め / 削除）。 */
private const val ROW_COMPOSABLE_CALL_COUNT = 4

/** Compose の `Text` は 25 個の register を並べた range 呼び出しです。 */
private const val TEXT_REGISTER_COUNT = 25

/**
 * トーク一覧の長押しメニューの本体を探す fingerprint。
 *
 * <p>難読化名には一切依存しません。Compose の lambda は `invoke(Object, Object, Object)Object` へ
 * erase されるため、その形をした全メソッドのうち、(1) 行 composable
 * `(Function0, Modifier, Function2, Function2, Composer, int, int)V` を 4 回呼び、
 * (2) 同一の型へ 4 回 `instance-of` / `check-cast` するもの、という条件で絞ります。
 * 実 APK の全 DEX を走査して、この条件を満たすメソッドは 1 件だけであることを確認済みです。</p>
 */
private val composeMenuFingerprint = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(OBJECT, OBJECT, OBJECT),
    custom = { method, _ -> looksLikeComposeMenu(method) },
)

/** メニュー本体から取り出した、注入に必要な参照一式。すべて実際の命令から導出します。 */
internal data class ComposeMenuShape(
    val composerType: String,
    val itemType: String,
    val dismissRegister: Int,
    val itemRegister: Int,
    val composerRegister: Int,
    val rowComposable: MethodReference,
    val rememberLambda: MethodReference,
    val startReplaceGroup: MethodReference,
    val endReplaceGroup: MethodReference,
    val skipToGroupEnd: MethodReference,
    val shouldExecute: MethodReference,
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
 * <p>LINE 26.11.0 の長押しメニューは Jetpack Compose のダイアログです。View も RecyclerView も
 * 介さないため、「メニュー項目のリストへ要素を足す」という従来の方法は使えません。旧実装が対象に
 * していた AlertDialog 経路は APK 内に残っていますが実行されない死んだコードでした。</p>
 *
 * <p>そこで、LINE 自身の 4 行とまったく同じ手順で 5 行目を描きます。行 composable・
 * `rememberComposableLambda`・`startReplaceGroup` / `endReplaceGroup` の参照はすべてメニュー本体の
 * 命令列から取り出すため、難読化名を patch へ書き込みません。</p>
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
 *   <li>メニュー本体の先頭（`shouldExecute` を通過した直後、LINE の 1 行目より前）へ、その
 *   static メソッドの呼び出しを 1 命令だけ挿入します。</li>
 * </ol>
 *
 * <p>どの段でも導出に失敗した場合は何も注入せず、Patch Status を記録して終了します。</p>
 */
val readWithoutReceiptComposeMenuPatch = bytecodePatch {
    dependsOn(readWithoutReceiptMenuLabelResourcePatch)

    execute {
        val matches = composeMenuFingerprint.matchAllOrNull().orEmpty()
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
        val shape = composeMenuShape(match.originalMethod)
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
            ROW_INSERTION_INDEX,
            "invoke-static { v${shape.dismissRegister}, v${shape.itemRegister}, " +
                "v${shape.composerRegister} }, $ROW_TYPE->$RENDER_METHOD_NAME" +
                "($OBJECT$OBJECT${shape.composerType})$VOID",
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
 * 25 引数の `Text` 呼び出しへ v0〜v24 を並べます。</p>
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
            invoke-interface { v0, v1, v2 }, ${shape.shouldExecute.smali()}
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
            invoke-interface/range { v21 .. v21 }, ${shape.skipToGroupEnd.smali()}
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
 */
private fun addRenderMethod(rowClass: MutableClass, shape: ComposeMenuShape, chatIdField: FieldReference) {
    val method = newMethod(
        definingClass = rowClass.type,
        name = RENDER_METHOD_NAME,
        parameterTypes = listOf(OBJECT, OBJECT, shape.composerType),
        returnType = VOID,
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
        registerCount = RENDER_REGISTER_COUNT,
        returnsObject = false,
    )

    val dismiss = "v${RENDER_REGISTER_COUNT - 3}"
    val item = "v${RENDER_REGISTER_COUNT - 2}"
    val composer = "v${RENDER_REGISTER_COUNT - 1}"

    method.addInstructionsWithLabels(
        0,
        """
            const/4 v7, 0x0
            instance-of v0, $item, ${shape.itemType}
            if-eqz v0, :rwrRowNoChat
            check-cast $item, ${shape.itemType}
            iget-object v7, $item, ${chatIdField.smali()}
            :rwrRowNoChat
            invoke-static { v7 }, $SHOULD_SHOW_ROW
            move-result v0
            if-eqz v0, :rwrRowHidden
            const v0, $GROUP_KEY_SHOWN
            invoke-interface { $composer, v0 }, ${shape.startReplaceGroup.smali()}
            new-instance v0, $ACTION_TYPE
            invoke-direct { v0, v7, $dismiss }, $ACTION_CONSTRUCTOR
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
 * 行 composable を 4 回呼び、かつ同一の型へ 4 回以上 `instance-of` / `check-cast` するメソッドかどうか。
 * fingerprint の絞り込みにだけ使うため、ここでは register や index を検証しません。
 */
internal fun looksLikeComposeMenu(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    val composerType = composerCastType(instructions) ?: return false

    val rowCalls = instructions.count { isRowComposableCall(it, composerType) }
    if (rowCalls != ROW_COMPOSABLE_CALL_COUNT) {
        return false
    }

    val castCounts = instructions
        .filter { it.opcode == Opcode.INSTANCE_OF || it.opcode == Opcode.CHECK_CAST }
        .mapNotNull { typeReference(it) }
        .groupingBy { it }
        .eachCount()
    return castCounts.any { (_, count) -> count >= ROW_COMPOSABLE_CALL_COUNT }
}

/**
 * メニュー本体の命令列を検証し、注入に必要な参照と register を取り出します。
 * 実測どおりの並びでなければ null を返し、patch は何も注入しません。
 */
internal fun composeMenuShape(method: Method): ComposeMenuShape? {
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions.toList()
    if (instructions.size <= ROW_INSERTION_INDEX) {
        return null
    }

    val composerType = composerCastType(instructions) ?: return null
    val composerRegister = (instructions[COMPOSER_CAST_INDEX] as OneRegisterInstruction).registerA

    val shouldExecute = methodReference(instructions[SHOULD_EXECUTE_INDEX])
    if (
        instructions[SHOULD_EXECUTE_INDEX].opcode != Opcode.INVOKE_INTERFACE ||
        shouldExecute == null ||
        shouldExecute.definingClass != composerType ||
        shouldExecute.parameterTypes.map(CharSequence::toString) != listOf(INT, BOOLEAN) ||
        shouldExecute.returnType != BOOLEAN ||
        instructions[SHOULD_EXECUTE_BRANCH_INDEX].opcode != Opcode.IF_EQZ ||
        instructions[ITEM_READ_INDEX].opcode != Opcode.IGET_OBJECT ||
        instructions[ITEM_INSTANCE_OF_INDEX].opcode != Opcode.INSTANCE_OF ||
        instructions[DISMISS_READ_INDEX].opcode != Opcode.IGET_OBJECT
    ) {
        return null
    }

    val itemRegister = (instructions[ITEM_READ_INDEX] as OneRegisterInstruction).registerA
    val instanceOf = instructions[ITEM_INSTANCE_OF_INDEX] as TwoRegisterInstruction
    if (instanceOf.registerB != itemRegister) {
        return null
    }
    val itemType = typeReference(instructions[ITEM_INSTANCE_OF_INDEX]) ?: return null
    val dismissRegister = (instructions[DISMISS_READ_INDEX] as OneRegisterInstruction).registerA

    val rowCallIndices = instructions.indices.filter { isRowComposableCall(instructions[it], composerType) }
    if (rowCallIndices.size != ROW_COMPOSABLE_CALL_COUNT) {
        return null
    }
    val rowComposable = methodReference(instructions[rowCallIndices.first()]) ?: return null
    if (rowCallIndices.any { methodReference(instructions[it]) != rowComposable }) {
        return null
    }

    val rememberLambda = instructions
        .mapNotNull { instruction ->
            if (instruction.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
            val reference = methodReference(instruction) ?: return@mapNotNull null
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            if (parameters.size == 3 && parameters[0] == INT && parameters[2] == composerType &&
                reference.returnType != VOID
            ) {
                reference
            } else {
                null
            }
        }
        .distinct()
        .singleOrNull() ?: return null

    val startReplaceGroup = composerCalls(instructions, composerType, listOf(INT))
        .distinct()
        .singleOrNull() ?: return null

    // 行 composable の直後に呼ばれる引数なしの composer メソッドが endReplaceGroup です。
    // 同じ形の skipToGroupEnd と取り違えないよう、呼び出し位置で区別します。
    val endReplaceGroup = rowCallIndices
        .mapNotNull { methodReference(instructions.getOrNull(it + 1)) }
        .filter { it.definingClass == composerType && it.parameterTypes.isEmpty() && it.returnType == VOID }
        .distinct()
        .singleOrNull() ?: return null

    val skipToGroupEnd = composerCalls(instructions, composerType, emptyList())
        .distinct()
        .filter { it != endReplaceGroup }
        .singleOrNull() ?: return null

    val labelDonorType = labelDonorType(instructions, rememberLambda) ?: return null

    return ComposeMenuShape(
        composerType = composerType,
        itemType = itemType,
        dismissRegister = dismissRegister,
        itemRegister = itemRegister,
        composerRegister = composerRegister,
        rowComposable = rowComposable,
        rememberLambda = rememberLambda,
        startReplaceGroup = startReplaceGroup,
        endReplaceGroup = endReplaceGroup,
        skipToGroupEnd = skipToGroupEnd,
        shouldExecute = shouldExecute,
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

/** 命令 2 の `check-cast` が示す Composer の型。 */
private fun composerCastType(instructions: List<Instruction>): String? {
    val instruction = instructions.getOrNull(COMPOSER_CAST_INDEX) ?: return null
    if (instruction.opcode != Opcode.CHECK_CAST) {
        return null
    }
    return typeReference(instruction)
}

/**
 * 行 composable の呼び出しかどうか。引数は
 * `(Function0, Modifier, Function2, Function2, Composer, int, int)` を返り値 void で取ります。
 */
private fun isRowComposableCall(instruction: Instruction, composerType: String): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
        return false
    }
    val reference = methodReference(instruction) ?: return false
    val parameters = reference.parameterTypes.map(CharSequence::toString)
    return reference.returnType == VOID &&
        parameters.size == 7 &&
        parameters[4] == composerType &&
        parameters[5] == INT &&
        parameters[6] == INT
}

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

private fun MethodReference.smali(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.smali(): String = "$definingClass->$name:$type"

private fun methodReference(instruction: Instruction?): MethodReference? =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

private fun fieldReference(instruction: Instruction?): FieldReference? =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

private fun typeReference(instruction: Instruction?): String? =
    ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type

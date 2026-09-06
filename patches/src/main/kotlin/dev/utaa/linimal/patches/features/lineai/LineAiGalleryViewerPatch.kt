package dev.utaa.linimal.patches.features.lineai

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.OBJECT
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.branchTargetAddress
import dev.utaa.linimal.patches.util.instructionAddress

private const val VIEW = "Landroid/view/View;"
private const val LINE_AI_GALLERY_SOURCE_VALUE = "chatroom_image_viewer"
private const val LINE_AI_GALLERY_HOOK =
    "Ldev/utaa/linimal/extension/features/lineai/LineAiGalleryViewerHooks;->adjustVisibility(Z)Z"

/**
 * LINE_AI_EDIT_IMAGE tooltip model. Together with the binder's button/raw resources this forms the gallery resource
 * tuple, while avoiding any alteration of generic media viewer controls.
 *
 * 26.11.0 で条件にしていた宣言クラス `Lfu7/o$a;` は 26.14.0 で `Lv18/l$a;` へ変わる難読化名です。
 * tooltip resource と難読化されない enum 定数名 `LINE_AI_EDIT_IMAGE` だけで全 DEX 中 1 件に絞れるため、
 * 型は一致した class から実行時に導出します。
 */
private val lineAiGalleryViewerActionFingerprint = Fingerprint(
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        literal(0x7f0b061b), // chat_gallery_line_ai_edit_image_tooltip
        string("LINE_AI_EDIT_IMAGE"),
        fieldAccess(
            name = "LINE_AI_EDIT_IMAGE",
            opcode = Opcode.SPUT_OBJECT,
        ),
    ),
)

/**
 * The source enum is independently tied to the gallery viewer's stable parameter value.
 *
 * 26.11.0 の `Lrq1/a;` は 26.14.0 で `Lzt1/a;` へ変わる難読化名のため、宣言クラスは条件にせず
 * 一致した class から導出します。
 */
private val lineAiGalleryViewerEntrySourceFingerprint = Fingerprint(
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        string(LINE_AI_GALLERY_SOURCE_VALUE),
        fieldAccess(
            name = "IMAGE_VIEWER",
            opcode = Opcode.SPUT_OBJECT,
        ),
    ),
)

/**
 * The binder is selected by the button ID, raw icon, LINE_AI_EDIT_IMAGE action, and click callback sequence rather
 * than by the obfuscated binder method name. It is invoked by the page-rebound header availability stream.
 *
 * 26.11.0 では click callback の型 `Lhu7/u0;` を直接指定していましたが、26.14.0 では `Lx18/y;` です。
 * 版ごとに変わるため、直前の resource literal に続く最初の `new-instance` として位置で特定し、
 * 型は一致した命令から導出します。
 */
private fun lineAiGalleryViewerBinderFingerprint(actionType: String) = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(OBJECT),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/lang/Boolean;",
            name = "booleanValue",
            returnType = BOOLEAN,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        literal(0x7f0b061a), // chat_gallery_line_ai_button
        methodCall(
            definingClass = VIEW,
            name = "setVisibility",
            parameters = listOf("I"),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        fieldAccess(
            definingClass = actionType,
            name = "LINE_AI_EDIT_IMAGE",
            type = actionType,
            opcode = Opcode.SGET_OBJECT,
        ),
        literal(0x7f1400ac), // viewer_ic_line_ai
        opcode(Opcode.NEW_INSTANCE),
    ),
)

/** The click callback has to use the dedicated IMAGE_VIEWER entry source. */
private fun lineAiGalleryViewerClickFingerprint(clickCallbackType: String, entrySourceType: String) = Fingerprint(
    definingClass = clickCallbackType,
    returnType = VOID,
    parameters = listOf(VIEW),
    filters = listOf(
        fieldAccess(
            definingClass = entrySourceType,
            name = "IMAGE_VIEWER",
            type = entrySourceType,
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

/**
 * Ensures the binder is registered as the `updateLineAiHeaderButton(Z)` page-rebind callback before injection.
 *
 * observer 登録の第 2 引数は 26.11.0 が `Landroidx/lifecycle/g1;`、26.14.0 が `Landroidx/lifecycle/i1;` と
 * 難読化名が変わるため前方一致で受けます。
 */
private fun lineAiGalleryViewerRebindFingerprint(binderType: String) = Fingerprint(
    returnType = VOID,
    filters = listOf(
        newInstance(binderType),
        string("updateLineAiHeaderButton(Z)V"),
        methodCall(
            parameters = listOf("Landroidx/lifecycle/u0;", "L"),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
)

/**
 * Alters only this header binder's input boolean. The underlying viewer, media data, click callback, telemetry,
 * and network path continue unchanged; each page rebind runs the adjusted binder value again.
 */
val lineAiGalleryViewerPatch = bytecodePatch(
    name = "写真・動画表示画面の LINE AI",
    description = "チャットの写真・動画表示画面にある LINE AI の入口を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(lineAiMessageContextMenuPatch)

    execute {
        // 難読化型は resource / 文字列 / enum 定数名の anchor から順に導出します。
        val actionMatches = lineAiGalleryViewerActionFingerprint.matchAllOrNull().orEmpty()
        if (actionMatches.size != 1) {
            recordLineAiGalleryViewerUnapplied(actionMatches.size, "LineAiGalleryViewerActionNotUnique")
            return@execute
        }
        val actionType = actionMatches.single().originalClassDef.type

        val sourceMatches = lineAiGalleryViewerEntrySourceFingerprint.matchAllOrNull().orEmpty()
        if (sourceMatches.size != 1) {
            recordLineAiGalleryViewerUnapplied(sourceMatches.size, "LineAiGalleryViewerEntrySourceNotUnique")
            return@execute
        }
        val entrySourceType = sourceMatches.single().originalClassDef.type

        val binderMatches = lineAiGalleryViewerBinderFingerprint(actionType).matchAllOrNull().orEmpty()
        if (binderMatches.size != 1) {
            recordLineAiGalleryViewerUnapplied(binderMatches.size, "LineAiGalleryViewerBinderNotUnique")
            return@execute
        }
        val binder = binderMatches.single()
        val clickCallbackInstruction = binder.instructionMatches[5].instruction as? ReferenceInstruction
        val clickCallbackType = (clickCallbackInstruction?.reference as? TypeReference)?.type
        val clickMatches = clickCallbackType
            ?.let { lineAiGalleryViewerClickFingerprint(it, entrySourceType).matchAllOrNull().orEmpty() }
            .orEmpty()
        if (clickMatches.size != 1) {
            recordLineAiGalleryViewerUnapplied(clickMatches.size, "LineAiGalleryViewerClickNotUnique")
            return@execute
        }

        val rebindMatches = lineAiGalleryViewerRebindFingerprint(binder.originalClassDef.type)
            .matchAllOrNull()
            .orEmpty()
        if (rebindMatches.size != 1) {
            recordLineAiGalleryViewerUnapplied(rebindMatches.size, "LineAiGalleryViewerRebindNotUnique")
            return@execute
        }

        val injectionShape = lineAiGalleryViewerInjectionShape(binder, actionType)
        if (injectionShape == null) {
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.LINE_AI_GALLERY_VIEWER,
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "LineAiGalleryViewerBinderInstructionShapeMismatch",
                ),
            )
            return@execute
        }

        // Immediately after Boolean.booleanValue(): all initial and subsequent gallery page rebinds use this value.
        binder.method.addInstructions(
            injectionShape.insertionIndex,
            """
                invoke-static { v${injectionShape.visibilityRegister} }, $LINE_AI_GALLERY_HOOK
                move-result v${injectionShape.visibilityRegister}
            """.trimIndent(),
        )
        patchStatusCollector.record(
            patchId = PatchId.LINE_AI_GALLERY_VIEWER,
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "LineAiGalleryViewerBinderGuarded",
        )
    }
}

private data class GalleryViewerInjectionShape(
    val insertionIndex: Int,
    val visibilityRegister: Int,
)

/**
 * Validates the boolean-to-visibility data flow and all resource/action/click anchors at the actual injection site.
 *
 * 26.11.0 は「事前に置いた 0 の register を move で複製する」形でしたが、26.14.0 では VISIBLE/GONE を
 * `setVisibility` の引数 register へ直接 const で書き込む形に変わったため、-5 の const/4 と move の
 * 検証を、-3 の const/4 0 の検証へ置き換えています。
 */
private fun lineAiGalleryViewerInjectionShape(match: Match, actionType: String): GalleryViewerInjectionShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions.toList()
    val booleanValueIndex = match.instructionMatches[0].index
    val buttonIdIndex = match.instructionMatches[1].index
    val setVisibilityIndex = match.instructionMatches[2].index
    val actionIndex = match.instructionMatches[3].index
    val rawIconIndex = match.instructionMatches[4].index
    val clickIndex = match.instructionMatches[5].index
    val booleanValue = (instructions.getOrNull(booleanValueIndex) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val result = instructions.getOrNull(booleanValueIndex + 1) as? OneRegisterInstruction
    val buttonId = instructions.getOrNull(buttonIdIndex) as? OneRegisterInstruction
    val findButton = instructions.getOrNull(buttonIdIndex + 1) as? FiveRegisterInstruction
    val findButtonReference = (instructions.getOrNull(buttonIdIndex + 1) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val buttonResult = instructions.getOrNull(buttonIdIndex + 2) as? OneRegisterInstruction
    val buttonCast = instructions.getOrNull(buttonIdIndex + 3) as? OneRegisterInstruction
    val visibilityBranch = instructions.getOrNull(setVisibilityIndex - 4) as? OneRegisterInstruction
    val visibleLiteral = instructions.getOrNull(setVisibilityIndex - 3) as? NarrowLiteralInstruction
    val visibleRegister = instructions.getOrNull(setVisibilityIndex - 3) as? OneRegisterInstruction
    val visibleGoto = instructions.getOrNull(setVisibilityIndex - 2) as? OffsetInstruction
    val goneLiteral = instructions.getOrNull(setVisibilityIndex - 1) as? NarrowLiteralInstruction
    val goneRegister = instructions.getOrNull(setVisibilityIndex - 1) as? OneRegisterInstruction
    val setVisibility = instructions.getOrNull(setVisibilityIndex) as? FiveRegisterInstruction
    val setVisibilityReference = (instructions.getOrNull(setVisibilityIndex) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val action = (instructions.getOrNull(actionIndex) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val goneAddress = instructionAddress(instructions, setVisibilityIndex - 1)
    val setterAddress = instructionAddress(instructions, setVisibilityIndex)

    if (
        instructions.getOrNull(booleanValueIndex - 1)?.opcode != Opcode.CHECK_CAST ||
        instructions.getOrNull(booleanValueIndex)?.opcode != Opcode.INVOKE_VIRTUAL ||
        booleanValue?.definingClass != "Ljava/lang/Boolean;" ||
        booleanValue.name != "booleanValue" ||
        booleanValue.parameterTypes.isNotEmpty() ||
        booleanValue.returnType != BOOLEAN ||
        result?.opcode != Opcode.MOVE_RESULT ||
        result.registerA !in 0..15 ||
        buttonId == null ||
        buttonId.opcode !in setOf(Opcode.CONST, Opcode.CONST_HIGH16) ||
        findButton?.opcode != Opcode.INVOKE_VIRTUAL ||
        findButton.registerCount != 2 ||
        findButton.registerD != buttonId.registerA ||
        findButtonReference?.definingClass != VIEW ||
        findButtonReference.name != "findViewById" ||
        findButtonReference.parameterTypes != listOf("I") ||
        findButtonReference.returnType != VIEW ||
        buttonResult?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        buttonCast?.opcode != Opcode.CHECK_CAST ||
        buttonCast.registerA != buttonResult.registerA ||
        visibilityBranch?.opcode != Opcode.IF_EQZ ||
        visibilityBranch.registerA != result.registerA ||
        branchTargetAddress(instructions, setVisibilityIndex - 4) != goneAddress ||
        visibleRegister?.opcode != Opcode.CONST_4 ||
        visibleLiteral?.narrowLiteral != 0 ||
        visibleGoto?.opcode != Opcode.GOTO ||
        branchTargetAddress(instructions, setVisibilityIndex - 2) != setterAddress ||
        goneRegister?.opcode != Opcode.CONST_16 ||
        goneLiteral?.narrowLiteral != 8 ||
        goneRegister.registerA != visibleRegister.registerA ||
        setVisibility?.opcode != Opcode.INVOKE_VIRTUAL ||
        setVisibility.registerCount != 2 ||
        setVisibility.registerC != buttonResult.registerA ||
        setVisibility.registerD != visibleRegister.registerA ||
        setVisibilityReference?.definingClass != VIEW ||
        setVisibilityReference.name != "setVisibility" ||
        setVisibilityReference.parameterTypes != listOf("I") ||
        setVisibilityReference.returnType != VOID ||
        instructions.getOrNull(actionIndex)?.opcode != Opcode.SGET_OBJECT ||
        action?.definingClass != actionType ||
        action.name != "LINE_AI_EDIT_IMAGE" ||
        action.type != actionType ||
        instructions.getOrNull(rawIconIndex)?.opcode !in setOf(Opcode.CONST, Opcode.CONST_HIGH16) ||
        instructions.getOrNull(clickIndex)?.opcode != Opcode.NEW_INSTANCE ||
        booleanValueIndex >= buttonIdIndex ||
        buttonIdIndex >= setVisibilityIndex ||
        setVisibilityIndex >= actionIndex ||
        actionIndex >= rawIconIndex ||
        rawIconIndex >= clickIndex
    ) {
        return null
    }

    return GalleryViewerInjectionShape(
        insertionIndex = booleanValueIndex + 2,
        visibilityRegister = result.registerA,
    )
}

private fun recordLineAiGalleryViewerUnapplied(actualTargetCount: Int, reason: String) {
    patchStatusCollector.record(lineAiGalleryViewerUnappliedRecord(actualTargetCount, reason))
}

internal fun lineAiGalleryViewerUnappliedRecord(
    actualTargetCount: Int,
    reason: String,
): PatchStatusRecord = PatchStatusRecord(
    patchId = PatchId.LINE_AI_GALLERY_VIEWER,
    status = when {
        actualTargetCount > 1 -> PatchStatus.ERROR
        actualTargetCount == 0 -> PatchStatus.TARGET_NOT_FOUND
        else -> PatchStatus.ERROR
    },
    expectedTargetCount = 1,
    actualTargetCount = actualTargetCount,
    reason = reason,
)

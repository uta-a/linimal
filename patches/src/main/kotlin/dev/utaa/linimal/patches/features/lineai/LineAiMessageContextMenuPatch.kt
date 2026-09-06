package dev.utaa.linimal.patches.features.lineai

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.utaa.linimal.patches.features.agenti.agentIChatComposerPatch
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

private const val CONTEXT = "Landroid/content/Context;"
private const val LINE_AI_CONTEXT_SOURCE_VALUE = "chatroom_context_menu"
private const val LINE_AI_CONTEXT_HOOK =
    "Ldev/utaa/linimal/extension/features/lineai/LineAiMessageContextMenuHooks;->adjustAvailability(Z)Z"

/**
 * 26.11.0 で条件にしていた難読化型は 26.14.0 で以下のように変わりました。
 *
 * - context menu item enum: `Lj51/c;` → `Lc81/c;`
 * - context menu model enum: `Lne1/x0;` → `Lkh1/w0;`
 * - model → item mapper: `Lne1/g;` → `Lkh1/g;`
 * - LINE AI click callback: `Lne1/h0;` → `Lkh1/e0;`
 * - LINE AI entry source enum: `Lrq1/a;` → `Lzt1/a;`
 *
 * そのためどれも定数では持たず、難読化されない enum 定数名・resource・文字列から順に導出します。
 */

/** The source enum is independently anchored by its stable value and parameter string. */
private val lineAiContextEntrySourceFingerprint = Fingerprint(
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        string(LINE_AI_CONTEXT_SOURCE_VALUE),
        fieldAccess(
            name = "CONTEXT_MENU",
            opcode = Opcode.SPUT_OBJECT,
        ),
    ),
)

/** Callback attached to the resource-validated item must enter LINE AI from the context-menu source. */
private fun lineAiMessageContextCallbackFingerprint(entrySourceType: String) = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(OBJECT, OBJECT, OBJECT),
    filters = listOf(
        fieldAccess(
            definingClass = entrySourceType,
            name = "CONTEXT_MENU",
            type = entrySourceType,
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

/**
 * Concrete LINE_AI model: icon / label resource tuple, callback construction, and enum field are all required.
 * This validates the static item independently from the long-press supplier before any code is injected.
 */
private fun lineAiMessageContextModelFingerprint(callbackType: String) = Fingerprint(
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        newInstance(callbackType),
        literal(0x7f080635), // chat_ui_context_line_ai
        literal(0x7f15192e), // line_chat_button_lineai
        fieldAccess(
            name = "LINE_AI",
            opcode = Opcode.SPUT_OBJECT,
        ),
    ),
)

/** Mapping from the context action enum to the resource-validated model. */
private fun lineAiMessageContextMapperFingerprint(modelType: String) = Fingerprint(
    returnType = modelType,
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/lang/Enum;",
            name = "ordinal",
            returnType = "I",
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        fieldAccess(
            definingClass = modelType,
            name = "LINE_AI",
            type = modelType,
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

/**
 * The unique long-press supplier: `LINE_AI` enum object, context-menu source check, then the three availability
 * predicates and a direct return of that enum object. The target class / method name is not an identifier condition.
 */
private fun lineAiMessageContextSupplierFingerprint(itemType: String) = Fingerprint(
    returnType = itemType,
    // 第 2 / 第 3 引数は 26.11.0 が `Lv01/a;` / `Lj51/a;`、26.14.0 が `Ll31/a;` / `Lc81/a;`。
    parameters = listOf(CONTEXT, "L", "L", BOOLEAN),
    filters = listOf(
        fieldAccess(
            definingClass = itemType,
            name = "LINE_AI",
            type = itemType,
            opcode = Opcode.SGET_OBJECT,
        ),
        string(LINE_AI_CONTEXT_SOURCE_VALUE),
        methodCall(
            definingClass = "Ljava/util/List;",
            name = "contains",
            parameters = listOf(OBJECT),
            returnType = BOOLEAN,
            opcode = Opcode.INVOKE_INTERFACE,
        ),
    ),
)

/**
 * Suppresses only the LINE AI element while it is being supplied to a freshly constructed long-press menu.
 * It does not alter the general context menu renderer, callback implementation, telemetry, or LINE AI backend.
 */
val lineAiMessageContextMenuPatch = bytecodePatch(
    name = "メッセージ長押しメニューの LINE AI",
    description = "メッセージの長押しメニューにある LINE AI の項目を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(agentIChatComposerPatch)

    execute {
        // 難読化型は「entry source enum → click callback → model enum → mapper → item enum」の順に導出します。
        val sourceMatches = lineAiContextEntrySourceFingerprint.matchAllOrNull().orEmpty()
        if (sourceMatches.size != 1) {
            recordLineAiMessageContextMenuUnapplied(sourceMatches.size, "LineAiLongPressContextEntrySourceNotUnique")
            return@execute
        }
        val entrySourceType = sourceMatches.single().originalClassDef.type

        val callbackMatches = lineAiMessageContextCallbackFingerprint(entrySourceType).matchAllOrNull().orEmpty()
        if (callbackMatches.size != 1) {
            recordLineAiMessageContextMenuUnapplied(callbackMatches.size, "LineAiLongPressContextCallbackNotUnique")
            return@execute
        }
        val callbackType = callbackMatches.single().originalClassDef.type

        val modelMatches = lineAiMessageContextModelFingerprint(callbackType).matchAllOrNull().orEmpty()
        if (modelMatches.size != 1) {
            recordLineAiMessageContextMenuUnapplied(modelMatches.size, "LineAiLongPressContextModelNotUnique")
            return@execute
        }
        val modelType = modelMatches.single().originalClassDef.type

        val mapperMatches = lineAiMessageContextMapperFingerprint(modelType).matchAllOrNull().orEmpty()
        if (mapperMatches.size != 1) {
            recordLineAiMessageContextMenuUnapplied(mapperMatches.size, "LineAiLongPressContextMapperNotUnique")
            return@execute
        }
        // mapper の唯一の引数が context menu item enum です。
        val itemType = mapperMatches.single().originalMethod.parameterTypes.single().toString()

        val supplierMatches = lineAiMessageContextSupplierFingerprint(itemType).matchAllOrNull().orEmpty()
        if (supplierMatches.size != 1) {
            recordLineAiMessageContextMenuUnapplied(supplierMatches.size, "LineAiLongPressContextSupplierNotUnique")
            return@execute
        }

        val supplier = supplierMatches.single()
        val injectionShape = lineAiMessageContextSupplierInjectionShape(supplier, itemType)
        if (injectionShape == null) {
            patchStatusCollector.record(
                unsafeFeatureStatus(
                    patchId = PatchId.LINE_AI_MESSAGE_CONTEXT_MENU,
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "LineAiLongPressContextSupplierInstructionShapeMismatch",
                ),
            )
            return@execute
        }

        // This predicate is evaluated for every long press, before this specific LINE_AI enum is returned to the list.
        supplier.method.addInstructions(
            injectionShape.availabilityBranchIndex,
            """
                invoke-static { v${injectionShape.availabilityRegister} }, $LINE_AI_CONTEXT_HOOK
                move-result v${injectionShape.availabilityRegister}
            """.trimIndent(),
        )
        patchStatusCollector.record(
            patchId = PatchId.LINE_AI_MESSAGE_CONTEXT_MENU,
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "LineAiLongPressContextSupplierGuarded",
        )
    }
}

private data class MessageContextSupplierInjectionShape(
    val availabilityBranchIndex: Int,
    val availabilityRegister: Int,
)

/** Validates the exact operand flow before changing the first conjunct of the LINE_AI supply predicate. */
private fun lineAiMessageContextSupplierInjectionShape(
    match: Match,
    itemType: String,
): MessageContextSupplierInjectionShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions.toList()
    val lineAiFieldIndex = match.instructionMatches[0].index
    val entrySourceIndex = match.instructionMatches[1].index
    val entryContainsIndex = match.instructionMatches[2].index
    val lineAiFieldInstruction = instructions.getOrNull(lineAiFieldIndex) as? OneRegisterInstruction
    val lineAiField = (instructions.getOrNull(lineAiFieldIndex) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val entryContains = (instructions.getOrNull(entryContainsIndex) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val entryResult = instructions.getOrNull(entryContainsIndex + 1) as? OneRegisterInstruction
    val typeSetRead = (instructions.getOrNull(entryContainsIndex + 2) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val typeSetContains = (instructions.getOrNull(entryContainsIndex + 3) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val typeSetResult = instructions.getOrNull(entryContainsIndex + 4) as? OneRegisterInstruction
    val firstAvailabilityBranch = instructions.getOrNull(entryContainsIndex + 5) as? OneRegisterInstruction
    val sourceAvailabilityBranch = instructions.getOrNull(entryContainsIndex + 6) as? OneRegisterInstruction
    val typeAvailabilityBranch = instructions.getOrNull(entryContainsIndex + 7) as? OneRegisterInstruction
    val suppliedItemReturn = instructions.getOrNull(entryContainsIndex + 8) as? OneRegisterInstruction
    val nullItem = instructions.getOrNull(entryContainsIndex + 9) as? OneRegisterInstruction
    val nullItemLiteral = instructions.getOrNull(entryContainsIndex + 9) as? NarrowLiteralInstruction
    val nullItemReturn = instructions.getOrNull(entryContainsIndex + 10) as? OneRegisterInstruction
    val nullItemAddress = instructionAddress(instructions, entryContainsIndex + 9)

    if (
        instructions.getOrNull(lineAiFieldIndex)?.opcode != Opcode.SGET_OBJECT ||
        lineAiField?.definingClass != itemType ||
        lineAiField.name != "LINE_AI" ||
        lineAiField.type != itemType ||
        lineAiFieldIndex >= entrySourceIndex ||
        instructions.getOrNull(entrySourceIndex)?.opcode != Opcode.CONST_STRING ||
        instructions.getOrNull(entryContainsIndex)?.opcode != Opcode.INVOKE_INTERFACE ||
        entryContains?.definingClass != "Ljava/util/List;" ||
        entryContains.name != "contains" ||
        entryContains.parameterTypes != listOf(OBJECT) ||
        entryContains.returnType != BOOLEAN ||
        entryResult?.opcode != Opcode.MOVE_RESULT ||
        instructions.getOrNull(entryContainsIndex + 2)?.opcode != Opcode.SGET_OBJECT ||
        typeSetRead?.type != "Ljava/util/Set;" ||
        instructions.getOrNull(entryContainsIndex + 3)?.opcode != Opcode.INVOKE_INTERFACE ||
        typeSetContains?.definingClass != "Ljava/util/Set;" ||
        typeSetContains.name != "contains" ||
        typeSetContains.parameterTypes != listOf(OBJECT) ||
        typeSetContains.returnType != BOOLEAN ||
        typeSetResult?.opcode != Opcode.MOVE_RESULT ||
        firstAvailabilityBranch?.opcode != Opcode.IF_EQZ ||
        firstAvailabilityBranch.registerA !in 0..15 ||
        sourceAvailabilityBranch?.opcode != Opcode.IF_EQZ ||
        sourceAvailabilityBranch.registerA != entryResult.registerA ||
        typeAvailabilityBranch?.opcode != Opcode.IF_EQZ ||
        typeAvailabilityBranch.registerA != typeSetResult.registerA ||
        branchTargetAddress(instructions, entryContainsIndex + 5) != nullItemAddress ||
        branchTargetAddress(instructions, entryContainsIndex + 6) != nullItemAddress ||
        branchTargetAddress(instructions, entryContainsIndex + 7) != nullItemAddress ||
        suppliedItemReturn?.opcode != Opcode.RETURN_OBJECT ||
        suppliedItemReturn.registerA != lineAiFieldInstruction?.registerA ||
        nullItem?.opcode != Opcode.CONST_4 ||
        nullItemLiteral?.narrowLiteral != 0 ||
        nullItemReturn?.opcode != Opcode.RETURN_OBJECT ||
        nullItemReturn.registerA != nullItem.registerA
    ) {
        return null
    }

    return MessageContextSupplierInjectionShape(
        availabilityBranchIndex = entryContainsIndex + 5,
        availabilityRegister = firstAvailabilityBranch.registerA,
    )
}

private fun recordLineAiMessageContextMenuUnapplied(actualTargetCount: Int, reason: String) {
    patchStatusCollector.record(lineAiMessageContextMenuUnappliedRecord(actualTargetCount, reason))
}

internal fun lineAiMessageContextMenuUnappliedRecord(
    actualTargetCount: Int,
    reason: String,
): PatchStatusRecord = PatchStatusRecord(
    patchId = PatchId.LINE_AI_MESSAGE_CONTEXT_MENU,
    status = when {
        actualTargetCount > 1 -> PatchStatus.ERROR
        actualTargetCount == 0 -> PatchStatus.TARGET_NOT_FOUND
        else -> PatchStatus.ERROR
    },
    expectedTargetCount = 1,
    actualTargetCount = actualTargetCount,
    reason = reason,
)

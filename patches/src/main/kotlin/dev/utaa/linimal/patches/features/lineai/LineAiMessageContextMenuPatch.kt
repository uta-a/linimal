package dev.utaa.linimal.patches.features.lineai

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
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
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.unsafeFeatureStatus
import dev.utaa.linimal.patches.util.branchTargetAddress
import dev.utaa.linimal.patches.util.instructionAddress

private const val VOID = "V"
private const val OBJECT = "Ljava/lang/Object;"
private const val BOOLEAN = "Z"
private const val CONTEXT = "Landroid/content/Context;"
private const val CONTEXT_MENU_ITEM = "Lj51/c;"
private const val CONTEXT_MENU_MODEL = "Lne1/x0;"
private const val CONTEXT_MENU_MAPPER = "Lne1/g;"
private const val CONTEXT_MENU_CALLBACK = "Lne1/h0;"
private const val LINE_AI_ENTRY_SOURCE = "Lrq1/a;"
private const val LINE_AI_CONTEXT_SOURCE_VALUE = "chatroom_context_menu"
private const val LINE_AI_CONTEXT_HOOK =
    "Ldev/utaa/linimal/extension/features/lineai/LineAiMessageContextMenuHooks;->adjustAvailability(Z)Z"

/**
 * Concrete LINE_AI model: icon / label resource tuple, callback construction, and enum field are all required.
 * This validates the static item independently from the long-press supplier before any code is injected.
 */
private val lineAiMessageContextModelFingerprint = Fingerprint(
    definingClass = CONTEXT_MENU_MODEL,
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        newInstance(CONTEXT_MENU_CALLBACK),
        literal(0x7f08064a), // chat_ui_context_line_ai
        literal(0x7f151848), // line_chat_button_lineai
        fieldAccess(
            definingClass = CONTEXT_MENU_MODEL,
            name = "LINE_AI",
            type = CONTEXT_MENU_MODEL,
            opcode = Opcode.SPUT_OBJECT,
        ),
    ),
)

/** Mapping from the context action enum to the resource-validated model. */
private val lineAiMessageContextMapperFingerprint = Fingerprint(
    definingClass = CONTEXT_MENU_MAPPER,
    name = "d",
    returnType = CONTEXT_MENU_MODEL,
    parameters = listOf(CONTEXT_MENU_ITEM),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/lang/Enum;",
            name = "ordinal",
            returnType = "I",
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        fieldAccess(
            definingClass = CONTEXT_MENU_MODEL,
            name = "LINE_AI",
            type = CONTEXT_MENU_MODEL,
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

/** Callback attached to the resource-validated item must enter LINE AI from the context-menu source. */
private val lineAiMessageContextCallbackFingerprint = Fingerprint(
    definingClass = CONTEXT_MENU_CALLBACK,
    returnType = OBJECT,
    parameters = listOf(OBJECT, OBJECT, OBJECT),
    filters = listOf(
        fieldAccess(
            definingClass = LINE_AI_ENTRY_SOURCE,
            name = "CONTEXT_MENU",
            type = LINE_AI_ENTRY_SOURCE,
            opcode = Opcode.SGET_OBJECT,
        ),
    ),
)

/** The source enum is independently anchored by its stable value and parameter string. */
private val lineAiContextEntrySourceFingerprint = Fingerprint(
    definingClass = LINE_AI_ENTRY_SOURCE,
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        string(LINE_AI_CONTEXT_SOURCE_VALUE),
        fieldAccess(
            definingClass = LINE_AI_ENTRY_SOURCE,
            name = "CONTEXT_MENU",
            type = LINE_AI_ENTRY_SOURCE,
            opcode = Opcode.SPUT_OBJECT,
        ),
    ),
)

/**
 * The unique long-press supplier: `LINE_AI` enum object, context-menu source check, then the three availability
 * predicates and a direct return of that enum object. The target class / method name is not an identifier condition.
 */
private val lineAiMessageContextSupplierFingerprint = Fingerprint(
    returnType = CONTEXT_MENU_ITEM,
    parameters = listOf(CONTEXT, "Lv01/a;", "Lj51/a;", BOOLEAN),
    filters = listOf(
        fieldAccess(
            definingClass = CONTEXT_MENU_ITEM,
            name = "LINE_AI",
            type = CONTEXT_MENU_ITEM,
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
val lineAiMessageContextMenuPatch = bytecodePatch {
    dependsOn(agentIChatComposerPatch)

    execute {
        val modelMatches = lineAiMessageContextModelFingerprint.matchAllOrNull().orEmpty()
        val mapperMatches = lineAiMessageContextMapperFingerprint.matchAllOrNull().orEmpty()
        val callbackMatches = lineAiMessageContextCallbackFingerprint.matchAllOrNull().orEmpty()
        val sourceMatches = lineAiContextEntrySourceFingerprint.matchAllOrNull().orEmpty()
        val supplierMatches = lineAiMessageContextSupplierFingerprint.matchAllOrNull().orEmpty()

        val invalidCount = listOf(
            modelMatches.size to "LineAiLongPressContextModelNotUnique",
            mapperMatches.size to "LineAiLongPressContextMapperNotUnique",
            callbackMatches.size to "LineAiLongPressContextCallbackNotUnique",
            sourceMatches.size to "LineAiLongPressContextEntrySourceNotUnique",
            supplierMatches.size to "LineAiLongPressContextSupplierNotUnique",
        ).firstOrNull { (count, _) -> count != 1 }
        if (invalidCount != null) {
            recordLineAiMessageContextMenuUnapplied(invalidCount.first, invalidCount.second)
            return@execute
        }

        val supplier = supplierMatches.single()
        val injectionShape = lineAiMessageContextSupplierInjectionShape(supplier)
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
private fun lineAiMessageContextSupplierInjectionShape(match: Match): MessageContextSupplierInjectionShape? {
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
        lineAiField?.definingClass != CONTEXT_MENU_ITEM ||
        lineAiField.name != "LINE_AI" ||
        lineAiField.type != CONTEXT_MENU_ITEM ||
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

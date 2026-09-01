package dev.utaa.linimal.patches.features.agenti

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val VOID = "V"
private const val BOOLEAN = "Z"
private const val OBJECT = "Ljava/lang/Object;"
private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
private const val VIEW = "Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"
private const val IMAGE_VIEW = "Landroid/widget/ImageView;"
private const val COMPOSE_VIEW = "Landroidx/compose/ui/platform/ComposeView;"
private const val VIEW_BINDING_FIND_VIEW = "Lyd/b;"
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/b0;"
private const val DEBUG_METADATA = "Llb8/e;"
private const val AI_TALK_CONTROLLER =
    "Lcom/linecorp/line/chat/ui/impl/message/input/aitalksuggestion/a;"
private const val AI_TALK_CONTROLLER_SOURCE = "AiTalkSuggestionInputViewController.kt"
private const val MESSAGE_INPUT_SOURCE = "MessageInputViewControllerImpl.kt"
private const val OBSERVE_AI_TALK_SOURCE =
    "MessageInputViewControllerImpl\$observeViewModelForAiTalkSuggestionFeature\$2"
private const val COMPOSER_BUTTON = 0x7f0b0760
private const val AI_TALK_SUGGESTION_CHIP_BAR = 0x7f0b0676
private const val AI_TALK_INPUT_LAYOUT = 0x7f0e0134
private const val CONTROLLER_IMAGE_VIEW_PARAMETER_INDEX = 8
private const val CONTROLLER_CHIP_BAR_PARAMETER_INDEX = 2

/** composer button の visibility gate と、入力欄下 chip bar の supply gate の 2 箇所。 */
private const val EXPECTED_TARGET_COUNT = 2
private const val AGENT_I_CHAT_COMPOSER_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentIChatComposerHooks;" +
        "->adjustComposerButtonVisibility(Z)Z"
private const val AGENT_I_CHAT_COMPOSER_CHIP_BAR_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentIChatComposerHooks;" +
        "->adjustAiTalkSuggestionChipBar(Ljava/lang/Object;)Ljava/lang/Object;"

private val aiTalkControllerConstructorParameters = listOf(
    FRAGMENT_ACTIVITY,
    VIEW_STUB,
    "Lif1/b;",
    "Lxf1/f;",
    "Lx51/b;",
    "Lai1/b;",
    "Ljava/lang/String;",
    "Lv01/c;",
    IMAGE_VIEW,
    "Laf1/m1;",
)

/**
 * Message input binding の `chat_ui_input_ai_talk_suggestion_button` を resource literal から特定します。
 * `gs1/i0` などの難読化名には依存しません。
 */
private val composerButtonBindingFingerprint = Fingerprint(
    returnType = null,
    parameters = listOf(VIEW),
    filters = listOf(
        literal(COMPOSER_BUTTON),
        methodCall(
            definingClass = VIEW_BINDING_FIND_VIEW,
            name = "a",
            parameters = listOf(VIEW, "I"),
            returnType = VIEW,
            opcode = Opcode.INVOKE_STATIC,
        ),
    ),
    custom = { method, classDef ->
        method.returnType == classDef.type &&
            classDef.interfaces.contains("Lyd/a;") &&
            method.implementation != null
    },
)

/**
 * MessageInputViewControllerImpl の AI Talk observer coroutine。source metadata から親 controller を導き、
 * generic な message-input binding を AI Talk surface と取り違えないために使用します。
 */
private val messageInputAiTalkSourceFingerprint = Fingerprint(
    name = "invokeSuspend",
    returnType = OBJECT,
    parameters = listOf(OBJECT),
    custom = { _, classDef ->
        hasDebugMetadata(
            classDef,
            sourceFile = MESSAGE_INPUT_SOURCE,
            classNameContains = OBSERVE_AI_TALK_SOURCE,
        )
    },
)

/**
 * stable owner の ViewStub layout-resource → inflate sequence。ここでは注入せず、composer button state
 * observer が同じ AI Talk input surface に確実に接続されていることを検証します。
 */
private val aiTalkInflationControllerFingerprint = Fingerprint(
    definingClass = AI_TALK_CONTROLLER,
    name = "<init>",
    returnType = VOID,
    filters = listOf(
        literal(AI_TALK_INPUT_LAYOUT),
        methodCall(
            definingClass = VIEW_STUB,
            name = "setLayoutResource",
            parameters = listOf("I"),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        methodCall(
            definingClass = VIEW_STUB,
            name = "inflate",
            parameters = emptyList(),
            returnType = VIEW,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
    custom = { method, _ ->
        method.parameterTypes.map(CharSequence::toString) == aiTalkControllerConstructorParameters
    },
)

/**
 * resource-bound composer ImageView を stable AI Talk controller constructor の ImageView parameter へ渡す
 * MessageInputViewControllerImpl の controller-construction path。binding / source metadata / controller owner を一つの経路として
 * 確認し、どれか一つでも一意でなければ注入しません。
 */
private fun composerButtonWiringFingerprint(parentType: String, bindingType: String) = Fingerprint(
    definingClass = parentType,
    returnType = VOID,
    filters = listOf(
        fieldAccess(
            definingClass = bindingType,
            type = IMAGE_VIEW,
            opcode = Opcode.IGET_OBJECT,
        ),
        methodCall(
            definingClass = AI_TALK_CONTROLLER,
            name = "<init>",
            parameters = aiTalkControllerConstructorParameters,
            returnType = VOID,
            opcode = Opcode.INVOKE_DIRECT_RANGE,
        ),
    ),
    custom = { _, _ -> true },
)

/**
 * ViewModel の visibility state を consumer へ反映する唯一の observer。stable owner field、source metadata、
 * VISIBLE/GONE command shape を合わせるため、`bf1/y` の難読化名を識別条件にしません。
 */
private val composerButtonVisibilityObserverFingerprint = Fingerprint(
    name = "invokeSuspend",
    returnType = OBJECT,
    parameters = listOf(OBJECT),
    filters = listOf(
        fieldAccess(
            definingClass = AI_TALK_CONTROLLER,
            name = "h",
            type = IMAGE_VIEW,
            opcode = Opcode.IGET_OBJECT,
        ),
        methodCall(
            definingClass = VIEW,
            name = "setVisibility",
            parameters = listOf("I"),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
    custom = { _, classDef -> hasDebugMetadata(classDef, AI_TALK_CONTROLLER_SOURCE) },
)

/**
 * chip bar view を保持する binding field を読み出す accessor。field 名は resource literal から逆引きするため、
 * `w31/j` などの難読化名も accessor が属する interface 名も識別条件にしません。
 */
private fun aiTalkSuggestionChipBarAccessorFingerprint(bindingType: String, fieldName: String) = Fingerprint(
    returnType = COMPOSE_VIEW,
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(
            definingClass = bindingType,
            name = fieldName,
            type = COMPOSE_VIEW,
            opcode = Opcode.IGET_OBJECT,
        ),
    ),
    custom = { method, _ -> method.implementation != null },
)

/**
 * Agent i in chat の 2 つの surface にだけ runtime gate を置きます。
 *
 * 1 つ目は composer button の visibility state です。button を一度だけ GONE にするのではなく、AI Talk
 * ViewModel の state observer が再入場、orientation、keyboard/resume 後に visibility を供給するたびに値を
 * 調整します。2 つ目は入力欄の下に並ぶ chip bar (`chat_ui_ai_talk_suggestion_chip_bar`) です。LINE 自身が
 * chip bar 無効構成で使う null 供給と同じ経路を通し、controller へ渡す前に view を取り除きます。
 *
 * text input、gallery、camera、attach menu はいずれの経路の対象外であり、AI Talk の
 * subscription/backend/settings/network は変更しません。
 */
val agentIChatComposerPatch = bytecodePatch {
    dependsOn(agentISettingsPatch)

    execute {
        val bindingMatches = composerButtonBindingFingerprint.matchAllOrNull().orEmpty()
        if (bindingMatches.size != 1) {
            recordUnappliedStatus(bindingMatches.size, "AgentIChatComposerBindingNotUnique")
            return@execute
        }
        val binding = bindingMatches.single()
        val bindingType = binding.originalClassDef.type

        val sourceMatches = messageInputAiTalkSourceFingerprint.matchAllOrNull().orEmpty()
        if (sourceMatches.size != 1) {
            recordUnappliedStatus(sourceMatches.size, "AgentIChatComposerSourceMetadataNotUnique")
            return@execute
        }
        val sourceParentTypes = sourceMatches.single().originalClassDef.fields
            .map { it.type }
            .filter { it.startsWith("L") }
            .toSet()
        if (sourceParentTypes.size != 1) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_CHAT_COMPOSER),
                expectedTargetCount = EXPECTED_TARGET_COUNT,
                actualTargetCount = EXPECTED_TARGET_COUNT,
                reason = "AgentIChatComposerSourceParentShapeMismatch",
            )
            return@execute
        }

        val wiringMatches = composerButtonWiringFingerprint(sourceParentTypes.single(), bindingType)
            .matchAllOrNull()
            .orEmpty()
        if (wiringMatches.size != 1) {
            recordUnappliedStatus(wiringMatches.size, "AgentIChatComposerWiringNotUnique")
            return@execute
        }
        val wiring = wiringMatches.single()

        val inflationMatches = aiTalkInflationControllerFingerprint.matchAllOrNull().orEmpty()
        if (inflationMatches.size != 1) {
            recordUnappliedStatus(inflationMatches.size, "AgentIChatComposerInflationControllerNotUnique")
            return@execute
        }

        val observerMatches = composerButtonVisibilityObserverFingerprint.matchAllOrNull().orEmpty()
        if (observerMatches.size != 1) {
            recordUnappliedStatus(observerMatches.size, "AgentIChatComposerVisibilityObserverNotUnique")
            return@execute
        }
        val observer = observerMatches.single()

        val gateShape = composerButtonVisibilityGateShape(
            observer.method,
            observer.originalClassDef.type,
        )
        if (
            !aiTalkInflationShape(inflationMatches.single().method) ||
            !composerButtonWiringShape(wiring.method, bindingType) ||
            gateShape == null
        ) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_CHAT_COMPOSER),
                expectedTargetCount = EXPECTED_TARGET_COUNT,
                actualTargetCount = EXPECTED_TARGET_COUNT,
                reason = "AgentIChatComposerInstructionShapeMismatch",
            )
            return@execute
        }

        // chip bar は composer button と独立した optional target です。resource literal から binding field を
        // 逆引きし、その field を読む accessor と supply shape が一意なときにだけ gate を追加します。
        val chipBarField = aiTalkSuggestionChipBarField(binding.originalMethod, binding.originalClassDef)
        val chipBarAccessor = chipBarField
            ?.let { aiTalkSuggestionChipBarAccessorFingerprint(bindingType, it).matchAllOrNull().orEmpty() }
            ?.singleOrNull()
        val chipBarGateShape = chipBarAccessor?.let { accessor ->
            aiTalkSuggestionChipBarGateShape(
                wiring.method,
                accessor.originalMethod.name,
                accessor.originalClassDef.interfaces.toSet(),
            )
        }

        observer.method.addInstructions(
            gateShape.insertionIndex,
            """
                invoke-static { v${gateShape.visibilityRegister} }, $AGENT_I_CHAT_COMPOSER_HOOK
                move-result v${gateShape.visibilityRegister}
            """.trimIndent(),
        )
        if (chipBarGateShape != null) {
            wiring.method.addInstructions(
                chipBarGateShape.insertionIndex,
                """
                    invoke-static { v${chipBarGateShape.chipBarRegister} }, $AGENT_I_CHAT_COMPOSER_CHIP_BAR_HOOK
                    move-result-object v${chipBarGateShape.chipBarRegister}
                    check-cast v${chipBarGateShape.chipBarRegister}, $COMPOSE_VIEW
                """.trimIndent(),
            )
        }
        patchStatusCollector.record(
            patchId = PatchId.AGENT_I_CHAT_COMPOSER,
            expectedTargetCount = EXPECTED_TARGET_COUNT,
            actualTargetCount = if (chipBarGateShape == null) 1 else EXPECTED_TARGET_COUNT,
            reason = if (chipBarGateShape == null) {
                "AgentIChatComposerChipBarTargetNotFound"
            } else {
                "AgentIChatComposerVisibilityGuarded"
            },
        )
    }
}

private data class ComposerVisibilityGateShape(
    val insertionIndex: Int,
    val visibilityRegister: Int,
)

private data class ChipBarGateShape(
    val insertionIndex: Int,
    val chipBarRegister: Int,
)

private fun hasDebugMetadata(
    classDef: ClassDef,
    sourceFile: String,
    classNameContains: String? = null,
): Boolean = classDef.annotations.any { annotation ->
    if (annotation.type != DEBUG_METADATA) {
        false
    } else {
        val metadataSource = annotation.elements.firstOrNull { it.name == "f" }
            ?.value
            .let { it as? StringEncodedValue }
            ?.value
        val metadataClass = annotation.elements.firstOrNull { it.name == "c" }
            ?.value
            .let { it as? StringEncodedValue }
            ?.value
        metadataSource == sourceFile &&
            (classNameContains == null || metadataClass?.contains(classNameContains) == true)
    }
}

private fun aiTalkInflationShape(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    val setLayoutIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                methodMatches(reference, VIEW_STUB, "setLayoutResource", listOf("I"), VOID)
        } == true
    }
    val inflateIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                methodMatches(reference, VIEW_STUB, "inflate", emptyList(), VIEW)
        } == true
    }
    val setLayout = instructions.getOrNull(setLayoutIndex) as? FiveRegisterInstruction ?: return false
    val inflate = instructions.getOrNull(inflateIndex) as? FiveRegisterInstruction ?: return false
    return setLayoutIndex >= 1 &&
        inflateIndex == setLayoutIndex + 1 &&
        setLayout.registerCount == 2 &&
        inflate.registerCount == 1 &&
        inflate.registerC == setLayout.registerC
}

private fun composerButtonWiringShape(method: Method, bindingType: String): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    val controllerCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                methodMatches(
                    reference,
                    AI_TALK_CONTROLLER,
                    "<init>",
                    aiTalkControllerConstructorParameters,
                    VOID,
                )
        } == true
    }
    val controllerCall = instructions.getOrNull(controllerCallIndex) as? RegisterRangeInstruction ?: return false
    if (controllerCall.registerCount != aiTalkControllerConstructorParameters.size + 1) return false

    val imageBindingReadIndex = instructions.indexOfFirst { instruction ->
        fieldReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                reference.definingClass == bindingType &&
                reference.type == IMAGE_VIEW
        } == true
    }
    val imageBindingRead = instructions.getOrNull(imageBindingReadIndex) as? TwoRegisterInstruction ?: return false
    val imageArgumentRegister = controllerCall.startRegister + CONTROLLER_IMAGE_VIEW_PARAMETER_INDEX + 1
    val imageArgumentIsBound = instructions
        .subList((imageBindingReadIndex + 1).coerceAtLeast(0), controllerCallIndex)
        .any { instruction ->
            val move = instruction as? TwoRegisterInstruction
            (instruction.opcode == Opcode.MOVE_OBJECT || instruction.opcode == Opcode.MOVE_OBJECT_FROM16) &&
                move != null &&
                move.registerA == imageArgumentRegister &&
                move.registerB == imageBindingRead.registerA
        }

    return imageBindingReadIndex >= 0 &&
        imageBindingReadIndex < controllerCallIndex &&
        imageArgumentIsBound
}

private fun composerButtonVisibilityGateShape(
    method: Method,
    observerType: String,
): ComposerVisibilityGateShape? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val imageReadIndex = instructions.indexOfFirst { instruction ->
        fieldReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                reference.definingClass == AI_TALK_CONTROLLER &&
                reference.name == "h" &&
                reference.type == IMAGE_VIEW
        } == true
    }
    val visibilityReadIndex = instructions.indexOfFirst { instruction ->
        fieldReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.IGET_BOOLEAN &&
                reference.definingClass == observerType &&
                reference.type == BOOLEAN
        } == true
    }
    val imageRead = instructions.getOrNull(imageReadIndex) as? TwoRegisterInstruction ?: return null
    val visibilityRead = instructions.getOrNull(visibilityReadIndex) as? TwoRegisterInstruction ?: return null
    // Coroutine の resume state dispatch を通過してから boolean を branch します。boolean register が
    // state dispatch 中に上書きされず、そのまま VISIBLE/GONE の input になる command shape を確認します。
    val conditionalIndex = visibilityReadIndex + 13
    val conditional = instructions.getOrNull(conditionalIndex) as? OneRegisterInstruction ?: return null
    val visibleLiteral = instructions.getOrNull(conditionalIndex + 1) as? OneRegisterInstruction ?: return null
    val goneLiteral = instructions.getOrNull(conditionalIndex + 3) as? OneRegisterInstruction ?: return null
    val setVisibilityIndex = conditionalIndex + 4
    val setVisibility = instructions.getOrNull(setVisibilityIndex) as? FiveRegisterInstruction ?: return null
    val setVisibilityReference = methodReference(instructions.getOrNull(setVisibilityIndex)) ?: return null

    if (
        imageReadIndex != 1 ||
        visibilityReadIndex != imageReadIndex + 1 ||
        imageRead.registerA !in 0..15 ||
        visibilityRead.registerA !in 0..15 ||
        instructions.getOrNull(visibilityReadIndex + 1)?.opcode != Opcode.SGET_OBJECT ||
        instructions.getOrNull(visibilityReadIndex + 2)?.opcode != Opcode.IGET ||
        instructions.getOrNull(visibilityReadIndex + 3)?.opcode != Opcode.CONST_4 ||
        instructions.getOrNull(visibilityReadIndex + 4)?.opcode != Opcode.IF_EQZ ||
        instructions.getOrNull(visibilityReadIndex + 5)?.opcode != Opcode.IF_NE ||
        instructions.getOrNull(visibilityReadIndex + 6)?.opcode != Opcode.INVOKE_STATIC ||
        instructions.getOrNull(visibilityReadIndex + 7)?.opcode != Opcode.GOTO ||
        instructions.getOrNull(visibilityReadIndex + 8)?.opcode != Opcode.CONST_STRING ||
        instructions.getOrNull(visibilityReadIndex + 9)?.opcode != Opcode.INVOKE_STATIC ||
        instructions.getOrNull(visibilityReadIndex + 10)?.opcode != Opcode.CONST_4 ||
        instructions.getOrNull(visibilityReadIndex + 11)?.opcode != Opcode.RETURN_OBJECT ||
        instructions.getOrNull(visibilityReadIndex + 12)?.opcode != Opcode.INVOKE_STATIC ||
        conditional.opcode != Opcode.IF_EQZ ||
        conditional.registerA != visibilityRead.registerA ||
        visibleLiteral.opcode != Opcode.CONST_4 ||
        visibleLiteral.registerA != setVisibility.registerD ||
        goneLiteral.opcode != Opcode.CONST_16 ||
        goneLiteral.registerA != setVisibility.registerD ||
        setVisibility.opcode != Opcode.INVOKE_VIRTUAL ||
        !methodMatches(setVisibilityReference, VIEW, "setVisibility", listOf("I"), VOID) ||
        setVisibility.registerCount != 2 ||
        setVisibility.registerC != imageRead.registerA ||
        instructions.getOrNull(conditionalIndex + 2)?.opcode != Opcode.GOTO
    ) {
        return null
    }
    return ComposerVisibilityGateShape(
        insertionIndex = visibilityReadIndex + 1,
        visibilityRegister = visibilityRead.registerA,
    )
}

/**
 * bind method 内の `chat_ui_ai_talk_suggestion_chip_bar` literal から、chip bar view を格納する binding field 名を
 * 逆引きします。literal → findChildViewById → check-cast → binding constructor argument → iput の連鎖を
 * すべて確認できたときだけ field 名を返します。
 */
private fun aiTalkSuggestionChipBarField(bindMethod: Method, bindingClass: ClassDef): String? {
    val instructions = bindMethod.implementation?.instructions?.toList() ?: return null
    val literalIndex = instructions
        .mapIndexedNotNull { index, instruction ->
            index.takeIf {
                (instruction as? WideLiteralInstruction)?.wideLiteral == AI_TALK_SUGGESTION_CHIP_BAR.toLong()
            }
        }
        .singleOrNull() ?: return null
    val literalRegister = (instructions[literalIndex] as? OneRegisterInstruction)?.registerA ?: return null

    val findViewCall = instructions.getOrNull(literalIndex + 1) as? FiveRegisterInstruction ?: return null
    val findViewReference = methodReference(instructions.getOrNull(literalIndex + 1)) ?: return null
    if (
        instructions[literalIndex + 1].opcode != Opcode.INVOKE_STATIC ||
        !methodMatches(findViewReference, VIEW_BINDING_FIND_VIEW, "a", listOf(VIEW, "I"), VIEW) ||
        findViewCall.registerCount != 2 ||
        findViewCall.registerD != literalRegister
    ) {
        return null
    }

    val resolved = instructions.getOrNull(literalIndex + 2) as? OneRegisterInstruction ?: return null
    if (instructions[literalIndex + 2].opcode != Opcode.MOVE_RESULT_OBJECT) return null

    // resolve 結果はそのまま、あるいは一度だけ別 register へ移してから check-cast されます。
    var castIndex = literalIndex + 3
    var chipBarRegister = resolved.registerA
    val relay = instructions.getOrNull(castIndex) as? TwoRegisterInstruction
    if (
        relay != null &&
        (
            instructions[castIndex].opcode == Opcode.MOVE_OBJECT ||
                instructions[castIndex].opcode == Opcode.MOVE_OBJECT_FROM16
            ) &&
        relay.registerB == chipBarRegister
    ) {
        chipBarRegister = relay.registerA
        castIndex += 1
    }
    val cast = instructions.getOrNull(castIndex) as? OneRegisterInstruction ?: return null
    val castType = (instructions[castIndex] as? ReferenceInstruction)?.reference as? TypeReference
    if (
        instructions[castIndex].opcode != Opcode.CHECK_CAST ||
        cast.registerA != chipBarRegister ||
        castType?.type != COMPOSE_VIEW
    ) {
        return null
    }

    val parameterIndex = bindingConstructorParameterIndex(instructions, bindingClass.type, chipBarRegister)
        ?: return null
    return bindingComposeViewFieldName(bindingClass, parameterIndex)
}

/** bind method 終端の binding constructor 呼び出しで、指定 register が渡される parameter index を返します。 */
private fun bindingConstructorParameterIndex(
    instructions: List<Instruction>,
    bindingType: String,
    valueRegister: Int,
): Int? {
    val constructorCall = instructions.firstOrNull { instruction ->
        methodReference(instruction)?.let { reference ->
            (
                instruction.opcode == Opcode.INVOKE_DIRECT_RANGE ||
                    instruction.opcode == Opcode.INVOKE_DIRECT
                ) &&
                reference.definingClass == bindingType &&
                reference.name == "<init>"
        } == true
    } ?: return null
    val constructorReference = methodReference(constructorCall) ?: return null
    val argumentRegisters = when (constructorCall) {
        is RegisterRangeInstruction ->
            (0 until constructorCall.registerCount).map { constructorCall.startRegister + it }
        is FiveRegisterInstruction -> listOf(
            constructorCall.registerC,
            constructorCall.registerD,
            constructorCall.registerE,
            constructorCall.registerF,
            constructorCall.registerG,
        ).take(constructorCall.registerCount)
        else -> return null
    }
    if (argumentRegisters.size != constructorReference.parameterTypes.size + 1) return null
    return argumentRegisters.drop(1)
        .mapIndexedNotNull { index, register -> index.takeIf { register == valueRegister } }
        .singleOrNull()
}

/** binding constructor の parameter index を、同じ値を保存する ComposeView field 名へ写します。 */
private fun bindingComposeViewFieldName(bindingClass: ClassDef, parameterIndex: Int): String? {
    val initMethod = bindingClass.directMethods.singleOrNull { it.name == "<init>" } ?: return null
    val implementation = initMethod.implementation ?: return null
    val instanceRegister = implementation.registerCount - (initMethod.parameterTypes.size + 1)
    if (instanceRegister < 0) return null
    val parameterRegister = instanceRegister + 1 + parameterIndex
    return implementation.instructions
        .mapNotNull { instruction ->
            val store = instruction as? TwoRegisterInstruction ?: return@mapNotNull null
            val reference = fieldReference(instruction) ?: return@mapNotNull null
            reference.name.takeIf {
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    store.registerA == parameterRegister &&
                    store.registerB == instanceRegister &&
                    reference.definingClass == bindingClass.type &&
                    reference.type == COMPOSE_VIEW
            }
        }
        .singleOrNull()
}

/**
 * controller-construction path で chip bar view が presenter へ渡される直前の位置を返します。
 *
 * accessor 呼び出し → null 判定 → presenter 生成 → controller argument への move という LINE 自身の shape を
 * すべて確認します。null 判定は元から存在し、chip bar が無効な構成では LINE 自身が同じ分岐で null を
 * 供給するため、hook が null を返しても未知の状態を作りません。
 */
private fun aiTalkSuggestionChipBarGateShape(
    method: Method,
    accessorName: String,
    accessorInterfaces: Set<String>,
): ChipBarGateShape? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val controllerCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                methodMatches(
                    reference,
                    AI_TALK_CONTROLLER,
                    "<init>",
                    aiTalkControllerConstructorParameters,
                    VOID,
                )
        } == true
    }
    val controllerCall = instructions.getOrNull(controllerCallIndex) as? RegisterRangeInstruction ?: return null
    if (controllerCall.registerCount != aiTalkControllerConstructorParameters.size + 1) return null

    val chipBarPresenterType = aiTalkControllerConstructorParameters[CONTROLLER_CHIP_BAR_PARAMETER_INDEX]
    val chipBarArgumentRegister = controllerCall.startRegister + CONTROLLER_CHIP_BAR_PARAMETER_INDEX + 1

    val candidates = instructions.indices.filter { index ->
        val accessorCall = instructions[index] as? FiveRegisterInstruction ?: return@filter false
        val accessorReference = methodReference(instructions[index]) ?: return@filter false
        if (
            instructions[index].opcode != Opcode.INVOKE_INTERFACE ||
            accessorCall.registerCount != 1 ||
            accessorReference.definingClass !in accessorInterfaces ||
            accessorReference.name != accessorName ||
            accessorReference.parameterTypes.isNotEmpty() ||
            accessorReference.returnType != COMPOSE_VIEW
        ) {
            return@filter false
        }

        val resolved = instructions.getOrNull(index + 1) as? OneRegisterInstruction ?: return@filter false
        if (
            instructions[index + 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
            resolved.registerA !in 0..15
        ) {
            return@filter false
        }

        val nullCheck = instructions.getOrNull(index + 2) as? OneRegisterInstruction ?: return@filter false
        if (
            instructions[index + 2].opcode != Opcode.IF_EQZ ||
            nullCheck.registerA != resolved.registerA
        ) {
            return@filter false
        }

        val presenter = instructions.getOrNull(index + 3) as? OneRegisterInstruction ?: return@filter false
        val presenterType = (instructions[index + 3] as? ReferenceInstruction)?.reference as? TypeReference
        if (
            instructions[index + 3].opcode != Opcode.NEW_INSTANCE ||
            presenterType?.type != chipBarPresenterType
        ) {
            return@filter false
        }

        val presenterInit = instructions.getOrNull(index + 4) as? FiveRegisterInstruction ?: return@filter false
        val presenterInitReference = methodReference(instructions.getOrNull(index + 4)) ?: return@filter false
        if (
            instructions[index + 4].opcode != Opcode.INVOKE_DIRECT ||
            presenterInitReference.definingClass != chipBarPresenterType ||
            presenterInitReference.name != "<init>" ||
            presenterInitReference.returnType != VOID ||
            presenterInitReference.parameterTypes.map(CharSequence::toString).firstOrNull() != COMPOSE_VIEW ||
            presenterInit.registerCount < 2 ||
            presenterInit.registerC != presenter.registerA ||
            presenterInit.registerD != resolved.registerA
        ) {
            return@filter false
        }

        val supply = instructions.getOrNull(index + 5) as? TwoRegisterInstruction ?: return@filter false
        val supplyOpcode = instructions[index + 5].opcode
        val supplyIsMove =
            supplyOpcode == Opcode.MOVE_OBJECT || supplyOpcode == Opcode.MOVE_OBJECT_FROM16
        supplyIsMove &&
            supply.registerA == chipBarArgumentRegister &&
            supply.registerB == presenter.registerA
    }
    val index = candidates.singleOrNull() ?: return null
    val chipBarRegister = (instructions[index + 1] as OneRegisterInstruction).registerA
    return ChipBarGateShape(insertionIndex = index + 2, chipBarRegister = chipBarRegister)
}

private fun methodReference(instruction: Instruction?): MethodReference? =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

private fun fieldReference(instruction: Instruction?): FieldReference? =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

private fun methodMatches(
    reference: MethodReference,
    definingClass: String,
    name: String,
    parameters: List<String>,
    returnType: String,
): Boolean = reference.definingClass == definingClass &&
    reference.name == name &&
    reference.parameterTypes.map(CharSequence::toString) == parameters &&
    reference.returnType == returnType

private fun recordUnappliedStatus(matchCount: Int, reason: String) {
    patchStatusCollector.record(agentIChatComposerUnappliedRecord(matchCount, reason))
}

/** 0 件は optional target 未発見、複数件は安全に注入できない ERROR として扱います。 */
internal fun agentIChatComposerUnappliedRecord(matchCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.AGENT_I_CHAT_COMPOSER,
    status = if (matchCount > 1) PatchStatus.ERROR else PatchStatus.TARGET_NOT_FOUND,
    expectedTargetCount = EXPECTED_TARGET_COUNT,
    actualTargetCount = matchCount,
    reason = reason,
)

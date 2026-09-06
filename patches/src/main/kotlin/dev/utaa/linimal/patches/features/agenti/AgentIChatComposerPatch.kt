package dev.utaa.linimal.patches.features.agenti

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
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
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus
import dev.utaa.linimal.patches.util.VOID

private const val VIEW = "Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"
private const val COMPOSE_VIEW = "Landroidx/compose/ui/platform/ComposeView;"
private const val AI_TALK_SUGGESTION_CHIP_BAR = 0x7f0b066b  // chat_ui_ai_talk_suggestion_chip_bar
private const val AI_TALK_INPUT_LAYOUT = 0x7f0e0127  // chat_ui_ai_talk_suggestion_input

/** 入力欄下 chip bar の supply gate 1 箇所。 */
private const val EXPECTED_TARGET_COUNT = 1
private const val AGENT_I_CHAT_COMPOSER_CHIP_BAR_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentIChatComposerHooks;" +
        "->adjustAiTalkSuggestionChipBar(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * AI Talk 入力 surface を inflate する controller の constructor。
 *
 * 26.11.0 では controller の class 名が難読化されていなかった
 * (`com.linecorp.line.chat.ui.impl.message.input.aitalksuggestion.a`) ため直接指定していましたが、
 * 26.14.0 では完全に難読化されるため、layout resource と ViewStub の
 * `setLayoutResource` → `inflate` の並びだけで特定し、型と constructor signature を実行時に導出します。
 */
private val aiTalkInflationControllerFingerprint = Fingerprint(
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
)

/** AI Talk controller を生成する唯一の呼び出し元（MessageInputViewControllerImpl の構築経路）。 */
private fun aiTalkControllerWiringFingerprint(controllerType: String, controllerParameters: List<String>) =
    Fingerprint(
        returnType = VOID,
        filters = listOf(
            methodCall(
                definingClass = controllerType,
                name = "<init>",
                parameters = controllerParameters,
                returnType = VOID,
                opcode = Opcode.INVOKE_DIRECT_RANGE,
            ),
        ),
    )

/**
 * `chat_ui_ai_talk_suggestion_chip_bar` を解決する message-input binding の bind factory。
 *
 * 26.11.0 では ViewBinding helper (`Lyd/b;->a`) と marker interface (`Lyd/a;`) も条件でしたが、
 * 26.14.0 では `Lpe/b;->b` / `Lpe/a;` へ変わる難読化名です。resource literal と
 * 「自分自身の型を返す static factory」という形だけで全 DEX 中 1 件に絞れます。
 */
private val aiTalkSuggestionChipBarBindingFingerprint = Fingerprint(
    returnType = null,
    parameters = listOf(VIEW),
    filters = listOf(
        literal(AI_TALK_SUGGESTION_CHIP_BAR),
        methodCall(
            parameters = listOf(VIEW, "I"),
            returnType = VIEW,
            opcode = Opcode.INVOKE_STATIC,
        ),
    ),
    custom = { method, classDef ->
        method.returnType == classDef.type && method.implementation != null
    },
)

/**
 * chip bar view を保持する binding field を読み出す accessor。field 名は resource literal から逆引きするため、
 * `Lo61/j;` などの難読化名も accessor が属する interface 名も識別条件にしません。
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
 * 入力欄の下に並ぶ AI Talk の候補チップ (`chat_ui_ai_talk_suggestion_chip_bar`) にだけ runtime gate を置きます。
 *
 * LINE 自身が chip bar 無効構成で使う null 供給と同じ経路を通し、controller へ渡す前に view を取り除きます。
 *
 * 26.11.0 にあったもう 1 つの surface である入力欄の Agent i ボタン
 * (`id/chat_ui_input_ai_talk_suggestion_button`) は、26.14.0 で layout からもリソースからも削除され、
 * AI Talk controller の constructor からも ImageView 引数が無くなりました。対象が存在しないため
 * この patch では扱いません。
 *
 * text input、gallery、camera、attach menu はいずれも対象外であり、AI Talk の
 * subscription/backend/settings/network は変更しません。
 */
val agentIChatComposerPatch = bytecodePatch(
    name = "チャット入力欄の Agent i",
    description = "チャット入力欄にある Agent i の候補チップを、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(agentISettingsPatch)

    execute {
        val inflationMatches = aiTalkInflationControllerFingerprint.matchAllOrNull().orEmpty()
        if (inflationMatches.size != 1) {
            recordUnappliedStatus(inflationMatches.size, "AgentIChatComposerInflationControllerNotUnique")
            return@execute
        }
        val inflation = inflationMatches.single()
        val controllerType = inflation.originalClassDef.type
        val controllerParameters = inflation.originalMethod.parameterTypes.map(CharSequence::toString)

        val wiringMatches = aiTalkControllerWiringFingerprint(controllerType, controllerParameters)
            .matchAllOrNull()
            .orEmpty()
        if (wiringMatches.size != 1) {
            recordUnappliedStatus(wiringMatches.size, "AgentIChatComposerWiringNotUnique")
            return@execute
        }
        val wiring = wiringMatches.single()

        val bindingMatches = aiTalkSuggestionChipBarBindingFingerprint.matchAllOrNull().orEmpty()
        if (bindingMatches.size != 1) {
            recordUnappliedStatus(bindingMatches.size, "AgentIChatComposerBindingNotUnique")
            return@execute
        }
        val binding = bindingMatches.single()
        val bindingType = binding.originalClassDef.type

        // resource literal から binding field を逆引きし、その field を読む accessor が一意なときにだけ
        // gate を追加します。
        val chipBarField = aiTalkSuggestionChipBarField(binding.originalMethod, binding.originalClassDef)
        val accessorMatches = chipBarField
            ?.let { aiTalkSuggestionChipBarAccessorFingerprint(bindingType, it).matchAllOrNull().orEmpty() }
            .orEmpty()
        if (accessorMatches.size != 1) {
            recordUnappliedStatus(accessorMatches.size, "AgentIChatComposerChipBarAccessorNotUnique")
            return@execute
        }
        val accessor = accessorMatches.single()

        val chipBarGateShape = aiTalkSuggestionChipBarGateShape(
            method = wiring.method,
            controllerType = controllerType,
            controllerParameters = controllerParameters,
            accessorName = accessor.originalMethod.name,
            accessorInterfaces = accessor.originalClassDef.interfaces.toSet(),
        )
        if (!aiTalkInflationShape(inflation.method) || chipBarGateShape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_CHAT_COMPOSER),
                expectedTargetCount = EXPECTED_TARGET_COUNT,
                actualTargetCount = EXPECTED_TARGET_COUNT,
                reason = "AgentIChatComposerInstructionShapeMismatch",
            )
            return@execute
        }

        wiring.method.addInstructions(
            chipBarGateShape.insertionIndex,
            """
                invoke-static { v${chipBarGateShape.chipBarRegister} }, $AGENT_I_CHAT_COMPOSER_CHIP_BAR_HOOK
                move-result-object v${chipBarGateShape.chipBarRegister}
                check-cast v${chipBarGateShape.chipBarRegister}, $COMPOSE_VIEW
            """.trimIndent(),
        )
        patchStatusCollector.record(
            patchId = PatchId.AGENT_I_CHAT_COMPOSER,
            expectedTargetCount = EXPECTED_TARGET_COUNT,
            actualTargetCount = EXPECTED_TARGET_COUNT,
            reason = "AgentIChatComposerChipBarGuarded",
        )
    }
}

private data class ChipBarGateShape(
    val insertionIndex: Int,
    val chipBarRegister: Int,
)

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

/**
 * bind method 内の `chat_ui_ai_talk_suggestion_chip_bar` literal から、chip bar view を格納する binding field 名を
 * 逆引きします。literal → findChildViewById → check-cast → binding constructor argument → iput の連鎖を
 * すべて確認できたときだけ field 名を返します。
 *
 * findChildViewById helper は 26.11.0 の `Lyd/b;->a` から 26.14.0 で `Lpe/b;->b` へ変わったため、
 * 名前ではなく「(View, I) を受けて View を返す static 呼び出し」という形だけを確認します。
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
        findViewReference.parameterTypes.map(CharSequence::toString) != listOf(VIEW, "I") ||
        findViewReference.returnType != VIEW ||
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
 *
 * presenter が controller の何番目の引数かは、26.11.0 で定数 (index 2) にしていましたが、
 * move 先 register と controller constructor の引数型から実行時に決めます。
 */
private fun aiTalkSuggestionChipBarGateShape(
    method: Method,
    controllerType: String,
    controllerParameters: List<String>,
    accessorName: String,
    accessorInterfaces: Set<String>,
): ChipBarGateShape? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val controllerCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                methodMatches(reference, controllerType, "<init>", controllerParameters, VOID)
        } == true
    }
    val controllerCall = instructions.getOrNull(controllerCallIndex) as? RegisterRangeInstruction ?: return null
    if (controllerCall.registerCount != controllerParameters.size + 1) return null

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
        val presenterType = ((instructions[index + 3] as? ReferenceInstruction)?.reference as? TypeReference)?.type
        if (instructions[index + 3].opcode != Opcode.NEW_INSTANCE || presenterType == null) {
            return@filter false
        }

        val presenterInit = instructions.getOrNull(index + 4) as? FiveRegisterInstruction ?: return@filter false
        val presenterInitReference = methodReference(instructions.getOrNull(index + 4)) ?: return@filter false
        if (
            instructions[index + 4].opcode != Opcode.INVOKE_DIRECT ||
            presenterInitReference.definingClass != presenterType ||
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
        // move 先が controller constructor の引数 register であり、その引数型が presenter 型であること。
        val argumentIndex = supply.registerA - controllerCall.startRegister - 1
        supplyIsMove &&
            supply.registerB == presenter.registerA &&
            argumentIndex in controllerParameters.indices &&
            controllerParameters[argumentIndex] == presenterType
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

/** 0 件は target 未発見、複数件は安全に注入できない ERROR として扱います。 */
internal fun agentIChatComposerUnappliedRecord(matchCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.AGENT_I_CHAT_COMPOSER,
    status = if (matchCount > 1) PatchStatus.ERROR else PatchStatus.TARGET_NOT_FOUND,
    expectedTargetCount = EXPECTED_TARGET_COUNT,
    actualTargetCount = matchCount,
    reason = reason,
)

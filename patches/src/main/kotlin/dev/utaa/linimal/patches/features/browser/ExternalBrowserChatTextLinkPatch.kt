package dev.utaa.linimal.patches.features.browser

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import dev.utaa.linimal.patches.features.ads.smartChannelAdsPatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.OBJECT
import dev.utaa.linimal.patches.util.STRING
import dev.utaa.linimal.patches.util.VOID

private const val CONTEXT = "Landroid/content/Context;"
private const val URI = "Landroid/net/Uri;"
private const val INTENT = "Landroid/content/Intent;"
private const val VIEW = "Landroid/view/View;"

/**
 * 難読化された object 型を受けるワイルドカードです。Morphe の型照合は前方一致のため、`"L"` は
 * 任意の object 型に一致します。26.11.0 では `Lna1/p;` 系（referrer param / link options /
 * link router / routing interface / chat referrer / normalized link）をすべて直書きしていましたが、
 * 26.14.0 でいずれも改名されたため、非難読化の enum 定数名と命令列の shape から導出します。
 */
private const val ANY_OBJECT = "L"

/** `<clinit>` の preset に渡される bitmask。26.11.0 と同じ値であることを固定します。 */
private const val CHAT_CLICK_PRESET_FLAGS = 4

/** `interface->helper(receiver, url, referrer, z, z, z, z, options, flags)` の形。 */
private const val DEFAULT_LINK_ROUTING_PARAMETER_COUNT = 9
private const val DEFAULT_ROUTING_INTERFACE_INDEX = 0
private const val DEFAULT_REFERRER_PARAMETER_INDEX = 2
private const val DEFAULT_LINK_OPTIONS_INDEX = 7

/** `router->route(context, uri, options, chatReferrer, z, referrer, z)` の形。 */
private const val LINK_ROUTER_PARAMETER_COUNT = 7
private const val LINK_ROUTER_CHAT_REFERRER_INDEX = 3

private const val EXTERNAL_BROWSER_HOOK =
    "Ldev/utaa/linimal/extension/features/browser/ExternalBrowserHooks;" +
        "->tryOpenNormalLinkExternally(Landroid/content/Context;Landroid/net/Uri;)Z"

/** CHAT / CLICK preset の static field。難読化名ではなく shape から解決した実体です。 */
private data class ChatClickPreset(
    val referrerParamType: String,
    val presetFieldName: String,
)

/** 通常チャット本文 binder が使う routing helper から導出した、難読化された型です。 */
private data class ChatTextLinkRouting(
    val routingInterfaceType: String,
    val linkOptionsType: String,
)

private fun defaultLinkRoutingParameters(referrerParamType: String) = listOf(
    ANY_OBJECT,
    STRING,
    referrerParamType,
    BOOLEAN,
    BOOLEAN,
    BOOLEAN,
    BOOLEAN,
    ANY_OBJECT,
    "I",
)

private fun linkRouterParameters(referrerParamType: String, linkOptionsType: String) = listOf(
    CONTEXT,
    URI,
    linkOptionsType,
    ANY_OBJECT,
    BOOLEAN,
    referrerParamType,
    BOOLEAN,
)

/**
 * CHAT / CLICK preset を組み立てる static initializer。26.11.0 は `Lna1/p;` とその field `h` を
 * 直書きしていましたが、26.14.0 で改名されたため、非難読化のまま残る enum 定数名 CHAT / CLICK と
 * 「自分自身の型を自分の static field へ格納する」shape だけで特定します。
 * この preset が、外部ブラウザへ渡してよい経路を判定する唯一の識別子です。
 */
private val chatClickPresetFingerprint = Fingerprint(
    name = "<clinit>",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(name = "CHAT", opcode = Opcode.SGET_OBJECT),
        fieldAccess(name = "CLICK", opcode = Opcode.SGET_OBJECT),
        fieldAccess(definingClass = "this", opcode = Opcode.SPUT_OBJECT),
    ),
    custom = { method, classDef ->
        chatClickPresetFieldNameOrNull(
            method.implementation?.instructions?.toList().orEmpty(),
            classDef.type,
        ) != null
    },
)

/**
 * テキストメッセージ binder が URL を正規化し、preset を読み、routing interface の static helper へ
 * 渡す経路を確認します。汎用 target を変更する前に、preset が通常チャット本文リンクで使われることを
 * 保証すると同時に、難読化された routing interface と link options の型をここから導出します。
 */
private fun chatTextLinkCallerFingerprint(preset: ChatClickPreset) = Fingerprint(
    returnType = VOID,
    parameters = listOf(STRING),
    filters = listOf(
        methodCall(
            parameters = listOf(CONTEXT, STRING, STRING),
            returnType = ANY_OBJECT,
            opcode = Opcode.INVOKE_STATIC,
        ),
        fieldAccess(
            definingClass = preset.referrerParamType,
            name = preset.presetFieldName,
            type = preset.referrerParamType,
            opcode = Opcode.SGET_OBJECT,
        ),
        fieldAccess(type = ANY_OBJECT, opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = defaultLinkRoutingParameters(preset.referrerParamType),
            returnType = VOID,
            opcode = Opcode.INVOKE_STATIC_RANGE,
        ),
    ),
)

/**
 * 汎用リンク routing の実装。難読化されたクラス名・メソッド名を条件にせず、URI 変換、
 * chat referrer model の生成、最終 routing の引数形状、`Context.startActivity` を組み合わせて
 * 一件に限定します。
 */
private fun externalBrowserTargetFingerprint(
    referrerParamType: String,
    routing: ChatTextLinkRouting,
) = Fingerprint(
    returnType = VOID,
    parameters = listOf(STRING, referrerParamType, BOOLEAN, routing.linkOptionsType),
    filters = listOf(
        methodCall(
            definingClass = URI,
            name = "parse",
            parameters = listOf(STRING),
            returnType = URI,
            opcode = Opcode.INVOKE_STATIC,
        ),
        newInstance(ANY_OBJECT),
        fieldAccess(type = ANY_OBJECT, opcode = Opcode.IGET_OBJECT),
        fieldAccess(type = ANY_OBJECT, opcode = Opcode.IGET_OBJECT),
        methodCall(
            parameters = linkRouterParameters(referrerParamType, routing.linkOptionsType),
            returnType = INTENT,
            opcode = Opcode.INVOKE_INTERFACE_RANGE,
        ),
        methodCall(
            definingClass = CONTEXT,
            name = "startActivity",
            parameters = listOf(INTENT),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
    custom = { _, classDef -> classDef.interfaces.contains(routing.routingInterfaceType) },
)

/**
 * 変更対象は最終の汎用実装だけですが、View click → Kotlin callback → text binder → routing interface
 * という完全なクリック経路が存在することも必須にします。
 */
private fun textLinkCallbackFingerprint(textLinkCaller: Match) = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(OBJECT),
    filters = listOf(
        methodCall(
            definingClass = textLinkCaller.originalClassDef.type,
            name = textLinkCaller.method.name,
            parameters = textLinkCaller.method.parameterTypes.map(CharSequence::toString),
            returnType = textLinkCaller.method.returnType,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
)

private fun textLinkViewClickFingerprint(callback: Match) = Fingerprint(
    returnType = VOID,
    parameters = listOf(VIEW),
    filters = listOf(
        methodCall(
            definingClass = callback.originalClassDef.type,
            name = callback.method.name,
            parameters = callback.method.parameterTypes.map(CharSequence::toString),
            returnType = callback.method.returnType,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
)

private data class TargetInjectionShape(
    val insertionIndex: Int,
    val uriRegister: Int,
    val presetScratchRegister: Int,
    val contextScratchRegister: Int,
    val contextField: FieldReference,
)

/**
 * 外部ブラウザ routing は通常チャット本文だけに限定します。OAuth、Channel permission、Pay、LIFF、
 * Settings WebView、Timeline、rich message は、検証済みの preset 経路へ入りません。
 */
val externalBrowserChatTextLinkPatch = bytecodePatch(
    name = "リンクを外部ブラウザで開く",
    description = "トーク本文の通常の http/https リンクを、実行時設定で外部ブラウザへ渡せるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(smartChannelAdsPatch)

    execute {
        // 26.11.0 は難読化名を直書きしていたため target から解決していました。26.14.0 では
        // すべての名前が変わるため、非難読化の CHAT / CLICK preset を起点に順へ導出します。
        val presetMatches = chatClickPresetFingerprint.matchAllOrNull().orEmpty()
        if (presetMatches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = presetMatches.size,
                reason = "ExternalBrowserChatPresetNotUnique",
            )
            return@execute
        }

        val presetMatch = presetMatches.single()
        val referrerParamType = presetMatch.originalClassDef.type
        val presetFieldName = chatClickPresetFieldNameOrNull(
            presetMatch.method.implementation?.instructions?.toList().orEmpty(),
            referrerParamType,
        )
        if (presetFieldName == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ExternalBrowserChatPresetShapeMismatch",
            )
            return@execute
        }
        val preset = ChatClickPreset(referrerParamType, presetFieldName)

        val textLinkCallers = chatTextLinkCallerFingerprint(preset).matchAllOrNull().orEmpty()
        if (textLinkCallers.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = textLinkCallers.size,
                reason = "ExternalBrowserChatCallerNotUnique",
            )
            return@execute
        }

        val routing = chatTextLinkRoutingOrNull(textLinkCallers.single(), preset)
        if (routing == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ExternalBrowserChatCallerShapeMismatch",
            )
            return@execute
        }

        val targetMatches = externalBrowserTargetFingerprint(preset.referrerParamType, routing)
            .matchAllOrNull()
            .orEmpty()
        if (targetMatches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = targetMatches.size,
                reason = "ExternalBrowserTargetNotUnique",
            )
            return@execute
        }

        val target = targetMatches.single()
        val targetShape = targetInjectionShape(target)
        if (targetShape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ExternalBrowserTargetInstructionShapeMismatch",
            )
            return@execute
        }

        val textLinkCallbacks = textLinkCallbackFingerprint(textLinkCallers.single()).matchAllOrNull().orEmpty()
        if (textLinkCallbacks.size != 1 || !hasTextLinkCallbackShape(textLinkCallbacks.singleOrNull())) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ExternalBrowserCallbackChainMismatch",
            )
            return@execute
        }

        val viewClicks = textLinkViewClickFingerprint(textLinkCallbacks.single()).matchAllOrNull().orEmpty()
        if (viewClicks.size != 1 || !hasTextLinkViewClickShape(viewClicks.singleOrNull())) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ExternalBrowserViewClickChainMismatch",
            )
            return@execute
        }

        val contextFieldSmali = "${targetShape.contextField.definingClass}->" +
            "${targetShape.contextField.name}:${targetShape.contextField.type}"
        // preset の identity 比較だけが外部ブラウザへの分岐条件です。ここを緩めると
        // LINE 内部リンクや決済リンクまで外部へ流れます。
        val presetFieldSmali =
            "${preset.referrerParamType}->${preset.presetFieldName}:${preset.referrerParamType}"
        target.method.addInstructionsWithLabels(
            targetShape.insertionIndex,
            """
                sget-object v${targetShape.presetScratchRegister}, $presetFieldSmali
                if-ne p2, v${targetShape.presetScratchRegister}, :original
                iget-object v${targetShape.contextScratchRegister}, p0, $contextFieldSmali
                invoke-static { v${targetShape.contextScratchRegister}, v${targetShape.uriRegister} }, $EXTERNAL_BROWSER_HOOK
                move-result v${targetShape.contextScratchRegister}
                if-eqz v${targetShape.contextScratchRegister}, :original
                return-void
                :original
                nop
            """.trimIndent(),
        )
        recordFeatureStatus(
            listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ExternalBrowserChatTextLinkGuarded",
        )
    }
}

/**
 * URI parse 境界の local register を再利用する前に、reference target の data flow を厳密に検証します。
 * link router field と Context field は型名に依存せず、routing 命令と `Context.startActivity` の
 * 引数から裏づけます。
 */
private fun targetInjectionShape(match: Match): TargetInjectionShape? {
    val method = match.method
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val parseIndex = match.instructionMatches[0].index
    val chatReferrerIndex = match.instructionMatches[1].index
    val routerFieldIndex = match.instructionMatches[2].index
    val contextFieldIndex = match.instructionMatches[3].index
    val routeIndex = match.instructionMatches[4].index
    val startActivityIndex = match.instructionMatches[5].index
    val implementation = method.implementation ?: return null
    val parameterStart = implementation.registerCount - method.parameterTypes.size - 1
    val thisRegister = parameterStart
    val stringParameterRegister = parameterStart + 1
    val referrerParameterRegister = parameterStart + 2
    val parse = instructions.getOrNull(parseIndex) as? FiveRegisterInstruction
    val uriResult = instructions.getOrNull(parseIndex + 1) as? OneRegisterInstruction
    val uriCheck = instructions.getOrNull(parseIndex + 2) as? FiveRegisterInstruction
    val chatReferrerValue = (instructions.getOrNull(chatReferrerIndex) as? ReferenceInstruction)
        ?.reference.let { it as? TypeReference }?.type
    val routerRead = instructions.getOrNull(routerFieldIndex) as? TwoRegisterInstruction
    val routerField = (instructions.getOrNull(routerFieldIndex) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val contextRead = instructions.getOrNull(contextFieldIndex) as? TwoRegisterInstruction
    val contextField = (instructions.getOrNull(contextFieldIndex) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val route = instructions.getOrNull(routeIndex) as? RegisterRangeInstruction
    val routeReference = (instructions.getOrNull(routeIndex) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val chatReferrerType = routeReference
        ?.parameterTypes
        ?.getOrNull(LINK_ROUTER_CHAT_REFERRER_INDEX)
        ?.toString()
    val referrerMove = instructions.getOrNull(routeIndex - 3) as? TwoRegisterInstruction
    val startActivity = instructions.getOrNull(startActivityIndex) as? FiveRegisterInstruction
    val activityRead = instructions.getOrNull(startActivityIndex - 1) as? TwoRegisterInstruction
    val activityField = (instructions.getOrNull(startActivityIndex - 1) as? ReferenceInstruction)
        ?.reference as? FieldReference

    // 検証済みの reference shape では、この位置の v0/v1 は live ではありません。v0 は preset の
    // identity 比較だけに使い、v1 は Context / hook result の後に元コードで上書きされます。
    if (
        implementation.registerCount - (method.parameterTypes.size + 1) < 2 ||
        parse?.opcode != Opcode.INVOKE_STATIC ||
        parse.registerCount != 1 ||
        parse.registerC != stringParameterRegister ||
        uriResult?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        uriResult.registerA !in 0..15 ||
        uriCheck?.opcode != Opcode.INVOKE_VIRTUAL ||
        uriCheck.registerCount != 1 ||
        uriCheck.registerC != uriResult.registerA ||
        (instructions.getOrNull(parseIndex + 2) as? ReferenceInstruction)?.reference
            .let { it as? MethodReference }
            ?.let { it.definingClass == OBJECT && it.name == "getClass" && it.returnType == "Ljava/lang/Class;" }
            != true ||
        routeReference == null ||
        routeReference.parameterTypes.size != LINK_ROUTER_PARAMETER_COUNT ||
        routeReference.returnType != INTENT ||
        // 生成される chat referrer は routing 引数の sealed 型の入れ子実装です。
        // 26.11.0 の `newInstance(Lna1/q$a;)` 直書きを、この関係で置き換えます。
        chatReferrerType == null ||
        chatReferrerValue == null ||
        !chatReferrerValue.startsWith(chatReferrerType.dropLast(1) + "$") ||
        routerRead?.opcode != Opcode.IGET_OBJECT ||
        routerRead.registerB != thisRegister ||
        routerField == null ||
        routerField.definingClass != match.originalClassDef.type ||
        // link router field の型が、そのまま routing 呼び出しの receiver 型であることを裏づけます。
        routerField.type != routeReference.definingClass ||
        contextRead?.opcode != Opcode.IGET_OBJECT ||
        contextRead.registerB != thisRegister ||
        contextRead.registerA != 1 ||
        contextField == null ||
        contextField.definingClass != match.originalClassDef.type ||
        // Context field は型名ではなく、同じ field が `Context.startActivity` の receiver として
        // 読み直されることで裏づけます。26.11.0 の `Landroidx/fragment/app/b0;` 直書きの置き換えです。
        startActivity?.opcode != Opcode.INVOKE_VIRTUAL ||
        startActivity.registerCount != 2 ||
        activityRead?.opcode != Opcode.IGET_OBJECT ||
        activityRead.registerB != thisRegister ||
        activityRead.registerA != startActivity.registerC ||
        activityField != contextField ||
        route?.opcode != Opcode.INVOKE_INTERFACE_RANGE ||
        route.registerCount != LINK_ROUTER_PARAMETER_COUNT + 1 ||
        route.startRegister != routerRead.registerA ||
        route.startRegister + 1 != contextRead.registerA ||
        route.startRegister + 2 != uriResult.registerA ||
        referrerMove?.opcode != Opcode.MOVE_OBJECT ||
        referrerMove.registerA != route.startRegister + 6 ||
        referrerMove.registerB != referrerParameterRegister
    ) {
        return null
    }

    return TargetInjectionShape(
        insertionIndex = parseIndex + 2,
        uriRegister = uriResult.registerA,
        presetScratchRegister = 0,
        contextScratchRegister = 1,
        contextField = contextField,
    )
}

/**
 * `<clinit>` の中から「CHAT / CLICK と既定の bitmask で組み立てて自分の static field へ格納する」
 * 唯一の store を探し、その field 名を返します。難読化された field 名を条件にしないための導出です。
 */
private fun chatClickPresetFieldNameOrNull(
    instructions: List<Instruction>,
    referrerParamType: String,
): String? {
    val candidates = instructions.indices.mapNotNull { index ->
        val store = instructions[index]
        val storeField = (store as? ReferenceInstruction)?.reference as? FieldReference
        val storeRegister = (store as? OneRegisterInstruction)?.registerA
        val constructor = instructions.getOrNull(index - 1) as? FiveRegisterInstruction
        val constructorReference = (instructions.getOrNull(index - 1) as? ReferenceInstruction)
            ?.reference as? MethodReference
        val constructorParameters = constructorReference?.parameterTypes?.map(CharSequence::toString)

        if (
            store.opcode != Opcode.SPUT_OBJECT ||
            storeField?.definingClass != referrerParamType ||
            storeField.type != referrerParamType ||
            storeRegister == null ||
            constructor?.opcode != Opcode.INVOKE_DIRECT ||
            constructor.registerCount != 4 ||
            constructorReference?.definingClass != referrerParamType ||
            constructorReference.name != "<init>" ||
            constructorReference.returnType != VOID ||
            constructorParameters?.size != 3 ||
            constructorParameters[2] != "I" ||
            storeRegister != constructor.registerC
        ) {
            return@mapNotNull null
        }

        val chatRead = lastWriteOrNull(instructions, index - 1, constructor.registerD)
        val clickRead = lastWriteOrNull(instructions, index - 1, constructor.registerE)
        val flags = (lastWriteOrNull(instructions, index - 1, constructor.registerF) as? NarrowLiteralInstruction)
            ?.narrowLiteral

        if (
            !isEnumConstantRead(chatRead, constructorParameters[0], "CHAT") ||
            !isEnumConstantRead(clickRead, constructorParameters[1], "CLICK") ||
            flags != CHAT_CLICK_PRESET_FLAGS
        ) {
            return@mapNotNull null
        }

        storeField.name
    }
    return candidates.singleOrNull()
}

/** [index] より手前で [register] へ最後に書き込んだ命令。見つからない、または別命令なら照合が落ちます。 */
private fun lastWriteOrNull(
    instructions: List<Instruction>,
    index: Int,
    register: Int,
): Instruction? = (index - 1 downTo 0)
    .firstOrNull { candidate -> (instructions[candidate] as? OneRegisterInstruction)?.registerA == register }
    ?.let(instructions::get)

private fun isEnumConstantRead(
    instruction: Instruction?,
    enumType: String,
    constantName: String,
): Boolean {
    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
    return instruction?.opcode == Opcode.SGET_OBJECT &&
        field?.definingClass == enumType &&
        field.name == constantName &&
        field.type == enumType
}

/**
 * 通常チャット本文 binder が preset をそのまま routing helper の referrer 引数へ渡していることを
 * 確認し、難読化された routing interface と link options の型を返します。
 */
private fun chatTextLinkRoutingOrNull(match: Match, preset: ChatClickPreset): ChatTextLinkRouting? {
    val instructions = match.method.implementation?.instructions?.toList() ?: return null
    val presetReadIndex = match.instructionMatches[1].index
    val routeIndex = match.instructionMatches[3].index
    val presetRead = instructions.getOrNull(presetReadIndex) as? OneRegisterInstruction
    val presetField = (instructions.getOrNull(presetReadIndex) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val route = instructions.getOrNull(routeIndex) as? RegisterRangeInstruction
    val routeReference = (instructions.getOrNull(routeIndex) as? ReferenceInstruction)
        ?.reference as? MethodReference
    val routeParameters = routeReference?.parameterTypes?.map(CharSequence::toString)

    if (
        presetRead?.opcode != Opcode.SGET_OBJECT ||
        presetField?.definingClass != preset.referrerParamType ||
        presetField.name != preset.presetFieldName ||
        presetField.type != preset.referrerParamType ||
        route?.opcode != Opcode.INVOKE_STATIC_RANGE ||
        routeReference?.returnType != VOID ||
        routeParameters?.size != DEFAULT_LINK_ROUTING_PARAMETER_COUNT ||
        routeParameters[DEFAULT_REFERRER_PARAMETER_INDEX] != preset.referrerParamType ||
        // Kotlin interface の static helper は自身を第 1 引数に取るため、ここから interface 型を得ます。
        routeParameters[DEFAULT_ROUTING_INTERFACE_INDEX] != routeReference.definingClass ||
        route.registerCount != DEFAULT_LINK_ROUTING_PARAMETER_COUNT ||
        route.startRegister + DEFAULT_REFERRER_PARAMETER_INDEX != presetRead.registerA
    ) {
        return null
    }

    return ChatTextLinkRouting(
        routingInterfaceType = routeReference.definingClass,
        linkOptionsType = routeParameters[DEFAULT_LINK_OPTIONS_INDEX],
    )
}

private fun hasTextLinkCallbackShape(match: Match?): Boolean {
    val candidate = match ?: return false
    val instructions = candidate.method.implementation?.instructions?.toList() ?: return false
    val invokeIndex = candidate.instructionMatches.singleOrNull()?.index ?: return false
    val stringCast = instructions.getOrNull(invokeIndex - 4) as? ReferenceInstruction
    val receiverRead = instructions.getOrNull(invokeIndex - 2) as? TwoRegisterInstruction
    val receiverCast = instructions.getOrNull(invokeIndex - 1) as? ReferenceInstruction
    val invoke = instructions.getOrNull(invokeIndex) as? FiveRegisterInstruction
    val invokeReference = (instructions.getOrNull(invokeIndex) as? ReferenceInstruction)
        ?.reference as? MethodReference

    return stringCast?.opcode == Opcode.CHECK_CAST &&
        (stringCast.reference as? TypeReference)?.type == STRING &&
        receiverRead?.opcode == Opcode.IGET_OBJECT &&
        receiverCast?.opcode == Opcode.CHECK_CAST &&
        (receiverCast.reference as? TypeReference)?.type == invokeReference?.definingClass &&
        invoke?.opcode == Opcode.INVOKE_VIRTUAL &&
        invoke.registerCount == 2 &&
        receiverRead.registerA == receiverCast.let { it as? OneRegisterInstruction }?.registerA &&
        invoke.registerC == receiverCast.let { it as? OneRegisterInstruction }?.registerA &&
        invoke.registerD == stringCast.let { it as? OneRegisterInstruction }?.registerA
}

private fun hasTextLinkViewClickShape(match: Match?): Boolean {
    val candidate = match ?: return false
    val instructions = candidate.method.implementation?.instructions?.toList() ?: return false
    val invokeIndex = candidate.instructionMatches.singleOrNull()?.index ?: return false
    val callbackRead = instructions.getOrNull(invokeIndex - 1) as? TwoRegisterInstruction
    val callbackField = (instructions.getOrNull(invokeIndex - 1) as? ReferenceInstruction)
        ?.reference as? FieldReference
    val invoke = instructions.getOrNull(invokeIndex) as? FiveRegisterInstruction

    return callbackRead?.opcode == Opcode.IGET_OBJECT &&
        callbackField != null &&
        callbackRead.registerA == invoke?.registerC &&
        invoke.opcode == Opcode.INVOKE_VIRTUAL &&
        invoke.registerCount == 2
}

package dev.utaa.linimal.patches.features.agenti

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val WALLET_HEADER_OWNER =
    "Lcom/linecorp/line/wallet/impl/v3/view/WalletV3GrandDesignHeaderView;"
private const val WALLET_AGENT_STATE =
    "Lcom/linecorp/line/wallet/impl/v3/view/WalletV3GrandDesignHeaderView\$a;"
private const val WALLET_AGENT_ICON = 0x7f0821dc  // wallet_agent_i_navigate_icon
private const val WALLET_AGENT_ACCESSIBILITY_LABEL = 0x7f150351  // access_minitab_agenti
private const val WALLET_HEADER_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentIWalletHeaderHooks;->adjustButtonState(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Wallet header の Agent i button state setup。campaign / search state setup を含めません。
 *
 * 26.11.0 では難読化された method 名 `o` と accessFlags も条件にしていましたが、どちらも版ごとに
 * 変わり得るため落としました。2 つの Agent i 専用 resource と stable な `setAgentIButtonState` だけで
 * 全 DEX 中 1 件に絞れます。
 */
private val walletAgentStateSupplierFingerprint = Fingerprint(
    definingClass = WALLET_HEADER_OWNER,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        literal(WALLET_AGENT_ICON),
        literal(WALLET_AGENT_ACCESSIBILITY_LABEL),
        methodCall(
            definingClass = WALLET_HEADER_OWNER,
            name = "setAgentIButtonState",
            parameters = listOf(WALLET_AGENT_STATE),
            returnType = "V",
            opcode = Opcode.INVOKE_DIRECT,
        ),
    ),
)

/**
 * Wallet mini-tab header が生成済み Agent i state を host setter へ渡す直前に gate を置きます。
 * ON はその state argument だけ null にし、OFF と hook failure は同一 state instance を渡します。
 */
val agentIWalletHeaderPatch = bytecodePatch(
    name = "ウォレット上部の Agent i",
    description = "ウォレット画面上部にある Agent i の入口を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(agentIHomeHeaderPatch)

    execute {
        val supplierMatches = walletAgentStateSupplierFingerprint.matchAllOrNull().orEmpty()
        if (supplierMatches.size != 1) {
            recordWalletHeaderUnapplied(supplierMatches.size, "AgentIWalletStateSupplierNotUnique")
            return@execute
        }

        val supplier = supplierMatches.single()
        val stateSupply = walletAgentStateSupply(supplier.method)
        if (stateSupply == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_WALLET_HEADER),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "AgentIWalletStateSupplierShapeMismatch",
            )
            return@execute
        }

        supplier.method.addInstructions(
            stateSupply.setterIndex,
            """
                invoke-static { v${stateSupply.stateRegister} }, $WALLET_HEADER_HOOK
                move-result-object v${stateSupply.stateRegister}
                check-cast v${stateSupply.stateRegister}, $WALLET_AGENT_STATE
            """.trimIndent(),
        )
        patchStatusCollector.record(
            patchId = PatchId.AGENT_I_WALLET_HEADER,
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "AgentIWalletStateArgumentAdjusted",
        )
    }
}

private data class WalletAgentStateSupply(
    val setterIndex: Int,
    val stateRegister: Int,
)

/**
 * Validates the final host call and proves that its argument is the unique Agent i state object constructed here.
 * The original method body remains intact; only the exact final argument register is replaced.
 *
 * 26.11.0 では click action の型を別 fingerprint（deeplink 用の `minitab_header` 文字列と
 * `Context.startActivity`）から取得していました。26.14.0 では click action が
 * `onAgentIButtonClick` への method reference へ置き換わり、その文字列も startActivity 呼び出しも
 * 無くなったため、state constructor へ渡される callback 引数そのものを register から辿って
 * 「この method 内で 1 度だけ生成された、owner の nested class」であることを確認します。
 */
private fun walletAgentStateSupply(method: Method): WalletAgentStateSupply? {
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions.toList()
    val setters = instructions.mapIndexedNotNull { index, instruction ->
        val setter = instruction as? FiveRegisterInstruction ?: return@mapIndexedNotNull null
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (
            instruction.opcode == Opcode.INVOKE_DIRECT &&
            setter.registerCount == 2 &&
            reference?.definingClass == WALLET_HEADER_OWNER &&
            reference.name == "setAgentIButtonState" &&
            reference.parameterTypes == listOf(WALLET_AGENT_STATE) &&
            reference.returnType == "V"
        ) {
            WalletAgentStateSupply(index, setter.registerD)
        } else {
            null
        }
    }
    val stateSupply = setters.singleOrNull() ?: return null
    val stateConstructors = instructions.mapIndexedNotNull { index, instruction ->
        val constructor = instruction as? FiveRegisterInstruction ?: return@mapIndexedNotNull null
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        val parameters = reference?.parameterTypes?.map(CharSequence::toString)
        // 第 3 引数は click callback (kotlin.jvm.functions.Function0)。26.11.0 は `Lvb8/a;`、
        // 26.14.0 は `Laj8/a;` と版ごとに変わる難読化型なので前方一致で受けます。
        val parametersMatch = parameters != null &&
            parameters.size == 4 &&
            parameters[0] == "I" &&
            parameters[1] == "I" &&
            parameters[2].startsWith("L") &&
            parameters[3] == "Ljava/util/Set;"
        constructor.takeIf {
            instruction.opcode == Opcode.INVOKE_DIRECT &&
                reference?.definingClass == WALLET_AGENT_STATE &&
                reference.name == "<init>" &&
                parametersMatch &&
                reference.returnType == "V" &&
                constructor.registerCount == 5 &&
                constructor.registerC == stateSupply.stateRegister
        }?.let { index to it }
    }
    val stateConstructor = stateConstructors.singleOrNull() ?: return null

    // state constructor が受け取る click callback は、この method 内で 1 度だけ生成された
    // Wallet header owner の nested class でなければなりません。
    val actionRegister = stateConstructor.second.registerF
    val actionInstances = instructions.count { instruction ->
        val type = ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type
        instruction.opcode == Opcode.NEW_INSTANCE &&
            (instruction as? OneRegisterInstruction)?.registerA == actionRegister &&
            type != null &&
            type.startsWith(WALLET_HEADER_OWNER.removeSuffix(";")) &&
            type != WALLET_AGENT_STATE
    }

    return stateSupply.takeIf {
        method.parameterTypes.isEmpty() &&
            implementation.registerCount >= 2 &&
            it.stateRegister in 0..15 &&
            it.setterIndex == instructions.lastIndex - 1 &&
            instructions.lastOrNull()?.opcode == Opcode.RETURN_VOID &&
            stateConstructor.first < it.setterIndex &&
            actionInstances == 1
    }
}

private fun recordWalletHeaderUnapplied(matchCount: Int, reason: String) {
    patchStatusCollector.record(agentIWalletHeaderUnappliedRecord(matchCount, reason))
}

internal fun agentIWalletHeaderUnappliedRecord(matchCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.AGENT_I_WALLET_HEADER,
    status = if (matchCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = 1,
    actualTargetCount = matchCount,
    reason = reason,
)

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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.utaa.linimal.patches.features.ads.homeTopAdPatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val HOME_AGENT_ICON = 0x7f080b87  // home26_navi_top_agent
private const val HOME_AGENT_ACCESSIBILITY_LABEL = 0x7f15006c  // access_agenti
private const val HOME_HEADER_HOOK =
    "Ldev/utaa/linimal/extension/features/agenti/AgentIHomeHeaderHooks;->adjustVisibility(Z)Z"

/** icon lambda constructor の並び。boolean と callback が icon ごとに交互に並びます。 */
private val homeAgentIconCallbackParameters = listOf("Z", "L", "Z", "L", "Z", "L", "Z", "L")

/**
 * Agent i icon の drawable、accessibility label、Compose icon call を組み合わせた resource anchor。
 *
 * Compose icon helper は 26.11.0 の `Loa2/m;->c` から 26.14.0 で `Lng2/n;->b` へ名前が変わったため、
 * 難読化された class / method 名は落とし、引数の並び（先頭 2 つと theme / Set / Composer の位置）だけを
 * 条件にします。難読化型は前方一致の `"L"` で受けます。
 */
private val homeAgentIconFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        literal(HOME_AGENT_ACCESSIBILITY_LABEL),
        literal(HOME_AGENT_ICON),
        methodCall(
            parameters = listOf(
                "I",
                "Ljava/lang/String;",
                "L",
                "L",
                "L",
                "Lcom/linecorp/line/compose/theme/g;",
                "Ljava/util/Set;",
                "L",
                "I",
                "I",
            ),
            returnType = "V",
            opcode = Opcode.INVOKE_STATIC_RANGE,
        ),
    ),
    // 26.11.0 の `classDef.interfaces.contains("Lvb8/q;")` は 26.14.0 で `Laj8/q;` へ変わる
    // 難読化名でした。2 つの Agent i 専用 resource だけで全 DEX 中 1 件に絞れるため落とします。
)

/**
 * 4 icon callback を生成する composable のうち Agent i icon callback を持つ唯一の boolean supplier。
 *
 * 26.11.0 では deeplink 文字列 `line://lineai/thread` → callback → host → `b` という chain で
 * 導出していましたが、26.14.0 では deeplink が remote config 由来になり文字列が消えました。
 * 代わりに、resource で特定済みの icon lambda の constructor を呼ぶ唯一の method として導出します。
 */
private fun homeAgentHeaderSupplierFingerprint(iconCallbackType: String) = Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            definingClass = iconCallbackType,
            name = "<init>",
            parameters = homeAgentIconCallbackParameters,
            returnType = "V",
            opcode = Opcode.INVOKE_DIRECT_RANGE,
        ),
    ),
)

/**
 * Home 上部ナビゲーションが再 composition ごとに受け取る Agent i 専用 boolean を制御します。
 * icon row や他の 3 button callback、deeplink dispatcher、analytics は変更しません。
 */
val agentIHomeHeaderPatch = bytecodePatch(
    name = "ホーム上部の Agent i",
    description = "ホーム画面上部にある Agent i の入口を、実行時設定で非表示にできるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(homeTopAdPatch)

    execute {
        val iconMatches = homeAgentIconFingerprint.matchAllOrNull().orEmpty()
        if (iconMatches.size != 1) {
            recordHomeHeaderUnapplied(iconMatches.size, "AgentIHomeHeaderIconNotUnique")
            return@execute
        }

        val iconCallbackType = iconMatches.single().originalClassDef.type
        val supplierMatches = homeAgentHeaderSupplierFingerprint(iconCallbackType).matchAllOrNull().orEmpty()
        if (supplierMatches.size != 1) {
            recordHomeHeaderUnapplied(supplierMatches.size, "AgentIHomeHeaderSupplierNotUnique")
            return@execute
        }

        val supplier = supplierMatches.single()
        if (!guardHomeHeaderSupplier(supplier.method, iconCallbackType)) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.AGENT_I_HOME_HEADER),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "AgentIHomeHeaderSupplierShapeMismatch",
            )
            return@execute
        }

        supplier.method.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $HOME_HEADER_HOOK
                move-result p1
            """.trimIndent(),
        )
        patchStatusCollector.record(
            patchId = PatchId.AGENT_I_HOME_HEADER,
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "AgentIHomeHeaderVisibilitySupplied",
        )
    }
}

private fun guardHomeHeaderSupplier(method: Method, iconCallbackType: String): Boolean {
    val implementation = method.implementation ?: return false
    val instructions = implementation.instructions.toList()
    // 26.11.0 は `Lgg2/r;` / `Lgg2/j;` / `Lgg2/i;` / `Lh3/t;`、26.14.0 は `Lwm2/q;` / `Lwm2/i;` /
    // `Lwm2/h;` / `Lh3/s;`。いずれも版ごとに変わる難読化型なので前方一致で受けます。
    val expectedParameters = listOf(
        "L",
        "Z",
        "Z",
        "Z",
        "Z",
        "L",
        "L",
        "L",
        "L",
        "L",
        "L",
        "I",
    )
    val constructorIndices = instructions.mapIndexedNotNull { index, instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        val parameters = reference?.parameterTypes?.map(CharSequence::toString)
        index.takeIf {
            instruction.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                reference?.definingClass == iconCallbackType &&
                reference.name == "<init>" &&
                parameters != null &&
                parametersStartWith(parameters, homeAgentIconCallbackParameters) &&
                reference.returnType == "V"
        }
    }
    val constructorIndex = constructorIndices.singleOrNull() ?: return false
    val constructor = instructions[constructorIndex] as? RegisterRangeInstruction ?: return false
    // The range receiver is followed by Agent i's first boolean. Its immediate move must originate from p1.
    val firstBooleanRegister = constructor.startRegister + 1
    val constructorArgumentCopy = instructions.getOrNull(constructorIndex - 8) as? TwoRegisterInstruction ?: return false
    val parameterStart = implementation.registerCount - expectedParameters.size
    val firstBooleanParameterRegister = parameterStart + 1
    val sourceCopies = instructions.take(constructorIndex).filter { instruction ->
        instruction.opcode == Opcode.MOVE_FROM16 &&
            instruction is TwoRegisterInstruction &&
            instruction.registerA == constructorArgumentCopy.registerB &&
            instruction.registerB == firstBooleanParameterRegister
    }

    return parametersStartWith(method.parameterTypes.map(CharSequence::toString), expectedParameters) &&
        parameterStart >= 0 &&
        firstBooleanParameterRegister in 0..255 &&
        constructor.registerCount == 9 &&
        constructorArgumentCopy.opcode == Opcode.MOVE_FROM16 &&
        constructorArgumentCopy.registerA == firstBooleanRegister &&
        sourceCopies.size == 1
}

/** Morphe の parameter 照合と同じく、要素数一致 + 各要素の前方一致で比較します。 */
private fun parametersStartWith(actual: List<String>, expected: List<String>): Boolean =
    actual.size == expected.size && actual.zip(expected).all { (a, e) -> a.startsWith(e) }

private fun recordHomeHeaderUnapplied(matchCount: Int, reason: String) {
    patchStatusCollector.record(agentIHomeHeaderUnappliedRecord(matchCount, reason))
}

internal fun agentIHomeHeaderUnappliedRecord(matchCount: Int, reason: String) = PatchStatusRecord(
    patchId = PatchId.AGENT_I_HOME_HEADER,
    status = if (matchCount == 0) PatchStatus.TARGET_NOT_FOUND else PatchStatus.ERROR,
    expectedTargetCount = 1,
    actualTargetCount = matchCount,
    reason = reason,
)

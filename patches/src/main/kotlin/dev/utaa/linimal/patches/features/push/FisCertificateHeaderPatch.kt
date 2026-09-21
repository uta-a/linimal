package dev.utaa.linimal.patches.features.push

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.utaa.linimal.patches.features.chat.chatListHeaderButtonsPatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusRecord
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus
import dev.utaa.linimal.patches.util.STRING
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.branchTargetAddresses
import dev.utaa.linimal.patches.util.exceptionHandlerAddresses
import dev.utaa.linimal.patches.util.instructionAddress
import dev.utaa.linimal.patches.util.instructionWritesRegister
import dev.utaa.linimal.patches.util.registerSurvivesBetween

internal const val CERTIFICATE_HEADER = "X-Android-Cert"
private const val URL = "Ljava/net/URL;"
private const val HTTP_URL_CONNECTION = "Ljava/net/HttpURLConnection;"
private const val ADD_REQUEST_PROPERTY = "addRequestProperty"
private const val HOOKS_TYPE = "Ldev/utaa/linimal/extension/features/push/FirebaseInstallationsHooks;"
private const val CERTIFICATE_HEADER_HOOK = "$HOOKS_TYPE->certificateHeader($STRING)$STRING"
private const val ORIGINAL_CERTIFICATE_METHOD = "originalCertificateSha1"

private val certificateSha1Pattern = Regex("[0-9A-F]{40}")

/**
 * LINE に同梱された Firebase Installations SDK の接続生成処理。`openHttpURLConnection(URL, String)`
 * に相当し、`X-Android-Cert` をキーにした `addRequestProperty` を持つ唯一のメソッドを対象にします。
 * 同じヘッダーを付ける Remote Config の client は引数と戻り値の形が異なるため一致しません。
 */
private val fisConnectionFingerprint = Fingerprint(
    returnType = HTTP_URL_CONNECTION,
    parameters = listOf(URL, STRING),
    filters = listOf(
        string(CERTIFICATE_HEADER),
        methodCall(
            name = ADD_REQUEST_PROPERTY,
            parameters = listOf(STRING, STRING),
            returnType = VOID,
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
    ),
)

internal data class CertificateHeaderInjection(val insertionIndex: Int, val valueRegister: Int)

/**
 * 再署名で失われる push 通知の登録を戻します。FIS が送る `X-Android-Cert` の値だけを、
 * 実行時設定が ON のとき公式 LINE の証明書 SHA-1 に置き換えます。GMS、PackageManager、LINE の
 * 認証と通信、FIS 以外の Firebase client には触れません。
 */
val fisCertificateHeaderPatch = bytecodePatch(
    name = "アプリを閉じていても通知を受け取る",
    description = "Firebase Installations の登録で送る署名情報を、実行時設定で公式 LINE のものに置き換えられるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(chatListHeaderButtonsPatch)

    execute {
        val certificate = Constants.LINE_ORIGINAL_CERTIFICATE_SHA1
        if (!isOriginalCertificateSha1(certificate)) {
            patchStatusCollector.record(fisCertificateNotConfiguredRecord())
            return@execute
        }

        val matches = fisConnectionFingerprint.matchAllOrNull().orEmpty()
        if (matches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.FIS_CERTIFICATE_HEADER),
                expectedTargetCount = 1,
                actualTargetCount = matches.size,
                reason = "FisConnectionNotUnique",
            )
            return@execute
        }

        val match = matches.single()
        val implementation = match.method.implementation
        val injection = implementation?.let {
            certificateHeaderInjection(
                instructions = it.instructions.toList(),
                headerKeyIndex = match.instructionMatches[0].index,
                addRequestPropertyIndex = match.instructionMatches[1].index,
                handlerAddresses = exceptionHandlerAddresses(it),
            )
        }
        if (injection == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.FIS_CERTIFICATE_HEADER),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "FisCertificateHeaderShapeMismatch",
            )
            return@execute
        }

        val originalCertificateMethod = mutableClassDefByOrNull(HOOKS_TYPE)?.methods?.singleOrNull { method ->
            method.name == ORIGINAL_CERTIFICATE_METHOD &&
                method.parameterTypes.isEmpty() &&
                method.returnType == STRING &&
                (method.implementation?.registerCount ?: 0) >= 1
        }
        if (originalCertificateMethod == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.FIS_CERTIFICATE_HEADER),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "FisCertificateExtensionMethodMissing",
            )
            return@execute
        }

        // 値は patch 側の定数 1 か所で管理し、extension のソースには書きません。
        originalCertificateMethod.addInstructions(
            0,
            """
                const-string v0, "$certificate"
                return-object v0
            """.trimIndent(),
        )
        // 置き換えは hook に任せ、設定 OFF・未初期化・例外時は元の値が同じ register に戻ります。
        match.method.addInstructions(
            injection.insertionIndex,
            """
                invoke-static { v${injection.valueRegister} }, $CERTIFICATE_HEADER_HOOK
                move-result-object v${injection.valueRegister}
            """.trimIndent(),
        )
        recordFeatureStatus(
            listOf(PatchId.FIS_CERTIFICATE_HEADER),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "FisCertificateHeaderGuarded",
        )
    }
}

internal fun isOriginalCertificateSha1(value: String): Boolean = certificateSha1Pattern.matches(value)

/** 公式証明書の SHA-1 が未設定の build。LINE を変更していないため DISABLED として記録します。 */
internal fun fisCertificateNotConfiguredRecord() = PatchStatusRecord(
    patchId = PatchId.FIS_CERTIFICATE_HEADER,
    status = PatchStatus.DISABLED,
    expectedTargetCount = 0,
    actualTargetCount = 0,
    reason = "FisOriginalCertificateNotConfigured",
)

/**
 * `const-string vK, "X-Android-Cert"` の register がそのまま `addRequestProperty` のキーに渡り、
 * 値の register が接続やキーと別であることを確認します。値は直前の String を返す呼び出しの
 * `move-result-object` で作られたものに限ります。
 *
 * キーと値を作ってから呼び出すまでの区間に、分岐先や例外 handler の先頭が 1 つでもあれば拒否します。
 * 途中から合流する経路では、キーが `X-Android-Cert` である保証がなく、呼び出し自体が分岐先なら
 * その経路だけ hook を飛び越すためです。
 */
internal fun certificateHeaderInjection(
    instructions: List<Instruction>,
    headerKeyIndex: Int,
    addRequestPropertyIndex: Int,
    handlerAddresses: Set<Int> = emptySet(),
): CertificateHeaderInjection? {
    val keyLoad = instructions.getOrNull(headerKeyIndex)
    val key = ((keyLoad as? ReferenceInstruction)?.reference as? StringReference)?.string
    val keyRegister = (keyLoad as? OneRegisterInstruction)?.registerA
    val call = instructions.getOrNull(addRequestPropertyIndex) as? FiveRegisterInstruction
    val callReference = (call as? ReferenceInstruction)?.reference as? MethodReference
    val valueDefinitionIndex = call?.let { valueCall ->
        (addRequestPropertyIndex - 1 downTo 0).firstOrNull { index ->
            instructionWritesRegister(instructions[index], valueCall.registerE)
        }
    }
    val valueResult = valueDefinitionIndex?.let { instructions[it] }
    val valueSource = valueDefinitionIndex?.let { instructions.getOrNull(it - 1) }
    val valueSourceReference = (valueSource as? ReferenceInstruction)?.reference as? MethodReference
    val guardedStart = minOf(headerKeyIndex, valueDefinitionIndex ?: headerKeyIndex) + 1

    if (
        headerKeyIndex >= addRequestPropertyIndex ||
        keyLoad?.opcode !in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) ||
        key != CERTIFICATE_HEADER ||
        keyRegister == null ||
        call?.opcode != Opcode.INVOKE_VIRTUAL ||
        callReference?.name != ADD_REQUEST_PROPERTY ||
        callReference.parameterTypes.map(CharSequence::toString) != listOf(STRING, STRING) ||
        callReference.returnType != VOID ||
        call.registerCount != 3 ||
        call.registerD != keyRegister ||
        call.registerE == call.registerC ||
        call.registerE == call.registerD ||
        !registerSurvivesBetween(instructions, keyRegister, headerKeyIndex, addRequestPropertyIndex) ||
        valueDefinitionIndex == null ||
        valueResult?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        valueSourceReference?.returnType != STRING ||
        valueSource?.opcode !in invokeOpcodes ||
        hasIncomingEdge(instructions, guardedStart, addRequestPropertyIndex, handlerAddresses)
    ) {
        return null
    }

    return CertificateHeaderInjection(
        insertionIndex = addRequestPropertyIndex,
        valueRegister = call.registerE,
    )
}

private val invokeOpcodes = setOf(
    Opcode.INVOKE_VIRTUAL,
    Opcode.INVOKE_DIRECT,
    Opcode.INVOKE_STATIC,
    Opcode.INVOKE_INTERFACE,
    Opcode.INVOKE_SUPER,
    Opcode.INVOKE_VIRTUAL_RANGE,
    Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_STATIC_RANGE,
    Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_SUPER_RANGE,
)

/** [fromIndex] から [toIndex] まで（両端を含む）のどれかが、分岐先か例外 handler の先頭かどうか。 */
private fun hasIncomingEdge(
    instructions: List<Instruction>,
    fromIndex: Int,
    toIndex: Int,
    handlerAddresses: Set<Int>,
): Boolean {
    val entries = branchTargetAddresses(instructions) + handlerAddresses
    return (fromIndex..toIndex).any { index -> instructionAddress(instructions, index) in entries }
}

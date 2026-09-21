package dev.utaa.linimal.patches.features.push

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FisCertificateHeaderPatchTest {
    private val string = "Ljava/lang/String;"

    private fun constString(register: Int, value: String) =
        ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    private fun addRequestProperty(connection: Int, key: Int, value: Int) = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        3,
        connection, key, value, 0, 0,
        ImmutableMethodReference(
            "Ljava/net/HttpURLConnection;",
            "addRequestProperty",
            listOf(string, string),
            "V",
        ),
    )

    private fun fingerprintHash(result: Int): List<Instruction> = listOf(
        ImmutableInstruction35c(
            Opcode.INVOKE_DIRECT,
            1,
            4, 0, 0, 0, 0,
            ImmutableMethodReference("Lct/c;", "a", emptyList<String>(), string),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, result),
    )

    /** FIS の `addRequestProperty("X-Android-Cert", getFingerprintHashForPackage())` に相当する形。 */
    private fun referenceShape(): List<Instruction> =
        listOf(constString(1, CERTIFICATE_HEADER)) +
            fingerprintHash(2) +
            addRequestProperty(connection = 0, key = 1, value = 2)

    @Test
    fun `the value register of the certificate header is replaced before the call`() {
        val injection = certificateHeaderInjection(referenceShape(), headerKeyIndex = 0, addRequestPropertyIndex = 3)

        assertEquals(CertificateHeaderInjection(insertionIndex = 3, valueRegister = 2), injection)
    }

    @Test
    fun `another header key is not touched`() {
        val instructions = listOf(constString(1, "X-Android-Package")) +
            fingerprintHash(2) +
            addRequestProperty(connection = 0, key = 1, value = 2)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 3))
    }

    /** キーの register が途中で上書きされると、呼び出しは別のヘッダーを付けている可能性があります。 */
    @Test
    fun `an overwritten key register is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            constString(1, "X-Android-Package") +
            fingerprintHash(2) +
            addRequestProperty(connection = 0, key = 1, value = 2)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 4))
    }

    @Test
    fun `a value register shared with the key is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            addRequestProperty(connection = 0, key = 1, value = 1)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 1))
    }

    /** 呼び出しが分岐先だと、その経路は挿入した hook を通らずに元の値を送ります。 */
    @Test
    fun `a call that is a branch target is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            fingerprintHash(2) +
            ImmutableInstruction10t(Opcode.GOTO, 1) +
            addRequestProperty(connection = 0, key = 1, value = 2)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 4))
    }

    /** 呼び出しの手前で合流する経路では、キーが `X-Android-Cert` である保証がありません。 */
    @Test
    fun `a branch target between the key and the call is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            ImmutableInstruction10t(Opcode.GOTO, 1) +
            fingerprintHash(2) +
            addRequestProperty(connection = 0, key = 1, value = 2)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 4))
    }

    @Test
    fun `a call at an exception handler is rejected`() {
        val instructions = referenceShape()
        val callAddress = instructions.take(3).sumOf { it.codeUnits }

        assertNull(
            certificateHeaderInjection(
                instructions,
                headerKeyIndex = 0,
                addRequestPropertyIndex = 3,
                handlerAddresses = setOf(callAddress),
            ),
        )
    }

    @Test
    fun `a value register shared with the connection is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            fingerprintHash(0) +
            addRequestProperty(connection = 0, key = 1, value = 0)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 3))
    }

    /** 値が計算結果でなく定数なら、置き換えてよい証明書の値だと確かめられません。 */
    @Test
    fun `a value that is not a String call result is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            constString(2, "constant") +
            addRequestProperty(connection = 0, key = 1, value = 2)

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 2))
    }

    @Test
    fun `a call with a different argument count is rejected`() {
        val instructions = listOf(constString(1, CERTIFICATE_HEADER)) +
            fingerprintHash(2) +
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                2,
                0, 1, 0, 0, 0,
                ImmutableMethodReference("Ljava/net/HttpURLConnection;", "addRequestProperty", listOf(string), "V"),
            )

        assertNull(certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 3))
    }

    @Test
    fun `a jumbo string key is accepted`() {
        val instructions = listOf(
            ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, 1, ImmutableStringReference(CERTIFICATE_HEADER)),
        ) + fingerprintHash(2) + addRequestProperty(connection = 0, key = 1, value = 2)

        assertEquals(
            CertificateHeaderInjection(insertionIndex = 3, valueRegister = 2),
            certificateHeaderInjection(instructions, headerKeyIndex = 0, addRequestPropertyIndex = 3),
        )
    }

    @Test
    fun `mismatched indexes are rejected`() {
        assertNull(certificateHeaderInjection(referenceShape(), headerKeyIndex = 3, addRequestPropertyIndex = 0))
        assertNull(certificateHeaderInjection(referenceShape(), headerKeyIndex = 0, addRequestPropertyIndex = 9))
    }

    @Test
    fun `only the uppercase hex form that Firebase sends is accepted`() {
        assertTrue(isOriginalCertificateSha1("0123456789ABCDEF0123456789ABCDEF01234567"))
        assertFalse(isOriginalCertificateSha1(""))
        assertFalse(isOriginalCertificateSha1("0123456789abcdef0123456789abcdef01234567"))
        assertFalse(isOriginalCertificateSha1("01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67"))
    }

    /** 定数が空なら LINE を変更しないことを、runtime parser が受け入れる DISABLED で表します。 */
    @Test
    fun `an unconfigured certificate is reported as disabled`() {
        val record = fisCertificateNotConfiguredRecord()

        assertEquals(PatchStatus.DISABLED, record.status)
        assertEquals(0, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `a configured certificate constant has the Firebase form`() {
        val certificate = Constants.LINE_ORIGINAL_CERTIFICATE_SHA1
        assertTrue(certificate.isEmpty() || isOriginalCertificateSha1(certificate))
    }

    @Test
    fun `push notifications keep their own feature id`() {
        assertEquals(FeatureId.PUSH_NOTIFICATIONS, PatchId.FIS_CERTIFICATE_HEADER.featureId)
        assertEquals("linimal.push-notifications", PatchId.FIS_CERTIFICATE_HEADER.featureId.value)
    }
}

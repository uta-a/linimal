package dev.utaa.linimal.patches.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchStatusCollectorTest {
    @Test
    fun `exact target count is OK`() {
        assertEquals(PatchStatus.OK, PatchStatusCollector.statusFor(1, 1))
    }

    @Test
    fun `missing optional targets are partial`() {
        assertEquals(PatchStatus.PARTIAL, PatchStatusCollector.statusFor(2, 1))
    }

    @Test
    fun `missing all targets is target not found`() {
        assertEquals(PatchStatus.TARGET_NOT_FOUND, PatchStatusCollector.statusFor(1, 0))
    }

    @Test
    fun `multiple matches for a unique target are an error`() {
        assertEquals(PatchStatus.ERROR, PatchStatusCollector.statusFor(1, 2))
    }

    @Test
    fun `class names and message content are omitted from reasons`() {
        assertEquals("Details omitted.", PatchStatusCollector.sanitizeReason("com.example.internal.Target"))
        assertEquals("Details omitted.", PatchStatusCollector.sanitizeReason("message content received"))
    }

    @Test
    fun `report is deterministic and excludes unsafe details`() {
        val collector = PatchStatusCollector()
        collector.record(PatchId.NO_OP_PROBE, 0, 0, "https://example.invalid/?token=secret")
        collector.record(PatchId.LINIMAL, 0, 0, "Linimal foundation installed.")

        val report = collector.toJson()

        assertTrue(report.contains("\"schemaVersion\": 1"))
        assertTrue(report.indexOf(PatchId.LINIMAL.value) < report.indexOf(PatchId.NO_OP_PROBE.value))
        assertTrue(report.contains("Details omitted."))
        assertFalse(report.contains("example.invalid"))
        assertFalse(report.contains("secret"))
    }
}

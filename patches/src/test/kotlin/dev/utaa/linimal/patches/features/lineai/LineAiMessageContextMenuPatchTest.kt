package dev.utaa.linimal.patches.features.lineai

import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class LineAiMessageContextMenuPatchTest {
    @Test
    fun `multiple context suppliers remain an explicit error`() {
        val record = lineAiMessageContextMenuUnappliedRecord(
            2,
            "LineAiMessageContextMenuSupplierNotUnique",
        )

        assertEquals(PatchId.LINE_AI_MESSAGE_CONTEXT_MENU, record.patchId)
        assertEquals(FeatureId.LINE_AI_MESSAGE_CONTEXT_MENU, record.featureId)
        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
    }

    @Test
    fun `missing context supplier remains target not found`() {
        val record = lineAiMessageContextMenuUnappliedRecord(
            0,
            "LineAiMessageContextMenuSupplierNotUnique",
        )

        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(0, record.actualTargetCount)
    }
}

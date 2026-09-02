package dev.utaa.linimal.patches.features.lineai

import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class LineAiGalleryViewerPatchTest {
    @Test
    fun `multiple gallery binders remain an explicit error`() {
        val record = lineAiGalleryViewerUnappliedRecord(2, "LineAiGalleryViewerBinderNotUnique")

        assertEquals(PatchId.LINE_AI_GALLERY_VIEWER, record.patchId)
        assertEquals(FeatureId.LINE_AI_GALLERY_VIEWER, record.featureId)
        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
    }

    @Test
    fun `missing gallery binder remains target not found`() {
        val record = lineAiGalleryViewerUnappliedRecord(0, "LineAiGalleryViewerBinderNotUnique")

        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(0, record.actualTargetCount)
    }
}

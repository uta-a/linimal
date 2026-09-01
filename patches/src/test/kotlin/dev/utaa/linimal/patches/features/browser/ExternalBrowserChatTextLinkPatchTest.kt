package dev.utaa.linimal.patches.features.browser

import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalBrowserChatTextLinkPatchTest {
    @Test
    fun `multiple target matches retain the actual count as an error`() {
        val record = externalBrowserUnappliedRecord(2, "ExternalBrowserTargetNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
        assertEquals(FeatureId.EXTERNAL_BROWSER, record.featureId)
    }

    @Test
    fun `missing target remains target not found`() {
        val record = externalBrowserUnappliedRecord(0, "ExternalBrowserTargetNotUnique")

        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(0, record.actualTargetCount)
    }
}

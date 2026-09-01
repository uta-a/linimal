package dev.utaa.linimal.patches.features.premium

import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PremiumUnsendPromotionPatchTest {
    @Test
    fun `multiple matches preserve the actual count for a valid error report`() {
        val record = premiumUnsendUnappliedRecord(2, "PremiumUnsendTargetNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
    }

    @Test
    fun `missing target remains target not found`() {
        val record = premiumUnsendUnappliedRecord(0, "PremiumUnsendTargetNotUnique")

        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(0, record.actualTargetCount)
    }
}

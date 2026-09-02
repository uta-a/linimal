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

    /**
     * 対象は 1 件見つかったが register が足りず注入できなかった場合。ここで TARGET_NOT_FOUND を
     * 記録すると `actualTargetCount == expectedTargetCount` になり、runtime の parser が
     * 「一致するなら OK のはず」として **report 全体を拒否**する。全機能が使えなくなるため、
     * 見つかったうえで適用できなかったことを示す ERROR でなければならない。
     */
    @Test
    fun `a found but unpatchable target is an error, not target not found`() {
        val record = premiumUnsendUnappliedRecord(1, "PremiumUnsendRegisterUnavailable")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(1, record.actualTargetCount)
    }
}

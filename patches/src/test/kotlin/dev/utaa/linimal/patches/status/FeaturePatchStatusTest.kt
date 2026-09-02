package dev.utaa.linimal.patches.status

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「注入していないのに適用済みとして記録してしまう」取りこぼしを固定するテストです。
 *
 * <p>件数から status を導く [recordFeatureStatus] は、`actual == expected` を OK にします。
 * 対象が 1 件見つかったうえで注入しなかった経路でこれを使うと、設定 UI がその機能を
 * 利用可能として表示し、トグルを操作しても何も起きない状態になります。</p>
 */
class FeaturePatchStatusTest {
    @Test
    fun `a found but unapplied target is recorded as an error, not as applied`() {
        patchStatusCollector.reset()

        recordUnappliedFeatureStatus(
            listOf(PatchId.CHAT_MENU_CALENDAR),
            expectedTargetCount = 1,
            matchCount = 1,
            reason = "ChatMenuCalendarItemNotUnique",
        )

        val record = patchStatusCollector.snapshot().single()
        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(1, record.actualTargetCount)
    }

    @Test
    fun `a missing target stays target not found`() {
        patchStatusCollector.reset()

        recordUnappliedFeatureStatus(
            listOf(PatchId.CHAT_MENU_CALENDAR),
            expectedTargetCount = 1,
            matchCount = 0,
            reason = "ChatMenuCalendarItemNotUnique",
        )

        val record = patchStatusCollector.snapshot().single()
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `an ambiguous target stays an error with its real match count`() {
        patchStatusCollector.reset()

        recordUnappliedFeatureStatus(
            listOf(PatchId.CHAT_MENU_LINE_GIFT),
            expectedTargetCount = 1,
            matchCount = 3,
            reason = "ChatMenuGiftItemNotUnique",
        )

        val record = patchStatusCollector.snapshot().single()
        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(3, record.actualTargetCount)
    }
}

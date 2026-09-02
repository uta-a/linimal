package dev.utaa.linimal.patches.features.browser

import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.recordFeatureStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * target が一意でなかったときの status 契約を固定します。
 *
 * <p>patch 本体は [recordFeatureStatus] で件数をそのまま記録するため、このテストも同じ経路を通します。
 * テスト専用の helper を経由すると、patch 側の記録方法が変わってもテストが気づけません。</p>
 */
class ExternalBrowserChatTextLinkPatchTest {
    @Test
    fun `multiple target matches retain the actual count as an error`() {
        patchStatusCollector.reset()

        recordFeatureStatus(
            listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
            expectedTargetCount = 1,
            actualTargetCount = 2,
            reason = "ExternalBrowserTargetNotUnique",
        )

        val record = patchStatusCollector.snapshot().single()
        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
        assertEquals(FeatureId.EXTERNAL_BROWSER, record.featureId)
    }

    @Test
    fun `missing target remains target not found`() {
        patchStatusCollector.reset()

        recordFeatureStatus(
            listOf(PatchId.EXTERNAL_BROWSER_CHAT_TEXT_LINK),
            expectedTargetCount = 1,
            actualTargetCount = 0,
            reason = "ExternalBrowserTargetNotUnique",
        )

        val record = patchStatusCollector.snapshot().single()
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(0, record.actualTargetCount)
    }
}

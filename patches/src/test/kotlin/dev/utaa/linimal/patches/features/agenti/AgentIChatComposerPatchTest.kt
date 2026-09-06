package dev.utaa.linimal.patches.features.agenti

import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusCollector
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIChatComposerPatchTest {
    @Test
    fun `missing chip bar target reports target not found`() {
        val record = agentIChatComposerUnappliedRecord(0, "AgentIChatComposerChipBarAccessorNotUnique")

        assertEquals(PatchId.AGENT_I_CHAT_COMPOSER, record.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `multiple chip bar targets preserve the actual cardinality as an error`() {
        val record = agentIChatComposerUnappliedRecord(2, "AgentIChatComposerChipBarAccessorNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
    }

    @Test
    fun `single chip bar target is the whole feature`() {
        // 26.14.0 で入力欄の Agent i ボタンが LINE 側から削除されたため、対象は候補チップ 1 箇所だけです。
        assertEquals(PatchStatus.OK, PatchStatusCollector.statusFor(1, 1))
        assertEquals(PatchStatus.TARGET_NOT_FOUND, PatchStatusCollector.statusFor(1, 0))
    }
}

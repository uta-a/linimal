package dev.utaa.linimal.patches.features.agenti

import dev.utaa.linimal.patches.status.PatchStatus
import dev.utaa.linimal.patches.status.PatchStatusCollector
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIChatComposerPatchTest {
    @Test
    fun `missing composite target reports target not found`() {
        val record = agentIChatComposerUnappliedRecord(0, "AgentIChatComposerCompositeTargetNotUnique")

        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(2, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `multiple composite targets preserve the actual cardinality as an error`() {
        val record = agentIChatComposerUnappliedRecord(2, "AgentIChatComposerCompositeTargetNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(2, record.expectedTargetCount)
        assertEquals(2, record.actualTargetCount)
    }

    @Test
    fun `chip bar target alone missing stays partial instead of error`() {
        // composer button の gate だけ適用できた場合でも feature は利用可能なままにします。
        assertEquals(PatchStatus.PARTIAL, PatchStatusCollector.statusFor(2, 1))
        assertEquals(PatchStatus.OK, PatchStatusCollector.statusFor(2, 2))
    }
}

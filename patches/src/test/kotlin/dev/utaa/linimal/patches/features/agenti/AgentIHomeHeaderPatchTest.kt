package dev.utaa.linimal.patches.features.agenti

import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIHomeHeaderPatchTest {
    @Test
    fun `missing header supplier reports target not found`() {
        val record = agentIHomeHeaderUnappliedRecord(0, "AgentIHomeHeaderSupplierNotUnique")

        assertEquals(PatchId.AGENT_I_HOME_HEADER, record.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `multiple header suppliers report error`() {
        val record = agentIHomeHeaderUnappliedRecord(2, "AgentIHomeHeaderSupplierNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(2, record.actualTargetCount)
    }
}

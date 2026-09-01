package dev.utaa.linimal.patches.features.agenti

import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIWalletHeaderPatchTest {
    @Test
    fun `missing state supplier reports target not found`() {
        val record = agentIWalletHeaderUnappliedRecord(0, "AgentIWalletStateSupplierNotUnique")

        assertEquals(PatchId.AGENT_I_WALLET_HEADER, record.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `multiple state suppliers report error`() {
        val record = agentIWalletHeaderUnappliedRecord(2, "AgentIWalletStateSupplierNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(2, record.actualTargetCount)
    }
}

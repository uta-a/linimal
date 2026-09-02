package dev.utaa.linimal.patches.features.agenti

import dev.utaa.linimal.patches.status.FeatureId
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIChatListSearchPatchTest {
    @Test
    fun `missing search bar header reports target not found`() {
        val record = agentIChatListSearchUnappliedRecord(0, "AgentIChatListSearchHeaderNotUnique")

        assertEquals(PatchId.AGENT_I_CHAT_LIST_SEARCH, record.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, record.status)
        assertEquals(1, record.expectedTargetCount)
        assertEquals(0, record.actualTargetCount)
    }

    @Test
    fun `multiple search bar headers report error`() {
        val record = agentIChatListSearchUnappliedRecord(2, "AgentIChatListSearchHeaderNotUnique")

        assertEquals(PatchStatus.ERROR, record.status)
        assertEquals(2, record.actualTargetCount)
    }

    @Test
    fun `chat list search keeps its own feature id`() {
        assertEquals(
            FeatureId.AGENT_I_CHAT_LIST_SEARCH,
            PatchId.AGENT_I_CHAT_LIST_SEARCH.featureId,
        )
        assertEquals(
            "linimal.agent-i-chat-list-search",
            PatchId.AGENT_I_CHAT_LIST_SEARCH.featureId.value,
        )
    }
}

package com.cone.agent.data.repository

import com.cone.agent.data.remote.FREE_PROVIDER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationModelSelectionTest {
    private val free = SelectedAgentModel(FREE_PROVIDER_ID, "free-model")
    private val chat = SelectedAgentModel(4L, "chat-model")
    private val agent = SelectedAgentModel(5L, "agent-model")
    private val catalog = listOf(free.modelId, "another-model")

    @Test
    fun `free catalog supplies a default only in the absence of another choice`() {
        assertEquals(free, pickConversationModel(null, null, catalog))
        assertEquals(agent, pickConversationModel(null, agent, catalog))
        assertEquals(chat, pickConversationModel(chat, agent, catalog))
        assertNull(pickConversationModel(null, null, emptyList()))
    }

    @Test
    fun `retired free model never redirects a request to a user provider`() {
        assertNull(pickConversationModel(free, agent, emptyList()))
        val replacement = SelectedAgentModel(FREE_PROVIDER_ID, "replacement")
        assertEquals(replacement, pickConversationModel(free, agent, listOf(replacement.modelId)))
        assertEquals(free, pickConversationModel(free, agent, catalog))
    }
}

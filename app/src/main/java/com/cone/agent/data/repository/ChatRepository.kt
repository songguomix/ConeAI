package com.cone.agent.data.repository

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.local.dao.ConversationDao
import com.cone.agent.data.local.dao.MessageDao
import com.cone.agent.data.local.entity.ConversationEntity
import com.cone.agent.data.local.entity.MessageEntity
import com.cone.agent.domain.model.ConversationMode
import com.cone.agent.domain.model.ConversationSummary
import com.cone.agent.domain.model.Sender
import com.cone.agent.domain.model.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists chats grouped into conversations. The 智能体 (agent) and 问答 (Q&A) flows are kept in
 * **separate** conversations: each mode has its own "current" conversation that receives new messages,
 * so the two threads never mix. Past conversations are browsable via [conversations] (each tagged with
 * its [ConversationMode]) and can be reopened with [switchTo]. The app starts a fresh conversation per
 * mode on each launch ([startFreshConversation]).
 */
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
) {
    private val currentAgentId = MutableStateFlow<Long?>(null)
    private val currentAskId = MutableStateFlow<Long?>(null)

    private val mutex = Mutex()

    private fun currentFlow(mode: ConversationMode) =
        if (mode == ConversationMode.AGENT) currentAgentId else currentAskId

    /** Messages of the agent (智能体) mode's current conversation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val agentMessages: Flow<List<UiMessage>> = currentAgentId.flatMapLatest { observeMessages(it) }

    /** Messages of the Q&A (问答) mode's current conversation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val askMessages: Flow<List<UiMessage>> = currentAskId.flatMapLatest { observeMessages(it) }

    private fun observeMessages(id: Long?): Flow<List<UiMessage>> =
        if (id == null) flowOf(emptyList())
        else messageDao.observeByConversation(id).map { list -> list.map { it.toDomain() } }

    /** Past conversations that contain messages, newest first — each tagged with its mode. */
    val conversations: Flow<List<ConversationSummary>> =
        conversationDao.observeNonEmpty().map { list -> list.map { it.toSummary() } }

    suspend fun add(
        sender: Sender,
        text: String,
        mode: ConversationMode,
        imagePath: String? = null,
        isError: Boolean = false,
        elapsedMs: Long? = null,
        tokens: Int? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        sources: String? = null,
    ): Long {
        val conversationId = requireCurrentConversation(mode)
        val id = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                sender = sender.name,
                text = text,
                imagePath = imagePath,
                isError = isError,
                elapsedMs = elapsedMs,
                tokens = tokens,
                fileName = fileName,
                fileSize = fileSize,
                sources = sources,
            ),
        )
        conversationDao.touch(conversationId, System.currentTimeMillis())
        // Title the conversation after the first thing the user actually asked for.
        if (sender == Sender.USER && text.isNotBlank()) {
            conversationDao.setTitleIfEmpty(conversationId, text.trim().take(40))
        }
        return id
    }

    /**
     * Keyword search across all saved conversations, for 问答's recall_history tool. [query] is
     * split into up to 4 whitespace-separated terms; each term's hits are merged (deduped by
     * message id, newest first) so 「上次 旅行 计划」 finds messages matching any of the words.
     */
    suspend fun searchHistory(query: String, limit: Int = 24): List<com.cone.agent.data.local.dao.MessageSearchHit> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
        if (terms.isEmpty()) return emptyList()
        return terms
            .flatMap { messageDao.search(it, limit) }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /** Clears the given mode's current conversation messages (leaving an empty session). */
    suspend fun clear(mode: ConversationMode) {
        val id = currentFlow(mode).value ?: return
        messageDao.clearConversation(id)
    }

    /**
     * Wipes every conversation and message across both modes, then opens a fresh empty session per
     * mode so the UI has a current conversation to write into. Backs "清除所有历史对话".
     */
    suspend fun clearAll() {
        messageDao.deleteAll()
        conversationDao.deleteAll()
        startNewConversation(ConversationMode.AGENT)
        startNewConversation(ConversationMode.ASK)
    }

    /** Prunes unused (empty) conversations and opens a brand-new one per mode. Called on app launch. */
    suspend fun startFreshConversation() {
        conversationDao.deleteEmpty()
        startNewConversation(ConversationMode.AGENT)
        startNewConversation(ConversationMode.ASK)
    }

    /** Opens a new empty conversation for [mode] and makes it that mode's current one. */
    suspend fun startNewConversation(mode: ConversationMode): Long = mutex.withLock {
        val now = System.currentTimeMillis()
        val id = conversationDao.insert(
            ConversationEntity(mode = mode.name, createdAt = now, updatedAt = now),
        )
        currentFlow(mode).value = id
        id
    }

    /** Reopens a past conversation (e.g. from history) as the current one for its [mode]. */
    fun switchTo(conversationId: Long, mode: ConversationMode) {
        currentFlow(mode).value = conversationId
    }

    suspend fun deleteConversation(conversationId: Long) {
        messageDao.clearConversation(conversationId)
        conversationDao.delete(conversationId)
        if (currentAgentId.value == conversationId) startNewConversation(ConversationMode.AGENT)
        if (currentAskId.value == conversationId) startNewConversation(ConversationMode.ASK)
    }

    private suspend fun requireCurrentConversation(mode: ConversationMode): Long = mutex.withLock {
        currentFlow(mode).value ?: run {
            val now = System.currentTimeMillis()
            val id = conversationDao.insert(
                ConversationEntity(mode = mode.name, createdAt = now, updatedAt = now),
            )
            currentFlow(mode).value = id
            id
        }
    }

    private fun MessageEntity.toDomain() = UiMessage(
        id = id,
        sender = runCatching { Sender.valueOf(sender) }.getOrDefault(Sender.SYSTEM),
        text = text,
        timestamp = timestamp,
        imagePath = imagePath,
        isError = isError,
        elapsedMs = elapsedMs,
        tokens = tokens,
        fileName = fileName,
        fileSize = fileSize,
        sources = sources,
    )

    private fun ConversationEntity.toSummary() = ConversationSummary(
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: LocaleHelper.string(context, R.string.untitled_conversation),
        updatedAt = updatedAt,
        mode = runCatching { ConversationMode.valueOf(mode) }.getOrDefault(ConversationMode.ASK),
    )
}

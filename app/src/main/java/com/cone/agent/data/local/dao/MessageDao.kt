package com.cone.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cone.agent.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** One cross-conversation search hit (message + its conversation's title), for 问答 recall_history. */
data class MessageSearchHit(
    val id: Long,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val conversationId: Long,
    val title: String?,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeByConversation(conversationId: Long): Flow<List<MessageEntity>>

    /** Keyword search across every conversation; only real user/assistant turns, newest first. */
    @Query(
        "SELECT m.id AS id, m.text AS text, m.sender AS sender, m.timestamp AS timestamp, " +
            "m.conversationId AS conversationId, c.title AS title " +
            "FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE m.text LIKE '%' || :keyword || '%' AND m.isError = 0 " +
            "AND m.sender IN ('USER', 'AGENT') " +
            "ORDER BY m.timestamp DESC LIMIT :limit",
    )
    suspend fun search(keyword: String, limit: Int): List<MessageSearchHit>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Long)

    /** Wipes every message across all conversations (used by "清除所有历史对话"). */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

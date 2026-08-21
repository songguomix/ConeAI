package com.cone.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cone.agent.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id AND (title IS NULL OR title = '')")
    suspend fun setTitleIfEmpty(id: Long, title: String)

    /** Conversations that actually contain at least one message, newest first. */
    @Query(
        "SELECT * FROM conversations " +
            "WHERE id IN (SELECT DISTINCT conversationId FROM messages) " +
            "ORDER BY updatedAt DESC",
    )
    fun observeNonEmpty(): Flow<List<ConversationEntity>>

    /** Removes conversations that have no messages (e.g. an opened-but-unused session). */
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT DISTINCT conversationId FROM messages)")
    suspend fun deleteEmpty()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Wipes every conversation (used by "清除所有历史对话"). Pair with [MessageDao.deleteAll]. */
    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

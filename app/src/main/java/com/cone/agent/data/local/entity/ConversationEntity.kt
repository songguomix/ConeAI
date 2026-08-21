package com.cone.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A single chat session. Messages reference their conversation via [MessageEntity.conversationId]. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Derived from the first user message; null until one exists. */
    val title: String? = null,
    /** Which mode this conversation belongs to — [com.cone.agent.domain.model.ConversationMode] name. */
    val mode: String = "ASK",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

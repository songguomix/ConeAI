package com.cone.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long = 0,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val isError: Boolean = false,
    /** For answer messages: generation time (ms) and total tokens, shown as a footer. */
    val elapsedMs: Long? = null,
    val tokens: Int? = null,
    /** For user messages with a file attachment: shown as a separate file card in the conversation. */
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** For 问答 answers grounded by 联网搜索: "title\turl" lines, rendered as a Grok-style sources card. */
    val sources: String? = null,
)

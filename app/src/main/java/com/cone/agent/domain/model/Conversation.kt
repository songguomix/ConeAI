package com.cone.agent.domain.model

/** Who produced a line shown in the chat / execution log. */
enum class Sender { USER, AGENT, SYSTEM, TOOL }

/** Which kind of conversation a session belongs to: the automation agent, or plain Q&A. */
enum class ConversationMode { AGENT, ASK }

/** A single entry rendered in the chat + execution record area. */
data class UiMessage(
    val id: Long = 0,
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val isError: Boolean = false,
    /** Answer footer (Claude-Code style): generation time in ms and total tokens, when known. */
    val elapsedMs: Long? = null,
    val tokens: Int? = null,
    /** Attached file shown as a separate card on a user message. */
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** 联网搜索 grounding sources for an answer, encoded as "title\turl" lines (see ChatComponents). */
    val sources: String? = null,
)

/** A past chat session shown in the history list. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val mode: ConversationMode,
)

/** A high level task the user asked the agent to perform. */
data class AgentTask(
    val id: Long = 0,
    val instruction: String,
    val status: TaskStatus = TaskStatus.IDLE,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TaskStatus { IDLE, RUNNING, PAUSED, WAITING_CONFIRM, OBSERVING, SUCCEEDED, FAILED, CANCELLED }

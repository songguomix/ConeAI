package com.cone.agent.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Wire types for the ConeCode desktop-remote bridge. They mirror, field-for-field, the snapshot the
 * desktop renderer publishes (`src/core/remote/bridge.ts` -> `buildSnapshot`) and the commands it
 * accepts (`handleCommand`). The desktop stays the single source of truth: this app only renders the
 * snapshot and posts intent back; no agent logic lives here.
 *
 * All ids are UUID strings on the desktop side, so they round-trip verbatim.
 */

/** One SSE frame: `{ "type": "state", "snapshot": { ... } }`. */
@Serializable
data class RemoteFrame(
    val type: String = "",
    val snapshot: RemoteSnapshot? = null,
)

@Serializable
data class RemoteSnapshot(
    val conversations: List<RemoteConversation> = emptyList(),
    val runningConversationId: String? = null,
    val activeConversationId: String? = null,
    val messages: List<RemoteMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingContent: String? = null,
    val streamingReasoningContent: String? = null,
    val streamingStatus: String? = null,
    val streamingToolName: String? = null,
    val todos: List<RemoteTodo> = emptyList(),
    val changes: List<RemoteChange> = emptyList(),
    val model: RemoteModel? = null,
    /** All selectable models on the desktop, for the phone's model picker. */
    val models: List<RemoteModel> = emptyList(),
    /** The desktop's resolved theme: "light" or "dark". Null on older desktops. */
    val theme: String? = null,
    /** The open project root, where the phone's file browser starts. Null if no folder is open. */
    val rootPath: String? = null,
    /** Reasoning effort: "low" | "medium" | "high". */
    val reasoningEffort: String? = null,
)

/** One directory entry from a `listDir` reply. */
@Serializable
data class RemoteEntry(
    val name: String = "",
    val path: String = "",
    val isDirectory: Boolean = false,
)

/** The saved login (pairing URL + password), encrypted at rest and unlocked by biometrics. */
@Serializable
data class SavedLogin(
    val url: String = "",
    val password: String = "",
)

@Serializable
data class RemoteConversation(
    val id: String = "",
    val title: String? = null,
    val isRunning: Boolean = false,
    val changedFiles: List<String> = emptyList(),
)

@Serializable
data class RemoteToolCall(
    val id: String? = null,
    val name: String = "",
)

@Serializable
data class RemoteMessage(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val isToolResult: Boolean = false,
    val isLocalNotice: Boolean = false,
    val reasoningContent: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isSummary: Boolean = false,
    val summaryUpToId: String? = null,
    val toolCalls: List<RemoteToolCall> = emptyList(),
    /** Milliseconds the model spent on this reply (shown under assistant messages). */
    val thinkingTime: Long? = null,
    /** Total tokens used for this reply, if the provider reported usage. */
    val tokensUsed: Int? = null,
)

@Serializable
data class RemoteTodo(
    val content: String = "",
    val status: String = "pending",
)

@Serializable
data class RemoteChange(
    val id: String = "",
    val kind: String? = null,
    val filePath: String = "",
    val status: String = "pending",
    val newCode: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
    val description: String? = null,
    val cwd: String? = null,
)

@Serializable
data class RemoteModel(
    val id: String = "",
    val name: String = "",
    val providerId: String? = null,
)

/** Lenient parser shared by the SSE stream and the `/api/state` bootstrap fetch. */
val RemoteJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
}

/**
 * The commands the phone can post to `/cmd`. Each maps 1:1 to a `case` in the desktop bridge's
 * `handleCommand`. Serialized to a flat JSON object `{ "type": ..., <field>: ... }`.
 */
sealed class RemoteCommand(val type: String) {
    data class Send(val text: String) : RemoteCommand("send")
    object Stop : RemoteCommand("stop")
    data class SwitchConversation(val id: String) : RemoteCommand("switchConversation")
    object NewConversation : RemoteCommand("newConversation")
    data class DeleteConversation(val id: String) : RemoteCommand("deleteConversation")
    data class SetModel(val id: String, val providerId: String? = null) : RemoteCommand("setModel")
    data class SetReasoningEffort(val value: String) : RemoteCommand("setReasoningEffort")
    data class Approve(val id: String) : RemoteCommand("approve")
    data class Reject(val id: String) : RemoteCommand("reject")
    object ApproveAll : RemoteCommand("approveAll")
    object RejectAll : RemoteCommand("rejectAll")

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        when (this@RemoteCommand) {
            is Send -> put("text", JsonPrimitive(text))
            is SwitchConversation -> put("id", JsonPrimitive(id))
            is DeleteConversation -> put("id", JsonPrimitive(id))
            is SetModel -> {
                put("id", JsonPrimitive(id))
                providerId?.let { put("providerId", JsonPrimitive(it)) }
            }
            is SetReasoningEffort -> put("value", JsonPrimitive(value))
            is Approve -> put("id", JsonPrimitive(id))
            is Reject -> put("id", JsonPrimitive(id))
            else -> {}
        }
    }
}

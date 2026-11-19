package com.cone.agent.remote

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Upper bound for one desktop exec. Builds and test suites legitimately run for minutes. */
private const val EXEC_TIMEOUT_MS = 15 * 60 * 1000L

/** One-shot terminal command result from the desktop. */
data class CommandResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val cwd: String,
    /** No reply within the exec budget: the command may still be running on the desktop. */
    val timedOut: Boolean = false,
    /** Never left the phone because the mirror is not connected. */
    val disconnected: Boolean = false,
)

/** Connection lifecycle of the desktop-remote mirror. */
enum class RemotePhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR, NEEDS_PASSWORD }

data class RemoteUiState(
    val phase: RemotePhase = RemotePhase.DISCONNECTED,
    val connectionAttempt: Long = 0,
    /** Technical detail for the latest failure (shown under a localized title). */
    val errorMessage: String? = null,
    /** Token-free label of the connected/last target, e.g. "192.168.1.23:8723". */
    val endpointLabel: String? = null,
    val computers: List<RemoteComputer> = emptyList(),
    val activeComputerId: String? = null,
    val lastComputerId: String? = null,
    /** True when this session runs over plain http on a LAN address (no transport encryption). */
    val insecure: Boolean = false,
    val snapshot: RemoteSnapshot? = null,
) {
    /**
     * Show the control UI as soon as the connection succeeds (or once any snapshot has arrived),
     * so a successful connect jumps straight to the mirror even before the first state frame lands.
     */
    val showMirror: Boolean get() = phase == RemotePhase.CONNECTED || snapshot != null
}

/**
 * 远程控制页的 ViewModel。
 *
 * 真正的连接、自动重连与凭据持久化都在应用级单例 [RemoteSession] 里，
 * 这里只做界面契约：暴露 state、转发用户意图、提供文件/终端这类一次性命令。
 * 这样离开页面、切到后台、甚至旋转屏幕都不会把连接掐掉。
 */
@HiltViewModel
class RemoteViewModel @Inject internal constructor(
    private val session: RemoteSession,
    private val uuRemote: UuRemoteConnector,
) : ViewModel() {

    val state: StateFlow<RemoteUiState> = session.state

    fun connect(raw: String, password: String? = null, name: String? = null, replaceComputerId: String? = null) =
        session.connect(raw, password, name, replaceComputerId)

    fun connectComputer(id: String) = session.connectComputer(id)
    fun renameComputer(id: String, name: String) = session.renameComputer(id, name)
    fun removeComputer(id: String) = session.removeComputer(id)
    fun submitPassword(password: String) = session.submitPassword(password)

    fun disconnect() = session.disconnect()

    private fun send(command: RemoteCommand) {
        val active = session.client ?: return
        session.launch { active.send(command) }
    }

    fun sendMessage(text: String) {
        if (text.isNotBlank()) send(RemoteCommand.Send(text))
    }

    fun renameConversation(id: String, title: String) = send(RemoteCommand.RenameConversation(id, title))
    fun setViewMode(value: String) = send(RemoteCommand.SetViewMode(value))
    fun setAgentMode(value: String) = send(RemoteCommand.SetAgentMode(value))
    fun setPlanMode(value: Boolean) = send(RemoteCommand.SetPlanMode(value))
    fun setReviewMode(value: Boolean) = send(RemoteCommand.SetReviewMode(value))
    fun enqueueMessage(text: String) = send(RemoteCommand.EnqueueMessage(text))
    fun dequeueMessage(id: String) = send(RemoteCommand.DequeueMessage(id))
    fun steerQueuedMessage(conversationId: String, id: String) = send(RemoteCommand.SteerQueuedMessage(conversationId, id))
    fun setConversationArchived(id: String, archived: Boolean) = send(RemoteCommand.SetConversationArchived(id, archived))
    fun continueConversation(conversationId: String) = send(RemoteCommand.ContinueConversation(conversationId))
    fun compactConversation(conversationId: String) = send(RemoteCommand.CompactConversation(conversationId))
    fun clearConversationError(conversationId: String?) = send(RemoteCommand.ClearConversationError(conversationId))
    fun approveConversationPlan(conversationId: String, id: String) = send(RemoteCommand.ApproveConversationPlan(conversationId, id))
    fun conversationGoal(conversationId: String, action: String, text: String? = null) =
        send(RemoteCommand.ConversationGoal(conversationId, action, text))

    suspend fun submitConversationMessage(
        conversationId: String?,
        text: String,
        images: List<String>,
        queue: Boolean,
    ): Boolean {
        val result = session.client?.request(
            if (queue) "enqueueMessage" else "send",
            mapOf("text" to text),
            jsonFields = mapOf(
                "conversationId" to JsonPrimitive(conversationId),
                "images" to JsonArray(images.map { JsonPrimitive(it) }),
            ),
        )
        return result?.get("ok")?.jsonPrimitive?.booleanOrNull == true
    }

    suspend fun changeConversationMessage(conversationId: String, id: String, content: String?): Boolean {
        val fields = buildMap {
            put("conversationId", conversationId)
            put("id", id)
            content?.let { put("text", it) }
        }
        val result = session.client?.request(if (content == null) "resendConversationMessage" else "editConversationMessage", fields)
        return result?.get("ok")?.jsonPrimitive?.booleanOrNull == true
    }

    suspend fun conversationHistory(conversationId: String, beforeId: String): RemoteHistoryPage? {
        val result = session.client?.request("conversationHistory", mapOf("conversationId" to conversationId, "beforeId" to beforeId))
            ?: return null
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return runCatching { RemoteJson.decodeFromJsonElement(RemoteHistoryPage.serializer(), result) }.getOrNull()
    }

    suspend fun conversationHandoff(conversationId: String, includeTools: Boolean, includeResults: Boolean): RemoteHandoff? {
        val result = session.client?.request("conversationHandoff", mapOf("conversationId" to conversationId), jsonFields = mapOf(
            "includeTools" to JsonPrimitive(includeTools), "includeToolResults" to JsonPrimitive(includeResults),
        )) ?: return null
        return runCatching { RemoteJson.decodeFromJsonElement(RemoteHandoff.serializer(), result) }.getOrNull()
    }

    suspend fun checkpointPreview(conversationId: String, changeId: String): RemoteCheckpointPreview? {
        val result = session.client?.request("conversationCheckpoint", mapOf(
            "action" to "preview", "conversationId" to conversationId, "id" to changeId,
        )) ?: return null
        return runCatching { RemoteJson.decodeFromJsonElement(RemoteCheckpointPreview.serializer(), result) }
            .getOrNull()?.takeIf { it.ok }
    }

    suspend fun restoreCheckpoint(conversationId: String, changeId: String, revision: String): String? =
        conversationChangeRequest("conversationCheckpoint", mapOf(
            "action" to "restore", "conversationId" to conversationId, "id" to changeId, "revision" to revision,
        ))

    suspend fun undoConversationChange(conversationId: String, changeId: String): String? =
        conversationChangeRequest("undoConversationChange", mapOf("conversationId" to conversationId, "id" to changeId))

    private suspend fun conversationChangeRequest(type: String, fields: Map<String, String>): String? {
        val result = session.client?.request(type, fields, timeoutMs = 120_000L)
        return if (result?.get("ok")?.jsonPrimitive?.booleanOrNull == true) null
        else result?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun conversationImage(conversationId: String, id: String, index: Int): String? {
        val result = session.client?.request(
            "conversationImage",
            mapOf("conversationId" to conversationId, "id" to id),
            jsonFields = mapOf("index" to JsonPrimitive(index)),
        ) ?: return null
        if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return result["dataUrl"]?.jsonPrimitive?.contentOrNull
    }

    suspend fun answerQuestion(question: RemoteQuestion, text: String): Boolean {
        val reply = session.client?.request("answerQuestion", mapOf(
            "id" to question.id, "conversationId" to question.conversationId, "text" to text,
        )) ?: return false
        return reply["ok"]?.jsonPrimitive?.booleanOrNull == true
    }

    suspend fun rsiControl(conversationId: String, action: String): Boolean {
        val reply = session.client?.request("rsiControl", mapOf("conversationId" to conversationId, "action" to action))
        return reply?.get("ok")?.jsonPrimitive?.booleanOrNull == true
    }

    suspend fun saveRsiDirection(conversationId: String, direction: RemoteRsiDirection): Boolean {
        val reply = session.client?.request("rsiDirection", mapOf("conversationId" to conversationId),
            jsonFields = mapOf("direction" to RemoteJson.encodeToJsonElement(direction)))
        return reply?.get("ok")?.jsonPrimitive?.booleanOrNull == true
    }

    internal val uuState = uuRemote.state
    internal val remoteComputerId: String?
        get() = session.client?.computerIdentity

    internal fun refreshUu() = uuRemote.refresh()
    internal fun bindUu(computerId: String) {
        if (computerId == remoteComputerId) uuRemote.bind(computerId)
    }
    internal fun controlUu(computerId: String, mode: UuRemoteProtocol.Mode) {
        if (computerId == remoteComputerId) uuRemote.control(computerId, mode)
    }
    internal fun forgetUu(computerId: String) = uuRemote.forget(computerId)
    internal fun stopWaitingUu(computerId: String) = uuRemote.stopWaiting(computerId)

    suspend fun changeDetail(id: String): Pair<String, String>? {
        val reply = session.client?.request("changeDetail", mapOf("id" to id)) ?: return null
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return (reply["originalCode"]?.jsonPrimitive?.contentOrNull ?: "") to
            (reply["newCode"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    fun stopGeneration() = send(RemoteCommand.Stop)
    fun newConversation() = send(RemoteCommand.NewConversation)
    fun switchConversation(id: String) = send(RemoteCommand.SwitchConversation(id))
    fun deleteConversation(id: String) = send(RemoteCommand.DeleteConversation(id))
    fun setModel(id: String, providerId: String? = null) = send(RemoteCommand.SetModel(id, providerId))
    fun setReasoningEffort(value: String) = send(RemoteCommand.SetReasoningEffort(value))
    fun approve(id: String) = send(RemoteCommand.Approve(id))
    fun reject(id: String) = send(RemoteCommand.Reject(id))
    fun approveAll() = send(RemoteCommand.ApproveAll)
    fun rejectAll() = send(RemoteCommand.RejectAll)

    // ---- File browser / editor / terminal (request → reply) ----

    internal suspend fun workspaceRequest(action: String, fields: Map<String, String> = emptyMap()): JsonObject? =
        session.client?.request("workspaceControl", fields + ("action" to action),
            timeoutMs = if (action == "workspaceAddRoot" && "path" !in fields) 90_000L else 30_000L)

    suspend fun listDir(path: String): List<RemoteEntry> {
        val reply = session.client?.request("listDir", mapOf("path" to path)) ?: return emptyList()
        val entries = reply["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull {
            runCatching { RemoteJson.decodeFromJsonElement(RemoteEntry.serializer(), it) }.getOrNull()
        }
    }

    /** Returns the file's text, or null if it couldn't be read (binary/missing). */
    suspend fun readFile(path: String): String? {
        val reply = session.client?.request("readFile", mapOf("path" to path)) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) reply["content"]?.jsonPrimitive?.contentOrNull ?: "" else null
    }

    suspend fun writeFile(path: String, content: String): Boolean {
        val reply = session.client?.request("writeFile", mapOf("path" to path, "content" to content)) ?: return false
        return reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    suspend fun runCommand(command: String, cwd: String?, timeoutMs: Long = EXEC_TIMEOUT_MS): CommandResult {
        val active = session.client ?: return CommandResult(false, "", "", -1, cwd ?: "", disconnected = true)
        val fields = buildMap {
            put("command", command)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        var sendFailed = false
        val reply = active.request("exec", fields, timeoutMs) { sendFailed = true }
            ?: return CommandResult(
                ok = false,
                stdout = "",
                stderr = "",
                exitCode = -1,
                cwd = cwd ?: "",
                timedOut = !sendFailed,
                disconnected = sendFailed,
            )
        return CommandResult(
            ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
            stdout = reply["stdout"]?.jsonPrimitive?.contentOrNull ?: "",
            stderr = reply["stderr"]?.jsonPrimitive?.contentOrNull ?: "",
            exitCode = reply["exitCode"]?.jsonPrimitive?.intOrNull ?: 0,
            cwd = reply["cwd"]?.jsonPrimitive?.contentOrNull ?: (cwd ?: ""),
        )
    }

    /** Upload a phone file (base64) to the desktop; returns the saved absolute path. */
    suspend fun uploadFile(name: String, base64: String): String? {
        val reply = session.client?.request("uploadFile", mapOf("name" to name, "data" to base64)) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) reply["path"]?.jsonPrimitive?.contentOrNull else null
    }

    /** Open a folder as the desktop project root. Null path → desktop shows a native picker. */
    suspend fun openFolder(path: String?): String? {
        val fields = if (path != null) mapOf("path" to path) else emptyMap()
        val reply = session.client?.request("openFolder", fields) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) reply["path"]?.jsonPrimitive?.contentOrNull else null
    }

    /**
     * Run work that must outlive a composable — a terminal command keeps running while the
     * user switches tabs, instead of being cancelled with the composition.
     */
    fun launchSession(block: suspend CoroutineScope.() -> Unit): Job = session.launch(block)

    internal suspend fun featureRequest(type: String, fields: Map<String, String>, values: Map<String, JsonElement> = emptyMap()): JsonObject? =
        session.client?.request(type, fields, jsonFields = values)
    internal suspend fun settingsRequest(
        fields: Map<String, String>,
        values: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    ): RemoteSettingsReply? {
        val active = session.client ?: return null
        val reply = active.request("remoteSettings", fields, timeoutMs = 180_000L, jsonFields = mapOf("values" to values))
            ?: return null
        return runCatching {
            RemoteJson.decodeFromJsonElement(RemoteSettingsReply.serializer(), reply)
        }.getOrNull()
    }

}

package com.cone.agent.remote

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
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
    /** Technical detail for the latest failure (shown under a localized title). */
    val errorMessage: String? = null,
    /** Token-free label of the connected/last target, e.g. "192.168.1.23:8723". */
    val endpointLabel: String? = null,
    /** The last successfully-entered pairing URL, for one-tap reconnect. */
    val savedUrl: String? = null,
    /** A biometric-protected saved login exists (enables the fingerprint/face unlock). */
    val canBiometric: Boolean = false,
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
class RemoteViewModel @Inject constructor(
    private val session: RemoteSession,
) : ViewModel() {

    val state: StateFlow<RemoteUiState> = session.state

    fun connect(raw: String, password: String? = null) = session.connect(raw, password)

    /** Reconnect to the remembered desktop, if any. */
    fun reconnect() = session.reconnect()

    /** Auto-restore when the remote screen comes back (skipped right after the user disconnects). */
    fun restore() = session.restore()

    /** After a successful biometric prompt: decrypt and connect with the saved url + password. */
    fun unlockSaved() = session.unlockSaved()

    /** Forget the saved (biometric) login and the reconnect URL. */
    fun clearSaved() = session.clearSaved()

    fun disconnect() = session.disconnect()

    private fun send(command: RemoteCommand) {
        val active = session.client ?: return
        session.launch { active.send(command) }
    }

    fun sendMessage(text: String) {
        if (text.isNotBlank()) send(RemoteCommand.Send(text))
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
}

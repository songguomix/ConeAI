package com.cone.agent.remote

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.R
import com.cone.agent.data.crypto.KeystoreManager
import com.cone.agent.data.crypto.Sealed
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject

/** One-shot terminal command result from the desktop. */
data class CommandResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val cwd: String,
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
    val snapshot: RemoteSnapshot? = null,
) {
    /**
     * Show the control UI as soon as the connection succeeds (or once any snapshot has arrived),
     * so a successful connect jumps straight to the mirror even before the first state frame lands.
     */
    val showMirror: Boolean get() = phase == RemotePhase.CONNECTED || snapshot != null
}

/**
 * Drives the ConeCode desktop-remote screen: parses a scanned/pasted pairing URL, keeps a live SSE
 * mirror of the desktop session in [state], and forwards user intent (send / stop / approve / switch
 * conversation …) back over `/cmd`. The desktop renderer remains the single source of truth.
 */
@HiltViewModel
class RemoteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val remoteTls: RemoteTls,
    private val keystore: KeystoreManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    // The remote talks HTTPS to a self-signed desktop cert, so trust the bundled pinned cert
    // (plus system CAs for tunnels). Derived once from the shared client.
    private val httpsClient: OkHttpClient by lazy {
        remoteTls.configure(okHttpClient.newBuilder()).build()
    }

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private var client: RemoteClient? = null
    private var streamJob: Job? = null

    // The credentials of the in-flight connection, persisted (encrypted) once it succeeds.
    private var lastUrl: String? = null
    private var lastPassword: String? = null
    private var savedThisSession = false

    init {
        viewModelScope.launch {
            settings.remoteEndpoint.collect { saved -> _state.update { it.copy(savedUrl = saved) } }
        }
        viewModelScope.launch {
            settings.hasRemoteSecret.collect { has -> _state.update { it.copy(canBiometric = has) } }
        }
    }

    /** Pair with the desktop using a scanned QR payload or a manually-entered URL, plus optional password. */
    fun connect(raw: String, password: String? = null) {
        val parsed = RemoteEndpoint.parse(raw)
        if (parsed == null) {
            _state.update {
                it.copy(phase = RemotePhase.ERROR, errorMessage = context.getString(R.string.remote_invalid_url))
            }
            return
        }
        // Prefer an explicitly-entered password (dialog); else use the one embedded in the QR.
        val endpoint = parsed.copy(password = password?.takeIf { it.isNotBlank() } ?: parsed.password)
        streamJob?.cancel()
        val newClient = RemoteClient(httpsClient, endpoint)
        client = newClient
        lastUrl = raw.trim()
        lastPassword = endpoint.password
        savedThisSession = false
        _state.update {
            it.copy(
                phase = RemotePhase.CONNECTING,
                errorMessage = null,
                endpointLabel = endpoint.display,
                snapshot = null,
            )
        }
        viewModelScope.launch { settings.setRemoteEndpoint(storableUrl(raw)) }
        streamJob = viewModelScope.launch {
            newClient.stream(
                onStatus = { status ->
                    _state.update { st ->
                        when (status) {
                            RemoteStatus.Connecting -> st.copy(phase = RemotePhase.CONNECTING)
                            RemoteStatus.Connected -> st.copy(phase = RemotePhase.CONNECTED, errorMessage = null)
                            RemoteStatus.AuthRequired -> st.copy(phase = RemotePhase.NEEDS_PASSWORD)
                            RemoteStatus.Kicked -> st.copy(
                                phase = RemotePhase.ERROR,
                                snapshot = null, // leave the mirror → back to the connect screen
                                errorMessage = context.getString(R.string.remote_kicked),
                            )
                            is RemoteStatus.Error -> st.copy(phase = RemotePhase.ERROR, errorMessage = status.message)
                        }
                    }
                },
                onSnapshot = { snap ->
                    _state.update { it.copy(phase = RemotePhase.CONNECTED, snapshot = snap, errorMessage = null) }
                    // First successful frame → remember this login (encrypted) for biometric unlock.
                    if (!savedThisSession) {
                        savedThisSession = true
                        persistSecret(lastUrl, lastPassword)
                    }
                },
                onStream = { delta ->
                    // Merge a token-growth delta into the current snapshot (no full re-send).
                    _state.update { st ->
                        val snap = st.snapshot ?: return@update st
                        st.copy(
                            snapshot = snap.copy(
                                isStreaming = delta.isStreaming,
                                streamingContent = delta.streamingContent,
                                streamingReasoningContent = delta.streamingReasoningContent,
                                streamingStatus = delta.streamingStatus,
                                streamingToolName = delta.streamingToolName,
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Reconnect to the remembered desktop, if any. */
    fun reconnect() {
        _state.value.savedUrl?.let { connect(it) }
    }

    /** After a successful biometric prompt: decrypt and connect with the saved url + password. */
    fun unlockSaved() {
        viewModelScope.launch {
            val (cipher, iv) = settings.readRemoteSecret() ?: return@launch
            val blob = keystore.decrypt(Sealed(cipher, iv))
            if (blob.isBlank()) return@launch
            val login = runCatching { RemoteJson.decodeFromString(SavedLogin.serializer(), blob) }.getOrNull() ?: return@launch
            if (login.url.isNotBlank()) connect(login.url, login.password.takeIf { it.isNotBlank() })
        }
    }

    /** Forget the saved (biometric) login and the reconnect URL. */
    fun clearSaved() {
        viewModelScope.launch {
            settings.clearRemoteSecret()
            settings.setRemoteEndpoint(null)
        }
    }

    /**
     * The pairing URL as persisted for one-tap reconnect: everything except the QR-embedded password
     * (`&p=`). The password only ever lands in the Keystore-encrypted [SavedLogin] — never in the
     * plaintext DataStore, which is included in device backups. Reconnecting from this URL against a
     * password-protected desktop yields a 401 → the existing password prompt / biometric unlock.
     */
    private fun storableUrl(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: return raw.trim()
        return url.newBuilder().removeAllQueryParameters("p").build().toString()
    }

    private fun persistSecret(url: String?, password: String?) {
        if (url.isNullOrBlank()) return
        viewModelScope.launch {
            val blob = RemoteJson.encodeToString(SavedLogin.serializer(), SavedLogin(url, password ?: ""))
            val sealed = keystore.encrypt(blob)
            if (sealed.cipherText.isNotBlank()) settings.setRemoteSecret(sealed.cipherText, sealed.iv)
        }
    }

    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        client = null
        _state.update {
            it.copy(phase = RemotePhase.DISCONNECTED, snapshot = null, errorMessage = null, endpointLabel = null)
        }
    }

    private fun send(command: RemoteCommand) {
        val active = client ?: return
        viewModelScope.launch { active.send(command) }
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
        val reply = client?.request("listDir", mapOf("path" to path)) ?: return emptyList()
        val entries = reply["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull {
            runCatching { RemoteJson.decodeFromJsonElement(RemoteEntry.serializer(), it) }.getOrNull()
        }
    }

    /** Returns the file's text, or null if it couldn't be read (binary/missing). */
    suspend fun readFile(path: String): String? {
        val reply = client?.request("readFile", mapOf("path" to path)) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) reply["content"]?.jsonPrimitive?.contentOrNull ?: "" else null
    }

    suspend fun writeFile(path: String, content: String): Boolean {
        val reply = client?.request("writeFile", mapOf("path" to path, "content" to content)) ?: return false
        return reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    suspend fun runCommand(command: String, cwd: String?): CommandResult {
        val fields = buildMap {
            put("command", command)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        val reply = client?.request("exec", fields)
            ?: return CommandResult(false, "", "timeout", 1, cwd ?: "")
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
        val reply = client?.request("uploadFile", mapOf("name" to name, "data" to base64)) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) reply["path"]?.jsonPrimitive?.contentOrNull else null
    }

    /** Open a folder as the desktop project root. Null path → desktop shows a native picker. */
    suspend fun openFolder(path: String?): String? {
        val fields = if (path != null) mapOf("path" to path) else emptyMap()
        val reply = client?.request("openFolder", fields) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) reply["path"]?.jsonPrimitive?.contentOrNull else null
    }

    override fun onCleared() {
        streamJob?.cancel()
    }
}

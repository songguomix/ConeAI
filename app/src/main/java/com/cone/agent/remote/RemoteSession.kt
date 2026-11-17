package com.cone.agent.remote

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.service.RemoteSessionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Holds the selected desktop connection across navigation and while Android is backgrounded. */
@Singleton
class RemoteSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val remoteTls: RemoteTls,
    private val directory: RemoteComputerDirectory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    /** Current command client, or null when offline. */
    @Volatile
    var client: RemoteClient? = null
        private set

    private var selectionJob: Job? = null
    private var pendingSelectionId: String? = null
    private var streamJob: Job? = null
    private var attempt: ConnectionAttempt? = null
    private var userDisconnected = false

    private data class ConnectionAttempt(
        val id: String,
        val raw: String,
        val endpoint: RemoteEndpoint,
        val nickname: String?,
        val replaceExisting: Boolean,
    )

    init {
        scope.launch {
            directory.initialize()
            directory.states.collect { saved ->
                _state.update {
                    it.copy(computers = saved.computers.map(SavedRemoteComputer::toPickerRow), lastComputerId = saved.lastComputerId)
                }
            }
        }
    }

    fun connect(raw: String, password: String? = null, name: String? = null, replaceComputerId: String? = null) {
        cancelSelection()
        val parsed = RemoteEndpoint.parse(raw)
        if (parsed == null) {
            val cleartext = raw.trim().startsWith("http://", ignoreCase = true)
            showError(if (cleartext) R.string.remote_cleartext_rejected else R.string.remote_invalid_url)
            return
        }
        val endpoint = parsed.copy(password = password?.takeIf { it.isNotBlank() } ?: parsed.password)
        pendingSelectionId = replaceComputerId
        selectionJob = scope.launch {
            val saved = directory.load()
            if (replaceComputerId != null && saved.hasOriginConflict(replaceComputerId, endpoint.base)) {
                pendingSelectionId = null
                showError(R.string.remote_computer_origin_conflict)
                return@launch
            }
            val replacement = saved.computers.firstOrNull { it.id == replaceComputerId }
            val existing = replacement ?: saved.computers.firstOrNull { it.origin == endpoint.base }
            startConnection(ConnectionAttempt(existing?.id ?: newComputerId(), raw.trim(), endpoint, name, replacement != null))
        }
    }

    fun connectComputer(id: String) {
        if (_state.value.activeComputerId == id && _state.value.phase == RemotePhase.CONNECTED) return
        cancelSelection()
        pendingSelectionId = id
        selectionJob = scope.launch { openComputer(directory.load(), id) }
    }

    private suspend fun openComputer(saved: SavedRemoteComputers, id: String): Boolean {
        val profile = saved.computers.firstOrNull { it.id == id } ?: return false
        val login = directory.login(profile)
        val parsed = login?.let { RemoteEndpoint.parse(it.url) }
        if (login == null || parsed == null || parsed.base != profile.origin) {
            showError(R.string.remote_invalid_url)
            return false
        }
        val endpoint = parsed.copy(password = login.password.takeIf { it.isNotBlank() } ?: parsed.password)
        startConnection(ConnectionAttempt(profile.id, login.url, endpoint, null, replaceExisting = false))
        return true
    }

    fun submitPassword(password: String) {
        if (_state.value.phase != RemotePhase.NEEDS_PASSWORD) return
        val pending = attempt ?: return
        val value = password.takeIf { it.isNotBlank() } ?: return
        startConnection(pending.copy(endpoint = pending.endpoint.copy(password = value)))
    }

    fun renameComputer(id: String, name: String) {
        scope.launch { directory.rename(id, name) }
    }

    fun removeComputer(id: String) {
        if (_state.value.activeComputerId == id) {
            disconnect()
        } else if (pendingSelectionId == id) {
            cancelSelection()
        }
        scope.launch { directory.remove(id) }
    }

    /** Restore the selected desktop if Android restarted the existing foreground service. */
    fun restore() {
        if (userDisconnected || client != null || selectionJob?.isActive == true) return
        selectionJob = scope.launch {
            val saved = directory.load()
            val id = saved.lastComputerId
            pendingSelectionId = id
            if (id == null || !openComputer(saved, id)) RemoteSessionService.stop(context)
        }
    }

    private fun startConnection(target: ConnectionAttempt) {
        pendingSelectionId = null
        if (client != null) RemoteSessionService.stop(context)
        stopStream()
        val endpoint = target.endpoint
        val httpsClient = remoteTls.configure(okHttpClient.newBuilder(), endpoint.fingerprint).build()
        val newClient = RemoteClient(httpsClient, endpoint)
        client = newClient
        attempt = target
        userDisconnected = false
        _state.update {
            it.copy(
                phase = RemotePhase.CONNECTING,
                connectionAttempt = it.connectionAttempt + 1,
                errorMessage = null,
                endpointLabel = endpoint.display,
                insecure = endpoint.insecure,
                activeComputerId = target.id,
                snapshot = null,
            )
        }
        var savedThisConnection = false
        var readyForMirror = !target.replaceExisting
        streamJob = scope.launch {
            newClient.stream(
                onStatus = { status ->
                    scope.launch {
                        if (client === newClient && (readyForMirror || status != RemoteStatus.Connected)) {
                            applyStatus(status, endpoint.display)
                        }
                    }
                },
                onSnapshot = { snap ->
                    scope.launch snapshot@{
                        if (client !== newClient) return@snapshot
                        if (readyForMirror) publishSnapshot(snap)
                        if (!savedThisConnection) {
                            savedThisConnection = true
                            val id = directory.remember(
                                id = target.id,
                                origin = endpoint.base,
                                nickname = target.nickname,
                                login = SavedLogin(target.raw, endpoint.password.orEmpty()),
                                replaceExisting = target.replaceExisting,
                            )
                            if (client !== newClient) return@snapshot
                            if (id == null) {
                                disconnect()
                                showError(R.string.remote_computer_origin_conflict)
                                return@snapshot
                            }
                            _state.update { it.copy(activeComputerId = id) }
                            if (!readyForMirror) {
                                readyForMirror = true
                                applyStatus(RemoteStatus.Connected, endpoint.display)
                                publishSnapshot(snap)
                            }
                        }
                    }
                },
                onStream = { delta ->
                    scope.launch {
                        if (client === newClient) applyDelta(delta)
                    }
                },
            )
        }
    }

    private fun publishSnapshot(snapshot: RemoteSnapshot) {
        _state.update { it.copy(phase = RemotePhase.CONNECTED, snapshot = snapshot, errorMessage = null) }
    }

    private fun applyStatus(status: RemoteStatus, label: String) {
        _state.update { state ->
            when (status) {
                RemoteStatus.Connecting -> state.copy(phase = RemotePhase.CONNECTING)
                RemoteStatus.Connected -> state.copy(phase = RemotePhase.CONNECTED, errorMessage = null)
                RemoteStatus.AuthRequired -> state.copy(phase = RemotePhase.NEEDS_PASSWORD, snapshot = null)
                RemoteStatus.Kicked -> state.copy(
                    phase = RemotePhase.ERROR,
                    snapshot = null,
                    errorMessage = LocaleHelper.string(context, R.string.remote_kicked),
                )
                is RemoteStatus.Error -> state.copy(phase = RemotePhase.ERROR, errorMessage = redactSecret(status.message))
            }
        }
        when (status) {
            RemoteStatus.Connected -> RemoteSessionService.start(context, label)
            RemoteStatus.Kicked, RemoteStatus.AuthRequired -> RemoteSessionService.stop(context)
            else -> Unit
        }
    }

    private fun applyDelta(delta: StreamDelta) {
        _state.update { state ->
            val snapshot = state.snapshot ?: return@update state
            state.copy(snapshot = snapshot.copy(
                isStreaming = delta.isStreaming,
                streamingContent = delta.streamingContent,
                streamingReasoningContent = delta.streamingReasoningContent,
                streamingStatus = delta.streamingStatus,
                streamingToolName = delta.streamingToolName,
            ))
        }
    }

    private fun showError(message: Int) {
        _state.update {
            it.copy(
                phase = if (it.showMirror) it.phase else RemotePhase.ERROR,
                errorMessage = LocaleHelper.string(context, message),
            )
        }
    }

    private fun stopStream() {
        val active = client
        client = null
        active?.stopStreaming()
        streamJob?.cancel()
        streamJob = null
    }

    private fun cancelSelection() {
        pendingSelectionId = null
        selectionJob?.cancel()
        selectionJob = null
    }

    fun disconnect() {
        userDisconnected = true
        cancelSelection()
        attempt = null
        stopStream()
        RemoteSessionService.stop(context)
        _state.update {
            it.copy(
                phase = RemotePhase.DISCONNECTED,
                snapshot = null,
                errorMessage = null,
                endpointLabel = null,
                activeComputerId = null,
                insecure = false,
            )
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}

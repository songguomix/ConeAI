package com.cone.agent.remote

import android.content.Context
import com.cone.agent.R
import com.cone.agent.data.crypto.KeystoreManager
import com.cone.agent.data.crypto.Sealed
import com.cone.agent.data.repository.SettingsRepository
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * 远程会话的持有者 —— 应用级单例，不属于任何一个界面。
 *
 * 之所以要从 ViewModel 里搬出来：ViewModel 跟着导航条目走，一旦离开远程页，
 * 连接就被取消，回到页面又要重新扫码。现在连接（以及内置的自动重连）活在这个单例里，
 * 再配合 [RemoteSessionService] 前台服务，切到后台、锁屏、换界面都能保持在线。
 */
@Singleton
class RemoteSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val remoteTls: RemoteTls,
    private val keystore: KeystoreManager,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val httpsClient: OkHttpClient by lazy {
        remoteTls.configure(okHttpClient.newBuilder()).build()
    }

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    /** Current command client, or null when offline. */
    @Volatile
    var client: RemoteClient? = null
        private set

    private var streamJob: Job? = null
    private var lastUrl: String? = null
    private var lastPassword: String? = null
    private var savedThisSession = false

    /** True once the user explicitly disconnects, so we don't auto-restore behind their back. */
    var userDisconnected: Boolean = false
        private set

    init {
        scope.launch {
            settings.remoteEndpoint.collect { saved -> _state.update { it.copy(savedUrl = saved) } }
        }
        scope.launch {
            settings.hasRemoteSecret.collect { has -> _state.update { it.copy(canBiometric = has) } }
        }
    }

    fun connect(raw: String, password: String? = null) {
        val parsed = RemoteEndpoint.parse(raw)
        if (parsed == null) {
            // 公网地址用 http 会被拒绝：token 走 query，明文等于把凭据敞给同网络的人。
            val cleartext = raw.trim().startsWith("http://", ignoreCase = true)
            _state.update {
                it.copy(
                    phase = RemotePhase.ERROR,
                    errorMessage = context.getString(
                        if (cleartext) R.string.remote_cleartext_rejected else R.string.remote_invalid_url,
                    ),
                )
            }
            return
        }
        val endpoint = parsed.copy(password = password?.takeIf { it.isNotBlank() } ?: parsed.password)
        streamJob?.cancel()
        val newClient = RemoteClient(httpsClient, endpoint)
        client = newClient
        userDisconnected = false
        lastUrl = raw.trim()
        lastPassword = endpoint.password
        savedThisSession = false
        _state.update {
            it.copy(
                phase = RemotePhase.CONNECTING,
                errorMessage = null,
                endpointLabel = endpoint.display,
                insecure = endpoint.insecure,
                snapshot = null,
            )
        }
        scope.launch { settings.setRemoteEndpoint(storableUrl(raw)) }
        streamJob = scope.launch {
            newClient.stream(
                onStatus = { status ->
                    _state.update { st ->
                        when (status) {
                            RemoteStatus.Connecting -> st.copy(phase = RemotePhase.CONNECTING)
                            RemoteStatus.Connected -> st.copy(phase = RemotePhase.CONNECTED, errorMessage = null)
                            RemoteStatus.AuthRequired -> st.copy(phase = RemotePhase.NEEDS_PASSWORD)
                            RemoteStatus.Kicked -> st.copy(
                                phase = RemotePhase.ERROR,
                                snapshot = null,
                                errorMessage = context.getString(R.string.remote_kicked),
                            )
                            is RemoteStatus.Error -> st.copy(
                                phase = RemotePhase.ERROR,
                                errorMessage = redactSecret(status.message),
                            )
                        }
                    }
                    if (status == RemoteStatus.Connected) {
                        RemoteSessionService.start(context, _state.value.endpointLabel)
                    } else if (status == RemoteStatus.Kicked || status == RemoteStatus.AuthRequired) {
                        RemoteSessionService.stop(context)
                    }
                },
                onSnapshot = { snap ->
                    _state.update { it.copy(phase = RemotePhase.CONNECTED, snapshot = snap, errorMessage = null) }
                    RemoteSessionService.start(context, _state.value.endpointLabel)
                    if (!savedThisSession) {
                        savedThisSession = true
                        persistSecret(lastUrl, lastPassword)
                    }
                },
                onStream = { delta ->
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

    /**
     * Restore the last session when the remote screen opens again (or the app comes back).
     * Skips when the user just pressed 断开.
     */
    fun restore() {
        if (userDisconnected) return
        if (client != null) return
        if (_state.value.phase == RemotePhase.CONNECTING) return
        scope.launch {
            // The plain store only knows the origin; the token comes from the encrypted blob.
            val (cipher, iv) = settings.readRemoteSecret() ?: return@launch
            val blob = runCatching { keystore.decrypt(Sealed(cipher, iv)) }.getOrNull().orEmpty()
            if (blob.isBlank()) return@launch
            val login = runCatching {
                RemoteJson.decodeFromString(SavedLogin.serializer(), blob)
            }.getOrNull() ?: return@launch
            if (login.url.isNotBlank()) {
                connect(login.url, login.password.takeIf { it.isNotBlank() })
            }
        }
    }

    /** After a successful biometric prompt: decrypt and connect with the saved url + password. */
    fun unlockSaved() {
        scope.launch {
            val (cipher, iv) = settings.readRemoteSecret() ?: return@launch
            val blob = keystore.decrypt(Sealed(cipher, iv))
            if (blob.isBlank()) return@launch
            val login = runCatching { RemoteJson.decodeFromString(SavedLogin.serializer(), blob) }.getOrNull()
                ?: return@launch
            if (login.url.isNotBlank()) connect(login.url, login.password.takeIf { it.isNotBlank() })
        }
    }

    /** Forget the saved (biometric) login and the reconnect URL. */
    fun clearSaved() {
        scope.launch {
            settings.clearRemoteSecret()
            settings.setRemoteEndpoint(null)
        }
    }

    /**
     * Only the origin (`https://host:port`) is written to the plain preference store — the token and
     * password never touch it. They live in the Keystore-encrypted blob instead.
     */
    private fun storableUrl(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: return raw.trim()
        return url.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun persistSecret(url: String?, password: String?) {
        if (url.isNullOrBlank()) return
        scope.launch {
            val blob = RemoteJson.encodeToString(SavedLogin.serializer(), SavedLogin(url, password ?: ""))
            val sealed = keystore.encrypt(blob)
            if (sealed.cipherText.isNotBlank()) settings.setRemoteSecret(sealed.cipherText, sealed.iv)
        }
    }

    fun disconnect() {
        userDisconnected = true
        streamJob?.cancel()
        streamJob = null
        client = null
        RemoteSessionService.stop(context)
        _state.update {
            it.copy(
                phase = RemotePhase.DISCONNECTED,
                snapshot = null,
                errorMessage = null,
                endpointLabel = null,
                insecure = false,
            )
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}

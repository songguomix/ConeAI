package com.cone.agent.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the pairing has got to. The UI is a stepper over exactly these. */
enum class WatchSetupStep {
    /** Nothing scanned yet — waiting for the user to point the camera at the watch. */
    SCAN,

    /** Scanned; hunting for the watch on the LAN and telling the user to switch the hotspot on. */
    CONNECTING,

    /** Found the watch and pushing settings across. */
    PUSHING,

    /** Done — the watch has providers, models and a selected chat model. */
    DONE,

    /** Search or push failed; the user can retry or type the watch's address by hand. */
    FAILED,
}

data class WatchSetupUiState(
    val step: WatchSetupStep = WatchSetupStep.SCAN,
    val payload: PairPayload? = null,
    val watch: FoundWatch? = null,
    val result: PushResult? = null,
    val error: String? = null,
    /** Seconds spent looking, so the UI can escalate its hints instead of spinning silently. */
    val searchSeconds: Int = 0,
    /** Provider names available to send, and to pick from for the watch's speech services. */
    val providerNames: List<String> = emptyList(),
    /** The watch's speech settings as staged on the phone; pushed along with the providers. */
    val speech: WatchSpeechPrefs = WatchSpeechPrefs(),
) {
    val providerCount: Int get() = providerNames.size
}

/**
 * Drives "配置手表端": scan → find on the LAN → push the phone's providers.
 *
 * Everything after the scan is automatic. The only thing asked of the user is switching on the
 * phone's hotspot with the name and password the watch chose, and even that is skipped when both
 * devices already sit on the same Wi-Fi — the search runs regardless and simply succeeds sooner.
 */
@HiltViewModel
class WatchSetupViewModel @Inject constructor(
    private val client: WatchLinkClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val speechStore: WatchSpeechStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchSetupUiState(speech = speechStore.load()))
    val state: StateFlow<WatchSetupUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            providerRepository.providers.collect { list ->
                _state.update { it.copy(providerNames = list.map { provider -> provider.name }) }
            }
        }
    }

    /** Called with the raw QR contents from the shared scanner, or null if the user backed out. */
    fun onScanned(raw: String?) {
        // Backing out of the camera is not a failure — leave the screen as it was rather than
        // accusing the user of scanning the wrong code.
        if (raw == null) return
        val payload = PairPayload.parse(raw)
        if (payload == null) {
            _state.update {
                it.copy(step = WatchSetupStep.FAILED, error = ERROR_BAD_QR, payload = null)
            }
            return
        }
        _state.update {
            it.copy(step = WatchSetupStep.CONNECTING, payload = payload, error = null, result = null, watch = null)
        }
        startSearch(payload)
    }

    /** Retry from the "not found" state without re-scanning — the payload is still good. */
    fun retry() {
        val payload = _state.value.payload ?: return
        _state.update { it.copy(step = WatchSetupStep.CONNECTING, error = null, searchSeconds = 0) }
        startSearch(payload)
    }

    /** Manual escape hatch: the watch's IP, read off its own screen, when discovery is blocked. */
    fun connectManually(host: String) {
        val payload = _state.value.payload ?: return
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        _state.update { it.copy(step = WatchSetupStep.CONNECTING, error = null) }
        searchJob = viewModelScope.launch {
            val found = client.probe(trimmed, payload)
            if (found == null) {
                _state.update { it.copy(step = WatchSetupStep.FAILED, error = ERROR_NOT_FOUND) }
            } else {
                pushTo(found, payload)
            }
        }
    }

    fun restart() {
        searchJob?.cancel()
        tickJob?.cancel()
        _state.value = WatchSetupUiState(
            providerNames = _state.value.providerNames,
            speech = _state.value.speech,
        )
    }

    /**
     * Edits the watch's speech settings. Saved on every keystroke rather than behind a "save"
     * button: the form is a staging area for the next push, and losing a half-typed model id because
     * the screen was left is the kind of thing that makes people re-enter it on the watch instead.
     */
    fun updateSpeech(transform: (WatchSpeechPrefs) -> WatchSpeechPrefs) {
        val updated = transform(_state.value.speech)
        speechStore.save(updated)
        _state.update { it.copy(speech = updated) }
    }

    private fun startSearch(payload: PairPayload) {
        searchJob?.cancel()
        tickJob?.cancel()
        _state.update { it.copy(searchSeconds = 0) }
        tickJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                _state.update { it.copy(searchSeconds = it.searchSeconds + 1) }
            }
        }
        searchJob = viewModelScope.launch {
            val found = client.discover(payload, DISCOVERY_TIMEOUT_MS)
            tickJob?.cancel()
            if (found == null) {
                _state.update { it.copy(step = WatchSetupStep.FAILED, error = ERROR_NOT_FOUND) }
            } else {
                pushTo(found, payload)
            }
        }
    }

    private suspend fun pushTo(found: FoundWatch, payload: PairPayload) {
        _state.update { it.copy(step = WatchSetupStep.PUSHING, watch = found) }
        val config = buildConfig()
        val outcome = client.push(found, payload, config)
        outcome.fold(
            onSuccess = { result ->
                _state.update {
                    if (result.ok) {
                        it.copy(step = WatchSetupStep.DONE, result = result, error = null)
                    } else {
                        it.copy(step = WatchSetupStep.FAILED, result = result, error = result.error ?: ERROR_PUSH)
                    }
                }
            },
            onFailure = { throwable ->
                _state.update {
                    it.copy(step = WatchSetupStep.FAILED, error = throwable.message ?: ERROR_PUSH)
                }
            },
        )
    }

    /**
     * Snapshots what the watch needs to be useful on its own: every provider with its decrypted key,
     * that provider's models, and the model the phone currently uses for 问答.
     */
    private suspend fun buildConfig(): WatchConfigPush {
        val providers = providerRepository.providers.first()
        val allModels = providerRepository.allModels.first()
        val pushProviders = providers.mapNotNull { provider ->
            val resolved = providerRepository.resolve(provider.id) ?: return@mapNotNull null
            PushProvider(
                name = provider.name,
                baseUrl = provider.baseUrl,
                protocol = provider.protocol.wireName,
                apiKey = resolved.apiKey,
                models = allModels.filter { it.providerId == provider.id }.map { it.modelId },
            )
        }

        val chat = settingsRepository.chatModel.first()?.let { selected ->
            providers.firstOrNull { it.id == selected.providerId }
                ?.let { PushSelection(providerName = it.name, modelId = selected.modelId) }
        }

        return WatchConfigPush(
            providers = pushProviders,
            chat = chat,
            speech = _state.value.speech.toPush(),
            language = settingsRepository.appLanguage.first(),
            searchEngine = settingsRepository.searchEngine.first(),
        )
    }

    private companion object {
        /** Long enough for the user to find the hotspot toggle and type a passphrase. */
        const val DISCOVERY_TIMEOUT_MS = 180_000L

        const val ERROR_BAD_QR = "bad_qr"
        const val ERROR_NOT_FOUND = "not_found"
        const val ERROR_PUSH = "push_failed"
    }
}

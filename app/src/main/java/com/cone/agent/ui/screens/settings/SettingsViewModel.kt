package com.cone.agent.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.core.Constants
import com.cone.agent.data.local.entity.VisionModelView
import com.cone.agent.data.repository.MemoryItem
import com.cone.agent.data.repository.MemoryRepository
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.ScreenMode
import com.cone.agent.data.repository.SelectedAgentModel
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    val allModels: StateFlow<List<VisionModelView>> = providerRepository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedModel: StateFlow<SelectedAgentModel?> = settingsRepository.selectedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chatModel: StateFlow<SelectedAgentModel?> = settingsRepository.chatModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val maxSteps: StateFlow<Int> = settingsRepository.maxSteps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Constants.DEFAULT_MAX_STEPS)

    val autoConfirmHighRisk: StateFlow<Boolean> = settingsRepository.autoConfirmHighRisk
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val planModeEnabled: StateFlow<Boolean> = settingsRepository.planModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val memoryEnabled: StateFlow<Boolean> = memoryRepository.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val memories: StateFlow<List<MemoryItem>> = memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val screenMode: StateFlow<ScreenMode> = settingsRepository.screenMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScreenMode.LOCAL_OCR)

    val visionModel: StateFlow<SelectedAgentModel?> = settingsRepository.visionModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chatDualModelEnabled: StateFlow<Boolean> = settingsRepository.chatDualModelEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val chatVisionModel: StateFlow<SelectedAgentModel?> = settingsRepository.chatVisionModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val searchEngine: StateFlow<String> = settingsRepository.searchEngine
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")

    fun selectModel(providerId: Long, modelId: String) {
        viewModelScope.launch { settingsRepository.selectModel(providerId, modelId) }
    }

    fun setSearchEngine(value: String) {
        viewModelScope.launch { settingsRepository.setSearchEngine(value) }
    }

    fun updateMemory(id: Long, content: String) {
        viewModelScope.launch { memoryRepository.update(id, content) }
    }

    fun setScreenMode(value: ScreenMode) {
        viewModelScope.launch { settingsRepository.setScreenMode(value) }
    }

    /* ---------------- 自定义语音识别 ---------------- */

    // Edited locally and written straight through: the four fields are one stored record, so each
    // keystroke has to persist the other three alongside it or typing in one would blank the rest.
    private val _asrBaseUrl = MutableStateFlow("")
    val asrBaseUrl: StateFlow<String> = _asrBaseUrl.asStateFlow()
    private val _asrApiKey = MutableStateFlow("")
    val asrApiKey: StateFlow<String> = _asrApiKey.asStateFlow()
    private val _asrModel = MutableStateFlow("")
    val asrModel: StateFlow<String> = _asrModel.asStateFlow()
    private val _asrLanguage = MutableStateFlow("")
    val asrLanguage: StateFlow<String> = _asrLanguage.asStateFlow()

    private val _ttsBaseUrl = MutableStateFlow("")
    val ttsBaseUrl: StateFlow<String> = _ttsBaseUrl.asStateFlow()
    private val _ttsApiKey = MutableStateFlow("")
    val ttsApiKey: StateFlow<String> = _ttsApiKey.asStateFlow()
    private val _ttsModel = MutableStateFlow("")
    val ttsModel: StateFlow<String> = _ttsModel.asStateFlow()
    private val _ttsVoice = MutableStateFlow("")
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()

    // Declared above the initialiser on purpose: this block reads every one of them, and a
    // property still being constructed would be null if the coroutine ever ran early.
    init {
        viewModelScope.launch {
            settingsRepository.asrConfig.first()?.let { config ->
                _asrBaseUrl.value = config.baseUrl
                _asrApiKey.value = config.apiKey
                _asrModel.value = config.model
                _asrLanguage.value = config.language
            }
            settingsRepository.ttsConfig.first()?.let { config ->
                _ttsBaseUrl.value = config.baseUrl
                _ttsApiKey.value = config.apiKey
                _ttsModel.value = config.model
                _ttsVoice.value = config.voice
            }
        }
    }

    fun setTtsBaseUrl(value: String) { _ttsBaseUrl.value = value; persistTts() }
    fun setTtsApiKey(value: String) { _ttsApiKey.value = value; persistTts() }
    fun setTtsModel(value: String) { _ttsModel.value = value; persistTts() }
    fun setTtsVoice(value: String) { _ttsVoice.value = value; persistTts() }

    private fun persistTts() {
        viewModelScope.launch {
            settingsRepository.setTtsConfig(
                baseUrl = _ttsBaseUrl.value,
                apiKey = _ttsApiKey.value,
                model = _ttsModel.value,
                voice = _ttsVoice.value,
            )
        }
    }

    fun setAsrBaseUrl(value: String) { _asrBaseUrl.value = value; persistAsr() }
    fun setAsrApiKey(value: String) { _asrApiKey.value = value; persistAsr() }
    fun setAsrModel(value: String) { _asrModel.value = value; persistAsr() }
    fun setAsrLanguage(value: String) { _asrLanguage.value = value; persistAsr() }

    private fun persistAsr() {
        viewModelScope.launch {
            settingsRepository.setAsrConfig(
                baseUrl = _asrBaseUrl.value,
                apiKey = _asrApiKey.value,
                model = _asrModel.value,
                language = _asrLanguage.value,
            )
        }
    }

    fun selectVisionModel(providerId: Long, modelId: String) {
        viewModelScope.launch { settingsRepository.selectVisionModel(providerId, modelId) }
    }

    fun setChatDualModelEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setChatDualModelEnabled(value) }
    }

    fun selectChatVisionModel(providerId: Long, modelId: String) {
        viewModelScope.launch { settingsRepository.selectChatVisionModel(providerId, modelId) }
    }

    fun selectChatModel(providerId: Long, modelId: String) {
        viewModelScope.launch { settingsRepository.selectChatModel(providerId, modelId) }
    }

    fun setMaxSteps(value: Int) {
        viewModelScope.launch { settingsRepository.setMaxSteps(value) }
    }

    fun setAutoConfirmHighRisk(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoConfirmHighRisk(value) }
    }

    fun setPlanModeEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setPlanModeEnabled(value) }
    }

    fun setMemoryEnabled(value: Boolean) {
        viewModelScope.launch { memoryRepository.setEnabled(value) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryRepository.remove(id) }
    }

    fun clearMemories() {
        viewModelScope.launch { memoryRepository.clear() }
    }
}

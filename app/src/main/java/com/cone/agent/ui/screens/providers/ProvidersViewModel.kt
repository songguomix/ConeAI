package com.cone.agent.ui.screens.providers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.domain.model.ModelInfo
import com.cone.agent.domain.model.Provider
import com.cone.agent.domain.model.ProviderProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProviderRepository,
) : ViewModel() {

    private fun str(id: Int, vararg args: Any) = LocaleHelper.string(context, id, *args)

    val providers: StateFlow<List<Provider>> = repository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering

    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    fun modelsOf(providerId: Long): Flow<List<ModelInfo>> = repository.modelsOf(providerId)

    fun saveProvider(
        id: Long,
        name: String,
        baseUrl: String,
        protocol: ProviderProtocol,
        apiKeyPlain: String?,
        manualModels: Boolean = false,
        onSaved: (Long) -> Unit = {},
    ) {
        if (name.isBlank() || baseUrl.isBlank()) {
            _events.trySend(str(R.string.providers_err_empty))
            return
        }
        viewModelScope.launch {
            val newId = repository.upsertProvider(id, name, baseUrl, protocol, apiKeyPlain, manualModels)
            _events.trySend(str(R.string.providers_saved, name))
            onSaved(if (id != 0L) id else newId)
        }
    }

    /** Persists the auto/manual model-source choice for an already-saved provider. */
    fun setManualMode(providerId: Long, manual: Boolean) {
        if (providerId == 0L) return
        viewModelScope.launch { repository.setManualMode(providerId, manual) }
    }

    /** Adds a hand-typed model name to a provider; surfaces a snackbar on success or error. */
    fun addModel(providerId: Long, modelId: String) {
        if (providerId == 0L) return
        viewModelScope.launch {
            repository.addManualModel(providerId, modelId)
                .onSuccess { _events.trySend(str(R.string.providers_model_added, modelId.trim())) }
                .onFailure { _events.trySend(it.message ?: str(R.string.providers_model_blank)) }
        }
    }

    fun removeModel(model: ModelInfo) {
        viewModelScope.launch {
            repository.removeModel(model.id)
            _events.trySend(str(R.string.providers_model_removed, model.modelId))
        }
    }

    fun deleteProvider(provider: Provider) {
        viewModelScope.launch {
            repository.deleteProvider(provider)
            _events.trySend(str(R.string.providers_deleted, provider.name))
        }
    }

    fun discover(providerId: Long) {
        if (_discovering.value) return
        viewModelScope.launch {
            _discovering.value = true
            val result = repository.discoverModels(providerId)
            _discovering.value = false
            result
                .onSuccess { models -> _events.trySend(str(R.string.providers_discovered, models.size)) }
                .onFailure { _events.trySend(str(R.string.providers_discover_failed, it.message ?: "")) }
        }
    }
}

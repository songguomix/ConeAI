package com.cone.agent.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.mcp.McpManager
import com.cone.agent.mcp.McpServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class McpSettingsViewModel @Inject constructor(private val manager: McpManager) : ViewModel() {
    val servers = manager.serversFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tools = manager.toolsFlow
    val errors = manager.errors
    val refreshing = manager.refreshing
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _testedCount = MutableStateFlow<Int?>(null)
    val testedCount = _testedCount.asStateFlow()

    fun clearFeedback() { _error.value = null; _testedCount.value = null }

    private fun perform(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        clearFeedback()
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "MCP operation failed" }
            finally { _busy.value = false }
        }
    }

    fun save(config: McpServerConfig, isNew: Boolean, onSaved: () -> Unit) = perform {
        if (isNew) manager.addServer(config).getOrThrow() else manager.updateServer(config).getOrThrow()
        onSaved()
    }

    fun test(config: McpServerConfig) = perform {
        _testedCount.value = manager.testConnection(config).getOrThrow().size
    }

    fun remove(id: String) = perform { manager.removeServer(id).getOrThrow() }
    fun setEnabled(id: String, enabled: Boolean) = perform { manager.setEnabled(id, enabled).getOrThrow() }
    fun refresh() = perform { manager.refreshAll().getOrThrow() }
}

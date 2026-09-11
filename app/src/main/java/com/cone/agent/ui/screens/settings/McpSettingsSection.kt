package com.cone.agent.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.mcp.McpServerConfig
import com.cone.agent.mcp.McpTransport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun McpSettingsSection(viewModel: McpSettingsViewModel = hiltViewModel()) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val testedCount by viewModel.testedCount.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<McpServerConfig?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mcp_description), style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(enabled = !busy, onClick = {
                viewModel.clearFeedback(); isNew = true; editing = McpServerConfig(name = "", url = "")
            }) { Text(stringResource(R.string.mcp_add)) }
            TextButton(enabled = !busy && !refreshing && servers.isNotEmpty(), onClick = viewModel::refresh) {
                Text(stringResource(R.string.mcp_refresh))
            }
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (servers.isEmpty()) Text(stringResource(R.string.mcp_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        servers.forEach { server ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(server.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(server.enabled, { viewModel.setEnabled(server.id, it) }, enabled = !busy)
                    }
                    // URLs and headers can contain credentials; show only the host in the list.
                    Text(runCatching { java.net.URI(server.url).host }.getOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.mcp_tool_count, tools.count { it.serverId == server.id }))
                    errors[server.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            viewModel.clearFeedback(); isNew = false; editing = server
                        }) { Text(stringResource(R.string.remote_edit)) }
                        TextButton(enabled = !busy, onClick = { viewModel.test(server) }) { Text(stringResource(R.string.mcp_test)) }
                        TextButton(enabled = !busy, onClick = { deleting = server }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
        if (editing == null) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            testedCount?.let { Text(stringResource(R.string.mcp_test_success, it)) }
        }
    }
    editing?.let { server ->
        McpEditor(server, busy, error, testedCount,
            onDismiss = { if (!busy) { editing = null; viewModel.clearFeedback() } },
            onChanged = viewModel::clearFeedback,
            onTest = viewModel::test,
            onSave = { viewModel.save(it, isNew) { editing = null } },
        )
    }
    deleting?.let { server ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.mcp_delete_confirm, server.name)) },
            confirmButton = { TextButton(onClick = { viewModel.remove(server.id); deleting = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun McpEditor(
    server: McpServerConfig, busy: Boolean, error: String?, testedCount: Int?,
    onDismiss: () -> Unit, onChanged: () -> Unit,
    onTest: (McpServerConfig) -> Unit, onSave: (McpServerConfig) -> Unit,
) {
    var name by remember(server.id) { mutableStateOf(server.name) }
    var url by remember(server.id) { mutableStateOf(server.url) }
    var headers by remember(server.id) { mutableStateOf(if (server.headers.isEmpty()) "" else Json.encodeToString(server.headers)) }
    var invalid by remember { mutableStateOf(false) }
    fun submit(action: (McpServerConfig) -> Unit) {
        val parsed = runCatching {
            server.copy(name = name.trim(), url = url.trim(), transport = McpTransport.HTTP,
                headers = if (headers.isBlank()) emptyMap() else Json.decodeFromString<Map<String, String>>(headers))
                .also { it.validate().getOrThrow() }
        }.getOrNull()
        invalid = parsed == null
        if (parsed != null) action(parsed)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mcp_transport_hint))
                OutlinedTextField(name, { name = it; invalid = false; onChanged() }, enabled = !busy,
                    label = { Text(stringResource(R.string.mcp_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it; invalid = false; onChanged() }, enabled = !busy,
                    label = { Text(stringResource(R.string.mcp_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(headers, { headers = it; invalid = false; onChanged() }, enabled = !busy,
                    label = { Text(stringResource(R.string.mcp_headers)) }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.mcp_headers_hint), style = MaterialTheme.typography.bodySmall)
                if (invalid) Text(stringResource(R.string.mcp_invalid), color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                testedCount?.let { Text(stringResource(R.string.mcp_test_success, it)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(enabled = !busy, onClick = { submit(onTest) }) { Text(stringResource(R.string.mcp_test)) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { submit(onSave) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

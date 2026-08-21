package com.cone.agent.ui.screens.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.data.remote.EndpointResolver
import com.cone.agent.domain.model.ProviderProtocol

private fun protocolLabelRes(protocol: ProviderProtocol): Int = when (protocol) {
    ProviderProtocol.OPENAI -> R.string.provider_protocol_openai
    ProviderProtocol.ANTHROPIC -> R.string.provider_protocol_anthropic
    ProviderProtocol.CUSTOM -> R.string.provider_protocol_custom
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(
    providerId: Long,
    onBack: () -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val discovering by viewModel.discovering.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentId by remember { mutableLongStateOf(providerId) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(ProviderProtocol.OPENAI) }
    var manualModels by remember { mutableStateOf(false) }
    var newModelName by remember { mutableStateOf("") }
    var prefilled by remember { mutableStateOf(false) }

    val modelsFlow = remember(currentId) { viewModel.modelsOf(currentId) }
    val models by modelsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(providers) {
        if (!prefilled && currentId != 0L) {
            providers.firstOrNull { it.id == currentId }?.let {
                name = it.name
                baseUrl = it.baseUrl
                protocol = it.protocol
                manualModels = it.manualModels
                prefilled = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (currentId == 0L) R.string.provider_edit_new else R.string.provider_edit_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Inset above the soft keyboard so the model-name field stays visible while typing,
                // and let the focused field auto-scroll into the area above the IME.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.provider_name_label)) },
                placeholder = { Text(stringResource(R.string.provider_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.provider_protocol_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderProtocol.entries.forEach { option ->
                    FilterChip(
                        selected = protocol == option,
                        onClick = { protocol = option },
                        label = { Text(stringResource(protocolLabelRes(option))) },
                        leadingIcon = if (protocol == option) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = {
                    Text(
                        when (protocol) {
                            ProviderProtocol.ANTHROPIC -> "https://api.anthropic.com"
                            ProviderProtocol.CUSTOM -> "https://your-proxy.com/v1/chat/completions"
                            ProviderProtocol.OPENAI -> "https://api.openai.com"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            if (baseUrl.isNotBlank()) {
                Text(
                    stringResource(R.string.provider_endpoint_hint, EndpointResolver.chatUrl(baseUrl, protocol)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.provider_apikey_label)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Button(
                onClick = {
                    viewModel.saveProvider(
                        id = currentId,
                        name = name,
                        baseUrl = baseUrl,
                        protocol = protocol,
                        apiKeyPlain = apiKey.ifBlank { null },
                        manualModels = manualModels,
                        onSaved = { savedId -> currentId = savedId },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(stringResource(R.string.action_save)) }

            if (currentId == 0L) {
                Text(
                    stringResource(R.string.provider_save_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Model source: 自动发现 (GET /models) vs 手动填写 (typed by hand).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.provider_manual_switch_label), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.provider_manual_switch_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = manualModels,
                    onCheckedChange = { on ->
                        manualModels = on
                        viewModel.setManualMode(currentId, on)
                    },
                )
            }

            if (manualModels) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newModelName,
                        onValueChange = { newModelName = it },
                        label = { Text(stringResource(R.string.provider_model_name_label)) },
                        placeholder = { Text(stringResource(R.string.provider_model_name_placeholder)) },
                        singleLine = true,
                        enabled = currentId != 0L,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.addModel(currentId, newModelName)
                            newModelName = ""
                        },
                        enabled = currentId != 0L && newModelName.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.provider_add_model))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { viewModel.discover(currentId) },
                    enabled = currentId != 0L && !discovering,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    if (discovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.provider_discover))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.provider_models_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (manualModels) R.string.provider_models_desc_manual else R.string.provider_models_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            if (models.isEmpty()) {
                Text(
                    stringResource(if (manualModels) R.string.provider_no_models_manual else R.string.provider_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                models.forEach { model ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                model.modelId,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeModel(model) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

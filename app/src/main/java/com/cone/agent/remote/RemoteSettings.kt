package com.cone.agent.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.cone.agent.R
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
internal data class RemoteSettingsReply(
    val ok: Boolean = false,
    val page: RemoteSettingsPage? = null,
    val item: RemoteSettingsEntry? = null,
    val error: String? = null,
)

@Serializable
internal data class RemoteSettingsPage(
    val items: List<RemoteSettingsEntry> = emptyList(),
    val more: Boolean = false,
)

@Serializable
internal data class RemoteSettingsEntry(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val content: String? = null,
    val type: String? = null,
    val enabled: Boolean? = null,
    val values: Map<String, String> = emptyMap(),
) {
    fun value(key: String) = values[key].orEmpty()
}

private enum class SettingsSection(val wire: String, @StringRes val label: Int, val scoped: Boolean = false) {
    GENERAL("general", R.string.remote_settings_general),
    PROVIDERS("providers", R.string.remote_settings_providers),
    MODELS("models", R.string.remote_settings_models),
    PROMPTS("prompts", R.string.remote_settings_prompts),
    SKILLS("skills", R.string.remote_settings_skills, true),
    MEMORY("memory", R.string.remote_settings_memory, true),
    WIKI("wiki", R.string.remote_settings_wiki, true),
    MCP("mcp", R.string.remote_settings_mcp, true),
    PLUGINS("plugins", R.string.remote_settings_plugins),
    MARKET("market", R.string.remote_settings_market),
    INSTALLED("marketInstalled", R.string.remote_settings_installed),
    PROFILES("marketProfiles", R.string.remote_settings_profiles),
    SOURCES("marketSources", R.string.remote_settings_sources),
    AGENTS("agents", R.string.remote_settings_agents),
    UPDATES("updates", R.string.remote_settings_updates),
}

private data class SettingsCommand(
    val action: String,
    val id: String = "",
    val values: JsonObject = JsonObject(emptyMap()),
)

private fun settingsValues(vararg values: Pair<String, String>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
}

private fun settingsToggle(key: String, value: Boolean, vararg values: Pair<String, String>): JsonObject =
    buildJsonObject {
        values.forEach { (name, text) -> put(name, JsonPrimitive(text)) }
        put(key, JsonPrimitive(value))
    }

private data class SettingsField(
    val key: String,
    @StringRes val label: Int,
    val initial: String = "",
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    val multiline: Boolean = false,
    val password: Boolean = false,
    val number: Boolean = false,
)

private data class SettingsForm(
    @StringRes val title: Int,
    val action: String,
    val id: String = "",
    val fields: List<SettingsField> = emptyList(),
    val base: Map<String, String> = emptyMap(),
    val confirmation: SettingsCommand? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteSettingsPanel(
    viewModel: RemoteViewModel,
    snapshot: RemoteSnapshot,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.remote_settings_failed)
    val colors = LocalRemotePalette.current
    var section by remember(state.activeComputerId) { mutableStateOf<SettingsSection?>(null) }
    val selected = section
    val root = snapshot.rootPath.orEmpty()
    val currentRoot = if (selected?.scoped == true) root else ""
    var search by remember(selected, currentRoot) { mutableStateOf("") }
    var query by remember(selected, currentRoot) { mutableStateOf("") }
    var pageNumber by remember(selected, query, currentRoot) { mutableIntStateOf(1) }
    var revision by remember { mutableIntStateOf(0) }
    var page by remember(selected, currentRoot) { mutableStateOf<RemoteSettingsPage?>(null) }
    var entry by remember(selected, currentRoot) { mutableStateOf<RemoteSettingsEntry?>(null) }
    var form by remember(selected, currentRoot) { mutableStateOf<SettingsForm?>(null) }
    var loading by remember(selected, currentRoot) { mutableStateOf(false) }
    var pending by remember(selected, currentRoot) { mutableStateOf(false) }
    var error by remember(selected, currentRoot) { mutableStateOf<String?>(null) }
    val connected = state.phase == RemotePhase.CONNECTED
    val desktop = state.activeComputerId
    val conversationId = snapshot.activeConversationId.orEmpty()

    suspend fun request(command: SettingsCommand, extra: Map<String, String> = emptyMap()): RemoteSettingsReply? {
        if (selected == null || !connected) return null
        return viewModel.settingsRequest(
            mapOf("section" to selected.wire, "action" to command.action, "id" to command.id, "root" to currentRoot) + extra,
            command.values,
        )
    }

    LaunchedEffect(selected, currentRoot, pageNumber, query, revision, connected, desktop) {
        if (selected == null || !connected) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        val result = request(SettingsCommand("list"), mapOf("page" to pageNumber.toString(), "query" to query))
        if (result?.ok == true) page = result.page
        else {
            page = null
            error = result?.error ?: failed
        }
        loading = false
    }

    fun run(command: SettingsCommand) {
        if (pending || !connected) return
        pending = true
        error = null
        scope.launch {
            val result = request(command)
            pending = false
            if (viewModel.state.value.activeComputerId != desktop) return@launch
            if (result?.ok == true) {
                entry = null
                form = null
                revision++
            } else error = result?.error ?: failed
        }
    }

    fun inspect(item: RemoteSettingsEntry) {
        if (pending || !connected) return
        if (selected !in setOf(SettingsSection.PROMPTS, SettingsSection.SKILLS, SettingsSection.MEMORY,
                SettingsSection.WIKI, SettingsSection.AGENTS, SettingsSection.MARKET, SettingsSection.INSTALLED)) {
            entry = item
            error = null
            return
        }
        pending = true
        error = null
        val type = when (selected) {
            SettingsSection.INSTALLED -> item.value("version")
            SettingsSection.MARKET -> ""
            else -> item.type.orEmpty()
        }
        scope.launch {
            val result = request(SettingsCommand("detail", item.id), mapOf("itemType" to type))
            pending = false
            if (viewModel.state.value.activeComputerId != desktop) return@launch
            if (result?.ok == true && result.item != null) {
                val remote = result.item
                entry = remote.copy(values = item.values + remote.values)
            } else error = result?.error ?: failed
        }
    }

    val back: () -> Unit = {
        when {
            pending -> onDismiss()
            form != null -> { form = null; error = null }
            entry != null -> { entry = null; error = null }
            selected != null -> section = null
            else -> onDismiss()
        }
    }

    RemoteDialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = colors.bg0, modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(stringResource(if (selected == null || pending) R.string.remote_settings_close else R.string.cd_back))
                    }
                    val title = form?.let { stringResource(it.title) }
                        ?: entry?.title?.takeIf { selected != SettingsSection.GENERAL }
                        ?: stringResource(selected?.label ?: R.string.remote_settings_title)
                    Text(title, color = colors.text, style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (selected != null && entry == null && form == null) {
                        TextButton(onClick = { revision++ }, enabled = connected && !loading && !pending) {
                            Text(stringResource(R.string.remote_settings_refresh))
                        }
                    }
                }
                HorizontalDivider(color = colors.border)
                if (loading || pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!connected || error != null) {
                    Text(if (!connected) stringResource(R.string.remote_settings_offline) else error.orEmpty(),
                        color = colors.danger, modifier = Modifier.padding(vertical = 8.dp))
                }
                val activeForm = form
                val activeEntry = entry
                when {
                    selected == null -> LazyColumn(Modifier.fillMaxSize()) {
                        items(SettingsSection.entries) { item ->
                            SettingsListRow(stringResource(item.label), null, true) { section = item }
                        }
                    }
                    activeForm != null -> SettingsFormView(activeForm, connected && !pending, ::run)
                    activeEntry != null -> SettingsEntryView(selected, activeEntry, root, conversationId,
                        state.insecure, connected && !pending, ::run) { form = it }
                    else -> {
                        if (selected !in setOf(SettingsSection.GENERAL, SettingsSection.UPDATES)) Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedTextField(search, { search = it.take(200) }, Modifier.weight(1f), singleLine = true,
                                label = { Text(stringResource(R.string.remote_settings_search)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { query = search.trim() }))
                            TextButton(onClick = { query = search.trim() }, enabled = connected && !loading && !pending) {
                                Text(stringResource(R.string.remote_settings_search))
                            }
                        }
                        val available = connected && !pending
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            when (selected) {
                                SettingsSection.PROVIDERS ->
                                    SettingsAction(R.string.remote_settings_add, available && !state.insecure) { form = providerForm(null) }
                                SettingsSection.PROMPTS -> {
                                    SettingsAction(R.string.remote_settings_add, available) { form = promptForm(null) }
                                    SettingsAction(R.string.remote_settings_reset_all, available) {
                                        form = confirm(R.string.remote_settings_reset_all, SettingsCommand("resetAll"))
                                    }
                                }
                                SettingsSection.SKILLS -> SettingsAction(R.string.remote_settings_add, available) { form = skillForm(null, root) }
                                SettingsSection.PLUGINS -> SettingsAction(R.string.remote_settings_add, available) { form = pluginForm() }
                                SettingsSection.MEMORY -> {
                                    SettingsAction(R.string.remote_settings_add, available) { form = memoryForm(null, root) }
                                    SettingsAction(R.string.remote_settings_sync, available) { run(SettingsCommand("sync")) }
                                }
                                SettingsSection.WIKI -> SettingsAction(R.string.remote_settings_run_dream, available) {
                                    form = confirm(R.string.remote_settings_run_dream, SettingsCommand("dream"))
                                }
                                SettingsSection.MCP -> SettingsAction(R.string.remote_settings_reload, available) { run(SettingsCommand("reload")) }
                                SettingsSection.INSTALLED -> SettingsAction(R.string.remote_settings_restart, available) { run(SettingsCommand("restart")) }
                                SettingsSection.PROFILES -> SettingsAction(R.string.remote_settings_add, available) { form = profileForm() }
                                SettingsSection.SOURCES -> SettingsAction(R.string.remote_settings_reload, available) { run(SettingsCommand("refresh")) }
                                SettingsSection.UPDATES -> SettingsAction(R.string.remote_settings_check_updates, available) { run(SettingsCommand("check")) }
                                else -> Unit
                            }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(page?.items.orEmpty()) { item ->
                                val title = if (selected == SettingsSection.GENERAL) stringResource(generalLabel(item.id)) else item.title
                                val subtitle = if (selected == SettingsSection.GENERAL) {
                                    item.enabled?.let { stringResource(if (it) R.string.remote_settings_on else R.string.remote_settings_off) }
                                        ?: item.value("value").takeIf { it.isNotBlank() }?.let { settingOption(it) }
                                } else listOfNotNull(item.description?.takeIf { it.isNotBlank() }, item.type?.let { settingOption(it) }).joinToString(" · ")
                                SettingsListRow(title, subtitle, available && !loading) { inspect(item) }
                            }
                            if (!loading && page?.items?.isEmpty() == true) item {
                                Text(stringResource(R.string.remote_settings_empty), color = colors.muted,
                                    modifier = Modifier.padding(vertical = 18.dp))
                            }
                        }
                        if (pageNumber > 1 || page?.more == true) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SettingsAction(R.string.remote_settings_previous, available && !loading && pageNumber > 1) { pageNumber-- }
                            SettingsAction(R.string.remote_settings_next, available && !loading && page?.more == true) { pageNumber++ }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsListRow(title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalRemotePalette.current
    Surface(onClick = onClick, enabled = enabled, color = colors.bg0, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun SettingsAction(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(stringResource(label)) }
}

private fun confirm(@StringRes title: Int, command: SettingsCommand) =
    SettingsForm(title, action = command.action, confirmation = command)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsEntryView(
    section: SettingsSection,
    item: RemoteSettingsEntry,
    root: String,
    conversationId: String,
    insecure: Boolean,
    enabled: Boolean,
    run: (SettingsCommand) -> Unit,
    edit: (SettingsForm) -> Unit,
) {
    val colors = LocalRemotePalette.current
    fun command(action: String, vararg pairs: Pair<String, String>) = SettingsCommand(action, item.id, settingsValues(*pairs))
    fun toggle(action: String, key: String = "enabled", vararg pairs: Pair<String, String>) =
        SettingsCommand(action, item.id, settingsToggle(key, item.enabled != true, *pairs))
    val toggling = if (item.enabled == true) R.string.remote_settings_disable else R.string.remote_settings_enable
    val deleting = R.string.remote_settings_delete
    val canEdit = item.value("editable") != "false"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (section == SettingsSection.GENERAL) {
            Text(stringResource(generalLabel(item.id)), color = colors.text, style = MaterialTheme.typography.titleMedium)
        }
        if (!item.description.isNullOrBlank()) Text(item.description, color = colors.muted)
        if (!item.type.isNullOrBlank() && section != SettingsSection.GENERAL) Text(settingOption(item.type), color = colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when (section) {
                SettingsSection.GENERAL -> when (item.type) {
                    "switch" -> SettingsAction(toggling, enabled) { run(toggle("save", "value")) }
                    "choice" -> item.value("options").split(',').forEach { value ->
                        val update = command("save", "value" to value)
                        TextButton(enabled = enabled && value != item.value("value"), onClick = {
                            if (value == "fullAuto" || value == "off") edit(confirm(R.string.remote_settings_change_policy, update))
                            else run(update)
                        }) { Text(settingOption(value)) }
                    }
                    "number" -> SettingsAction(R.string.remote_settings_edit, enabled) {
                        edit(SettingsForm(R.string.remote_settings_edit, "save", item.id,
                            listOf(SettingsField("value", generalLabel(item.id), item.value("value"), required = true, number = true))))
                    }
                }
                SettingsSection.PROVIDERS -> {
                    SettingsAction(R.string.remote_settings_refresh_models, enabled && item.enabled == true) { run(command("refresh")) }
                    if (item.type != "conecode-free") {
                        SettingsAction(R.string.remote_settings_edit, enabled && !insecure) { edit(providerForm(item)) }
                        SettingsAction(toggling, enabled) { run(toggle("enable")) }
                        SettingsAction(deleting, enabled) { edit(confirm(deleting, command("delete"))) }
                    }
                }
                SettingsSection.MODELS -> {
                    val provider = "providerId" to item.value("providerId")
                    SettingsAction(R.string.remote_settings_select, enabled && item.value("selected") != "true") { run(command("select", provider)) }
                    SettingsAction(if (item.enabled == true) R.string.remote_settings_unfavorite else R.string.remote_settings_favorite, enabled) {
                        run(command("favorite", provider))
                    }
                    if (item.value("canToggleReasoning") == "true") {
                        SettingsAction(if (item.value("reasoning") == "true") R.string.remote_settings_reasoning_off else R.string.remote_settings_reasoning_on, enabled) {
                            run(command("reasoning", provider))
                        }
                    }
                    SettingsAction(R.string.remote_settings_limits, enabled) { edit(modelLimitsForm(item)) }
                }
                SettingsSection.PROMPTS -> {
                    SettingsAction(R.string.remote_settings_edit, enabled && canEdit) { edit(promptForm(item)) }
                    if (item.type == "builtin") SettingsAction(R.string.remote_settings_reset, enabled) { edit(confirm(R.string.remote_settings_reset, command("reset"))) }
                    else {
                        SettingsAction(toggling, enabled) { run(command("toggle")) }
                        SettingsAction(deleting, enabled) { edit(confirm(deleting, command("delete"))) }
                    }
                }
                SettingsSection.SKILLS -> {
                    if (item.type == "catalog") {
                        val installed = item.value("scopes").split(',')
                        val scopes = (if (root.isNotBlank()) listOf("global", "project") else listOf("global")) - installed.toSet()
                        SettingsAction(R.string.remote_settings_install, enabled && scopes.isNotEmpty()) {
                            edit(SettingsForm(R.string.remote_settings_install, "install", item.id,
                                listOf(SettingsField("scope", R.string.remote_settings_scope, scopes.first(), scopes))))
                        }
                    } else {
                        val scope = "scope" to item.type.orEmpty()
                        if (item.value("source") == "custom") {
                            SettingsAction(R.string.remote_settings_edit, enabled && canEdit) { edit(skillForm(item, root)) }
                        }
                        SettingsAction(toggling, enabled) { run(toggle("enable", "enabled", scope)) }
                        SettingsAction(R.string.remote_settings_run, enabled && item.enabled == true && conversationId.isNotEmpty()) {
                            edit(SettingsForm(R.string.remote_settings_run, "run", item.id,
                                listOf(SettingsField("task", R.string.remote_settings_task, multiline = true)),
                                mapOf(scope, "conversationId" to conversationId)))
                        }
                        SettingsAction(deleting, enabled) { edit(confirm(deleting, command("delete", scope))) }
                    }
                }
                SettingsSection.PLUGINS -> {
                    SettingsAction(toggling, enabled) { run(toggle("enable")) }
                    if (item.type == "custom") SettingsAction(deleting, enabled) { edit(confirm(deleting, command("delete"))) }
                }
                SettingsSection.MEMORY -> if (item.type != "legacy") {
                    val version = "version" to item.value("version")
                    SettingsAction(R.string.remote_settings_edit, enabled && canEdit) { edit(memoryForm(item, root)) }
                    SettingsAction(R.string.remote_settings_archive, enabled && item.value("status") != "archived") {
                        edit(confirm(R.string.remote_settings_archive, command("archive", version)))
                    }
                    SettingsAction(R.string.remote_settings_undo, enabled && (item.value("version").toIntOrNull() ?: 0) > 1) {
                        edit(confirm(R.string.remote_settings_undo, command("undo", version)))
                    }
                    SettingsAction(deleting, enabled) { edit(confirm(deleting, command("delete", version))) }
                }
                SettingsSection.MARKET, SettingsSection.INSTALLED -> {
                    val profile = "profileId" to item.value("profileId")
                    val versions = item.value("versions").split(',').filter { it.isNotBlank() }
                    val installed = item.value("installed") == "true"
                    if (versions.isNotEmpty()) {
                        SettingsAction(if (installed) R.string.remote_settings_update else R.string.remote_settings_install, enabled) {
                            edit(SettingsForm(if (installed) R.string.remote_settings_update else R.string.remote_settings_install,
                                "install", item.id, listOf(SettingsField("version", R.string.remote_settings_version, item.value("version"), versions)),
                                mapOf(profile)))
                        }
                    }
                    if (installed) {
                        SettingsAction(toggling, enabled) { run(toggle("enable", "enabled", profile)) }
                        SettingsAction(R.string.remote_settings_rollback, enabled) {
                            edit(confirm(R.string.remote_settings_rollback, command("rollback", profile)))
                        }
                        SettingsAction(R.string.remote_settings_uninstall, enabled) {
                            edit(confirm(R.string.remote_settings_uninstall, command("uninstall", profile)))
                        }
                    }
                }
                SettingsSection.PROFILES -> SettingsAction(R.string.remote_settings_switch_profile, enabled && item.enabled != true) {
                    run(command("switch"))
                }
                SettingsSection.SOURCES -> {
                    SettingsAction(R.string.remote_settings_reload, enabled) { run(command("refresh")) }
                    if (item.type != "bundled") SettingsAction(deleting, enabled) { edit(confirm(deleting, command("remove"))) }
                }
                SettingsSection.UPDATES -> {
                    val status = item.value("download")
                    if (item.enabled == true && status !in setOf("downloading", "completed")) {
                        val download = R.string.remote_settings_download_update
                        SettingsAction(download, enabled) { edit(confirm(download, command("download", "version" to item.value("offer")))) }
                    }
                    if (status == "completed") SettingsAction(R.string.remote_settings_reveal_update, enabled) { run(command("reveal")) }
                }
                SettingsSection.WIKI, SettingsSection.MCP, SettingsSection.AGENTS -> Unit
            }
        }
        when (section) {
            SettingsSection.MODELS -> {
                SettingsValue(R.string.remote_settings_context_window, item.value("contextWindow"))
                SettingsValue(R.string.remote_settings_output_tokens, item.value("maxOutputTokens"))
                SettingsValue(R.string.remote_settings_price_in, item.value("priceInput"))
                SettingsValue(R.string.remote_settings_price_out, item.value("priceOutput"))
                SettingsValue(R.string.remote_settings_capabilities, item.value("capabilities"))
            }
            SettingsSection.PROVIDERS -> {
                SettingsValue(R.string.remote_settings_default_model, item.value("defaultModel"))
                SettingsValue(R.string.remote_settings_timeout, item.value("timeout"))
            }
            SettingsSection.SKILLS, SettingsSection.MEMORY -> SettingsValue(R.string.remote_settings_tags, item.value("tags"))
            SettingsSection.MARKET, SettingsSection.INSTALLED -> SettingsValue(R.string.remote_settings_version, item.value("version"))
            SettingsSection.UPDATES -> {
                SettingsValue(R.string.remote_settings_latest_version, item.value("offer"))
                SettingsValue(R.string.remote_settings_download_state, settingOption(item.value("download")))
                if (item.value("percent").isNotBlank()) Text("${item.value("percent")}%", color = colors.text)
            }
            else -> Unit
        }
        if (!canEdit) Text(stringResource(R.string.remote_settings_long_content), color = colors.muted)
        if (!item.content.isNullOrBlank()) SelectionContainer { Text(item.content, color = colors.text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SettingsValue(@StringRes label: Int, value: String) {
    if (value.isBlank()) return
    val colors = LocalRemotePalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), color = colors.muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = colors.text, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsFormView(form: SettingsForm, enabled: Boolean, run: (SettingsCommand) -> Unit) {
    val colors = LocalRemotePalette.current
    val draft = remember(form) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.initial) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val confirmation = form.confirmation
        if (confirmation != null) {
            Text(stringResource(R.string.remote_settings_confirm), color = colors.muted)
            Button(enabled = enabled, onClick = { run(confirmation) }) { Text(stringResource(form.title)) }
        } else {
            form.fields.forEach { field ->
                val value = draft[field.key].orEmpty()
                if (field.options.isNotEmpty()) {
                    Text(stringResource(field.label), color = colors.text, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        field.options.forEach { option ->
                            FilterChip(selected = value == option, onClick = { draft[field.key] = option },
                                enabled = enabled, label = { Text(settingOption(option)) })
                        }
                    }
                } else {
                    OutlinedTextField(value, { draft[field.key] = it.take(if (field.multiline) 160_000 else 16_384) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(field.label)) },
                        enabled = enabled, singleLine = !field.multiline,
                        minLines = if (field.multiline) 3 else 1, maxLines = if (field.multiline) 12 else 1,
                        keyboardOptions = KeyboardOptions(keyboardType = when {
                            field.password -> KeyboardType.Password
                            field.number -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        }),
                        visualTransformation = if (field.password) PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
            Button(enabled = enabled && form.fields.all { !it.required || !draft[it.key].isNullOrBlank() }, onClick = {
                run(SettingsCommand(form.action, form.id, settingsValues(*(form.base + draft).toList().toTypedArray())))
            }) { Text(stringResource(if (form.title == R.string.remote_settings_edit || form.title == R.string.remote_settings_add) R.string.remote_settings_save else form.title)) }
        }
    }
}

private fun providerForm(item: RemoteSettingsEntry?) = SettingsForm(
    if (item == null) R.string.remote_settings_add else R.string.remote_settings_edit,
    "save", item?.id.orEmpty(), listOf(
        SettingsField("name", R.string.remote_settings_name, item?.title.orEmpty(), required = true),
        SettingsField("type", R.string.remote_settings_type, item?.type ?: "openai",
            listOf("openai", "anthropic", "gemini", "deepseek", "openrouter", "custom")),
        SettingsField("baseUrl", if (item == null) R.string.remote_settings_endpoint else R.string.remote_settings_new_endpoint, required = item == null),
        SettingsField("apiKey", if (item == null) R.string.remote_settings_key else R.string.remote_settings_new_key, password = true),
        SettingsField("defaultModel", R.string.remote_settings_default_model, item?.value("defaultModel").orEmpty()),
        SettingsField("timeout", R.string.remote_settings_timeout, item?.value("timeout") ?: "60000", required = true, number = true),
    ),
)

private fun modelLimitsForm(item: RemoteSettingsEntry) = SettingsForm(R.string.remote_settings_limits, "limits", item.id,
    listOf(
        SettingsField("contextWindow", R.string.remote_settings_context_window, item.value("contextWindow").takeUnless { it == item.value("autoContextWindow") }.orEmpty()),
    ),
    mapOf("providerId" to item.value("providerId")),
)

private fun promptForm(item: RemoteSettingsEntry?) = SettingsForm(
    if (item == null) R.string.remote_settings_add else R.string.remote_settings_edit, "save", item?.id.orEmpty(),
    buildList {
        if (item?.type != "builtin") {
            add(SettingsField("title", R.string.remote_settings_name, item?.title.orEmpty(), required = true))
            add(SettingsField("description", R.string.remote_settings_description, item?.description.orEmpty()))
            add(SettingsField("category", R.string.remote_settings_category, item?.value("category").orEmpty()))
        }
        add(SettingsField("content", R.string.remote_settings_content, item?.content.orEmpty(), required = true, multiline = true))
    },
)

private fun skillForm(item: RemoteSettingsEntry?, root: String) = SettingsForm(
    if (item == null) R.string.remote_settings_add else R.string.remote_settings_edit, "save", item?.id.orEmpty(),
    buildList {
        add(SettingsField("name", R.string.remote_settings_name, item?.title.orEmpty(), required = true))
        add(SettingsField("description", R.string.remote_settings_description, item?.description.orEmpty(), required = true))
        add(SettingsField("tags", R.string.remote_settings_tags, item?.value("tags").orEmpty()))
        add(SettingsField("body", R.string.remote_settings_content, item?.content.orEmpty(), required = true, multiline = true))
        if (item == null) add(SettingsField("scope", R.string.remote_settings_scope, if (root.isEmpty()) "global" else "project",
            if (root.isEmpty()) listOf("global") else listOf("global", "project")))
    },
    item?.type?.let { mapOf("scope" to it) }.orEmpty(),
)

private fun memoryForm(item: RemoteSettingsEntry?, root: String) = SettingsForm(
    if (item == null) R.string.remote_settings_add else R.string.remote_settings_edit, "save", item?.id.orEmpty(),
    buildList {
        add(SettingsField("title", R.string.remote_settings_name, item?.title.orEmpty()))
        add(SettingsField("content", R.string.remote_settings_content, item?.content.orEmpty(), required = true, multiline = true))
        add(SettingsField("tags", R.string.remote_settings_tags, item?.value("tags").orEmpty()))
        add(SettingsField("why", R.string.remote_settings_reason, item?.description.orEmpty(), multiline = true))
        if (item == null) add(SettingsField("layer", R.string.remote_settings_scope, if (root.isEmpty()) "global" else "project",
            if (root.isEmpty()) listOf("global") else listOf("global", "project")))
    },
    item?.let { mapOf("version" to it.value("version")) }.orEmpty(),
)

private fun pluginForm() = SettingsForm(R.string.remote_settings_add, "add", fields = listOf(
    SettingsField("name", R.string.remote_settings_name, required = true),
    SettingsField("description", R.string.remote_settings_description, required = true, multiline = true),
    SettingsField("skillId", R.string.remote_settings_skill_id),
))

private fun profileForm() = SettingsForm(R.string.remote_settings_add, "create", fields = listOf(
    SettingsField("id", R.string.remote_settings_profile_id, required = true),
    SettingsField("name", R.string.remote_settings_name),
))

@StringRes
private fun generalLabel(id: String) = when (id) {
    "theme" -> R.string.remote_settings_theme
    "language" -> R.string.remote_settings_language
    "toolCallMode" -> R.string.remote_settings_tool_call
    "notificationMode" -> R.string.remote_settings_notifications
    "approvalMode" -> R.string.remote_settings_approval
    "sandboxMode" -> R.string.remote_settings_sandbox
    "sendWithEnter" -> R.string.remote_settings_enter
    "streamEnabled" -> R.string.remote_settings_stream
    "autoIncludeFileContext" -> R.string.remote_settings_file_context
    "fullContext" -> R.string.remote_settings_full_context
    "notifyApprovals" -> R.string.remote_settings_notify_approvals
    "preventSleepWhileRunning" -> R.string.remote_settings_prevent_sleep
    "costLock" -> R.string.remote_settings_cost_lock
    "sandboxAllowNetwork" -> R.string.remote_settings_network
    "maxEpisodeCost" -> R.string.remote_settings_cost
    "maxContextFileSize" -> R.string.remote_settings_max_file
    else -> R.string.remote_settings_general
}

@Composable
private fun settingOption(value: String): String {
    val label = when (value) {
        "system" -> R.string.remote_settings_system
        "light" -> R.string.remote_settings_light
        "dark" -> R.string.remote_settings_dark
        "auto" -> R.string.remote_settings_auto
        "native" -> R.string.remote_settings_native
        "prompt" -> R.string.remote_settings_prompt_mode
        "never" -> R.string.remote_settings_never
        "background" -> R.string.remote_settings_background
        "always" -> R.string.remote_settings_always
        "suggest" -> R.string.remote_settings_ask
        "autoEdit" -> R.string.remote_settings_auto_edit
        "fullAuto" -> R.string.remote_settings_full_auto
        "workspaceWrite" -> R.string.remote_settings_workspace_write
        "off" -> R.string.remote_settings_off
        "global" -> R.string.remote_settings_global
        "project" -> R.string.remote_settings_project
        "catalog" -> R.string.remote_settings_catalog
        "builtin" -> R.string.remote_settings_builtin
        "custom" -> R.string.remote_settings_custom
        "noTools" -> R.string.remote_settings_no_tools
        "downloading" -> R.string.remote_settings_downloading
        "completed" -> R.string.remote_settings_downloaded
        "error" -> R.string.remote_settings_download_error
        "en" -> return "English"
        "zh" -> return "简体中文"
        "zhHant" -> return "繁體中文"
        "ja" -> return "日本語"
        "openai" -> return "OpenAI"
        "anthropic" -> return "Anthropic"
        "gemini" -> return "Gemini"
        "deepseek" -> return "DeepSeek"
        "openrouter" -> return "OpenRouter"
        "conecode-free" -> return "ConeCode Free"
        else -> return value
    }
    return stringResource(label)
}

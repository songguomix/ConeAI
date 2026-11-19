package com.cone.agent.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cone.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private enum class Feature { WORK, IMAGE, SETTINGS }

@Composable
internal fun RemoteFeatures(viewModel: RemoteViewModel, snapshot: RemoteSnapshot, onTab: (RemoteTab) -> Unit) {
    var selected by remember { mutableStateOf<Feature?>(null) }
    val scope = rememberCoroutineScope()
    val errorLabel = stringResource(R.string.remote_feature_failed)
    var error by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    BackHandler(selected != null) { selected = null }
    when (selected) {
        Feature.WORK -> RemoteProjectWork(snapshot, viewModel, { onTab(RemoteTab.CHAT) }) { selected = null }
        Feature.IMAGE -> RemoteImageStudio(snapshot, viewModel, { onTab(RemoteTab.CHAT) }) { selected = null }
        Feature.SETTINGS -> RemoteSettingsPanel(viewModel, snapshot) { selected = null }
        null -> FeaturePage(stringResource(R.string.remote_more)) {
            if ("remoteWork" in snapshot.capabilities) FeatureButton(R.string.remote_project_work) { selected = Feature.WORK }
            if ("remoteImageStudio" in snapshot.capabilities) FeatureButton(R.string.remote_image_studio) { selected = Feature.IMAGE }
            if ("remoteSettings" in snapshot.capabilities) FeatureButton(R.string.remote_desktop_settings) { selected = Feature.SETTINGS }
            if (RemoteView.from(snapshot) != RemoteView.WORK) {
                FeatureButton(R.string.remote_files) { onTab(RemoteTab.FILES) }
                FeatureButton(R.string.remote_terminal) { onTab(RemoteTab.TERMINAL) }
                FeatureButton(R.string.remote_changes) { onTab(RemoteTab.CHANGES) }
            }
            HorizontalDivider(color = LocalRemotePalette.current.border)
            FeatureHeading(stringResource(R.string.remote_desktop))
            FeatureButton(R.string.remote_desktop_full) { onTab(RemoteTab.SCREEN) }
            if ("remoteDesktopPanel" in snapshot.capabilities) {
                val panels = listOf(
                    "preview" to R.string.remote_desktop_preview,
                    "terminal" to R.string.remote_desktop_terminal,
                    "agents" to R.string.remote_desktop_agents,
                    "background" to R.string.remote_desktop_background,
                    "computer" to R.string.remote_desktop_computer,
                    "market" to R.string.remote_desktop_market,
                    "settings" to R.string.remote_desktop_settings,
                )
                for ((panel, label) in panels) {
                    FeatureButton(label, enabled = !opening) {
                        opening = true
                        error = null
                        scope.launch {
                            try {
                                val result = viewModel.featureRequest("remoteDesktopPanel", mapOf("panel" to panel))
                                if (result?.flag("ok") == true) onTab(RemoteTab.SCREEN)
                                else error = result?.text("error") ?: errorLabel
                            } finally {
                                opening = false
                            }
                        }
                    }
                }
            }
            FeatureError(error)
        }
    }
}

@Composable
private fun RemoteProjectWork(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, onConversation: () -> Unit, onBack: () -> Unit) {
    val progress = rememberFeatureCalls(viewModel, "remoteWork", snapshot.rootPath) { state ->
        state.flag("scanning") || state.flag("loadingIdeas") || state.obj("brief")?.let { it.flag("generating") || it.flag("starting") } == true ||
            state.obj("autopilot")?.text("phase") in setOf("creating", "building", "verifying", "repairing")
    }
    val state = progress.result
    val root = state?.text("rootPath").orEmpty()
    val conversation = snapshot.activeConversationId.orEmpty()
    val brief = state?.obj("brief")
    val title = brief?.text("title")
    var idea by remember(brief?.text("idea")) { mutableStateOf(brief?.text("idea").orEmpty()) }
    var prompt by remember(brief?.text("prompt")) { mutableStateOf(brief?.text("prompt").orEmpty()) }
    var taskDraft by remember(root) { mutableStateOf<JsonObject?>(null) }
    var taskPrompt by remember(taskDraft) { mutableStateOf(taskDraft?.text("prompt").orEmpty()) }
    var project by remember { mutableStateOf<JsonObject?>(null) }

    FeaturePage(stringResource(R.string.remote_project_work), onBack) {
        FeatureError(progress.error)
        if (state == null && progress.loading) CircularProgressIndicator()
        if (state == null && !progress.loading) FeatureButton(R.string.remote_feature_refresh) { progress.call("state") }
        if (root.isNotBlank()) {
            FeatureHeading(root)
            val scan = state?.obj("scan")
            if (scan != null) {
                val count = scan.text("fileCount").orEmpty() + if (scan.flag("fileCountCapped")) "+" else ""
                FeatureLine(stringResource(R.string.remote_project_file_count, count))
                scan.obj("git")?.text("branch")?.let { FeatureLine(it) }
                FeatureLine(scan.texts("scripts").joinToString(" · "))
                for (todo in scan.objects("todoComments").take(12)) {
                    FeatureLine("${todo.text("file")}:${todo.text("line")}  ${todo.text("text")}")
                }
            }
            FeatureButton(R.string.remote_project_scan, enabled = !progress.loading && state?.flag("scanning") != true) {
                progress.call("scan", mapOf("rootPath" to root))
            }
            FeatureButton(R.string.remote_project_suggest, enabled = !progress.loading && state?.flag("scanning") != true && snapshot.model != null) {
                progress.call("suggest", mapOf("rootPath" to root))
            }
            FeatureError(state?.text("scanError"))
            for (task in state?.objects("tasks").orEmpty()) {
                TextButton(onClick = { taskDraft = task }, modifier = Modifier.fillMaxWidth()) {
                    Text(task.text("title").orEmpty(), modifier = Modifier.fillMaxWidth())
                }
                FeatureLine(task.text("rationale").orEmpty())
            }
            HorizontalDivider(color = LocalRemotePalette.current.border)
        }
        FeatureHeading(stringResource(R.string.remote_project_ideas))
        val autopilot = state?.obj("autopilot")
        val phase = autopilot?.text("phase")
        val projectRunning = phase in setOf("creating", "building", "verifying", "repairing")
        FeatureButton(R.string.remote_project_load_ideas, enabled = !progress.loading && state?.flag("loadingIdeas") != true && !projectRunning) {
            progress.call("ideas")
        }
        if (autopilot != null && phase != null && phase != "idle") {
            val phaseLabel = when (phase) {
                "creating" -> R.string.remote_project_creating
                "building" -> R.string.remote_project_building
                "verifying" -> R.string.remote_project_verifying
                "repairing" -> R.string.remote_project_repairing
                "done" -> R.string.remote_project_done
                else -> R.string.remote_feature_failed
            }
            FeatureLine(stringResource(phaseLabel))
            FeatureLine(autopilot.text("title").orEmpty())
            FeatureLine(autopilot.text("projectDir").orEmpty())
            FeatureLine(autopilot.text("verifyCommand").orEmpty())
            FeatureLine(autopilot.text("lastOutput").orEmpty())
            FeatureError(autopilot.text("error"))
        }
        for (proposal in state?.objects("ideas").orEmpty()) {
            TextButton(onClick = { project = proposal }, enabled = !projectRunning && snapshot.model != null, modifier = Modifier.fillMaxWidth()) {
                Text(proposal.text("title").orEmpty(), modifier = Modifier.fillMaxWidth())
            }
            FeatureLine(proposal.text("stack").orEmpty())
        }
        HorizontalDivider(color = LocalRemotePalette.current.border)
        FeatureHeading(stringResource(R.string.remote_project_brief))
        FeatureInput(idea, { idea = it }, R.string.remote_project_idea, maxLines = 4)
        val briefing = brief?.flag("generating") == true
        val starting = brief?.flag("starting") == true
        if (briefing) FeatureButton(R.string.remote_feature_cancel) { progress.call("cancelBrief") }
        else FeatureButton(R.string.remote_project_generate_brief, enabled = idea.isNotBlank() && !progress.loading && !starting && snapshot.model != null) {
            progress.call("generateBrief", mapOf("idea" to idea))
        }
        if (brief != null && title != null) {
            FeatureHeading(title)
            FeatureInput(prompt, { prompt = it }, R.string.remote_project_prompt, maxLines = 12)
            FeatureButton(R.string.remote_project_start, enabled = !starting && !briefing && !progress.loading && prompt.isNotBlank()) {
                progress.call("startBrief", mapOf(
                    "title" to title, "idea" to brief.text("idea").orEmpty(), "prompt" to prompt,
                    "rootPath" to root, "conversationId" to conversation,
                ))
            }
        }
        FeatureLine(brief?.text("directory").orEmpty())
        FeatureError(brief?.text("error"))
    }
    taskDraft?.let { task ->
        AlertDialog(
            onDismissRequest = { taskDraft = null },
            title = { Text(task.text("title").orEmpty()) },
            text = { FeatureInput(taskPrompt, { taskPrompt = it }, R.string.remote_project_prompt, maxLines = 10) },
            confirmButton = {
                TextButton(enabled = taskPrompt.isNotBlank() && snapshot.model != null, onClick = {
                    progress.call("startTask", mapOf("id" to task.text("id").orEmpty(), "prompt" to taskPrompt,
                        "rootPath" to root, "conversationId" to conversation), update = false, onSuccess = onConversation)
                    taskDraft = null
                }) { Text(stringResource(R.string.remote_project_start)) }
            },
            dismissButton = { TextButton(onClick = { taskDraft = null }) { Text(stringResource(R.string.remote_feature_cancel)) } },
        )
    }
    project?.let { proposal ->
        AlertDialog(
            onDismissRequest = { project = null },
            title = { Text(proposal.text("title").orEmpty()) },
            text = { Text(proposal.text("description").orEmpty() + "\n\n" + stringResource(R.string.remote_project_autopilot_confirm)) },
            confirmButton = { TextButton(onClick = {
                progress.call("startIdea", mapOf("id" to proposal.text("id").orEmpty()))
                project = null
            }) { Text(stringResource(R.string.remote_project_start)) } },
            dismissButton = { TextButton(onClick = { project = null }) { Text(stringResource(R.string.remote_feature_cancel)) } },
        )
    }
}

internal class RemoteFeatureCalls(
    private val viewModel: RemoteViewModel,
    private val type: String,
    private val scope: CoroutineScope,
    private val failed: String,
) {
    var result by mutableStateOf<JsonObject?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var requestVersion = 0

    fun call(action: String, fields: Map<String, String> = emptyMap(), update: Boolean = true, onSuccess: () -> Unit = {}) {
        if (loading) return
        loading = true
        error = null
        val version = ++requestVersion
        scope.launch {
            try { request(version, action, fields, update, onSuccess) } finally { loading = false }
        }
    }

    suspend fun refresh() {
        if (!loading) request(++requestVersion, "state", emptyMap(), true) {}
    }

    private suspend fun request(version: Int, action: String, fields: Map<String, String>, update: Boolean, onSuccess: () -> Unit) {
        val response = viewModel.featureRequest(type, fields + ("action" to action))
        if (version != requestVersion) return
        if (response?.flag("ok") != true) {
            error = response?.text("error") ?: failed
            return
        }
        error = null
        if (update) result = response
        onSuccess()
    }
}

@Composable
internal fun rememberFeatureCalls(viewModel: RemoteViewModel, type: String, contextKey: String?, busy: (JsonObject) -> Boolean): RemoteFeatureCalls {
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.remote_feature_failed)
    val calls = remember(viewModel, type, contextKey, scope, failed) { RemoteFeatureCalls(viewModel, type, scope, failed) }
    LaunchedEffect(calls) { calls.call("state") }
    val running = calls.result?.let(busy) == true
    LaunchedEffect(calls, running) {
        if (running) while (true) {
            delay(1200)
            calls.refresh()
        }
    }
    return calls
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
private fun JsonObject.elements(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
internal fun JsonObject.objects(key: String): List<JsonObject> = elements(key).filterIsInstance<JsonObject>()
private fun JsonObject.texts(key: String): List<String> = elements(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

@Composable
internal fun FeaturePage(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val cx = LocalRemotePalette.current
    Column(Modifier.fillMaxSize().background(cx.bg0).navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.remote_feature_back)) }
            Text(title, color = cx.text, style = RemoteType.brand, modifier = Modifier.padding(vertical = 7.dp))
        }
        content()
    }
}

@Composable
internal fun FeatureHeading(text: String) {
    Text(text, color = LocalRemotePalette.current.text, style = RemoteType.title)
}

@Composable
internal fun FeatureLine(text: String) {
    if (text.isNotBlank()) Text(text, color = LocalRemotePalette.current.muted, style = RemoteType.bodySmall)
}

@Composable
internal fun FeatureError(text: String?) {
    if (!text.isNullOrBlank()) Text(text, color = LocalRemotePalette.current.danger, style = RemoteType.bodySmall)
}

@Composable
internal fun FeatureButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(stringResource(label)) }
}

@Composable
internal fun FeatureInput(text: String, onChange: (String) -> Unit, label: Int, maxLines: Int = 1) {
    OutlinedTextField(text, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        singleLine = maxLines == 1, maxLines = maxLines)
}

package com.cone.agent.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cone.agent.R
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject?.workspaceString(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject?.workspaceOk(): Boolean = (this?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject?.workspaceStrings(key: String): List<String> =
    (this?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

@Composable
internal fun WorkspaceError(result: JsonObject?, modifier: Modifier = Modifier) {
    val failure = result.workspaceString("error")
    val text = when (failure) {
        "not-a-repository" -> stringResource(R.string.remote_git_no_repo)
        "no-changes" -> stringResource(R.string.remote_git_clean)
        "invalid-name" -> stringResource(R.string.remote_workspace_invalid_name)
        "invalid-path" -> stringResource(R.string.remote_workspace_absolute_path)
        "already-exists", "duplicate", "inside-existing", "contains-existing" -> stringResource(R.string.remote_workspace_exists)
        "unsaved-editor" -> stringResource(R.string.remote_workspace_unsaved)
        "file-too-large" -> stringResource(R.string.remote_workspace_file_large)
        "unsupported-file" -> stringResource(R.string.remote_cant_open)
        "stale-workspace", "stale-conversation", "workspace-busy", null, "" -> stringResource(R.string.remote_workspace_failed)
        "invalid-revision" -> stringResource(R.string.remote_git_revision)
        else -> failure
    }
    Text(text, color = LocalRemotePalette.current.danger, style = RemoteType.bodySmall, modifier = modifier)
}

private enum class WorkspacePane(val label: Int) {
    Files(R.string.remote_files), Git(R.string.remote_git_status), Worktrees(R.string.remote_git_worktrees),
}

@Composable
internal fun RemoteWorkspace(
    snapshot: RemoteSnapshot,
    viewModel: RemoteViewModel,
    onReviewStarted: () -> Unit,
    files: @Composable (root: String?, enhanced: Boolean) -> Unit,
) {
    if ("workspaceControl" !in snapshot.capabilities) {
        files(snapshot.rootPath, false)
        return
    }
    val cx = LocalRemotePalette.current
    val roots = snapshot.workspaceRoots.ifEmpty { listOfNotNull(snapshot.rootPath) }
    val scope = rememberCoroutineScope()
    var selected by remember(snapshot.activeConversationId, snapshot.rootPath) { mutableStateOf(WorkspacePane.Files) }
    var browsingRoot by remember(snapshot.activeConversationId, snapshot.rootPath) { mutableStateOf(snapshot.rootPath) }
    var rootMenu by remember { mutableStateOf(false) }
    var addRootDialog by remember { mutableStateOf(false) }
    var changingRoot by remember { mutableStateOf(false) }
    var rootError by remember { mutableStateOf<JsonObject?>(null) }
    val selectedRoot = browsingRoot?.takeIf { it in roots } ?: snapshot.rootPath

    fun addRoot(path: String?) {
        addRootDialog = false
        changingRoot = true
        rootError = null
        scope.launch {
            val reply = viewModel.workspaceRequest("workspaceAddRoot", if (path == null) emptyMap() else mapOf("path" to path))
            rootError = if (reply == null) JsonObject(emptyMap()) else reply.takeUnless {
                it.workspaceOk() || (path == null && it.workspaceString("error").isNullOrBlank())
            }
            if (reply.workspaceOk()) browsingRoot = reply.workspaceString("path")
            changingRoot = false
        }
    }

    if (addRootDialog) {
        var path by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addRootDialog = false },
            title = { Text(stringResource(R.string.remote_workspace_add_root)) },
            text = {
                DarkInput(path, { path = it.take(4096) }, stringResource(R.string.remote_workspace_absolute_path),
                    Modifier.fillMaxWidth(), singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = path.isNotBlank(), onClick = { addRoot(path.trim()) }) {
                    Text(stringResource(R.string.remote_open_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = { addRoot(null) }) { Text(stringResource(R.string.remote_workspace_choose_desktop)) }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(cx.bg0)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkspacePane.entries.forEach { pane ->
                FilterChip(selected = selected == pane, onClick = { selected = pane }, label = { Text(stringResource(pane.label)) })
            }
        }
        if (selected == WorkspacePane.Files && selectedRoot != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { rootMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !changingRoot) {
                        Text(selectedRoot, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (roots.size > 1) Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                        roots.forEach { root ->
                            DropdownMenuItem(text = { Text(root, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = { browsingRoot = root; rootMenu = false })
                        }
                    }
                }
                IconButton(enabled = !changingRoot, onClick = { addRootDialog = true }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.remote_workspace_add_root), tint = cx.accent)
                }
                IconButton(enabled = !changingRoot, onClick = {
                    changingRoot = true
                    rootError = null
                    scope.launch {
                        val reply = viewModel.workspaceRequest("workspaceCloseRoot", mapOf("path" to selectedRoot))
                        rootError = if (reply.workspaceOk()) null else reply ?: JsonObject(emptyMap())
                        if (reply.workspaceOk()) browsingRoot = snapshot.rootPath
                        changingRoot = false
                    }
                }) { Icon(Icons.Filled.Close, stringResource(R.string.remote_workspace_close_root), tint = cx.muted) }
            }
        }
        if (selected == WorkspacePane.Files && selectedRoot == null) TextButton(enabled = !changingRoot,
            onClick = { addRootDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.remote_open_folder))
        }
        if (rootError != null && selected == WorkspacePane.Files) WorkspaceError(rootError, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        HorizontalDivider(color = cx.border)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                WorkspacePane.Files -> key(snapshot.activeConversationId, selectedRoot) { files(selectedRoot, true) }
                WorkspacePane.Git -> key(snapshot.rootPath) { RemoteGitPanel(snapshot, viewModel, onReviewStarted) }
                WorkspacePane.Worktrees -> key(snapshot.rootPath) { RemoteWorktrees(snapshot.rootPath, viewModel) }
            }
        }
    }
}

private enum class GitScope(val wire: String, val label: Int) {
    Uncommitted("uncommitted", R.string.remote_git_uncommitted), Base("base", R.string.remote_git_base), Commit("commit", R.string.remote_git_commit),
}

@Composable
private fun RemoteGitPanel(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, onReviewStarted: () -> Unit) {
    val root = snapshot.rootPath
    if (root == null) {
        WorkspaceEmpty(R.string.remote_no_folder)
        return
    }
    val cx = LocalRemotePalette.current
    val coroutine = rememberCoroutineScope()
    var revision by remember { mutableStateOf(GitScope.Uncommitted) }
    var branch by remember { mutableStateOf("") }
    var commit by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<JsonObject?>(null) }
    var diff by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val fields = mapOf("root" to root, "scope" to revision.wire, "target" to if (revision == GitScope.Commit) commit.trim() else branch)

    suspend fun loadDiff(request: Map<String, String>) {
        val result = viewModel.workspaceRequest("workspaceGitDiff", request)
        diff = result?.takeIf { it.workspaceOk() }
        error = if (result.workspaceOk()) null else result ?: JsonObject(emptyMap())
    }

    LaunchedEffect(refresh) {
        busy = true
        error = null
        val result = viewModel.workspaceRequest("workspaceGitInfo", mapOf("root" to root))
        info = result?.takeIf { it.workspaceOk() }
        val branches = result.workspaceStrings("branches")
        if (branch !in branches) {
            branch = listOf("main", "master").firstOrNull { it in branches }
                ?: branches.firstOrNull { it.endsWith("/main") || it.endsWith("/master") }
                ?: branches.firstOrNull().orEmpty()
        }
        if (result.workspaceOk()) {
            val request = fields + ("target" to if (revision == GitScope.Commit) commit.trim() else branch)
            if (revision == GitScope.Uncommitted || request["target"].orEmpty().isNotBlank()) loadDiff(request)
        } else {
            error = result ?: JsonObject(emptyMap())
            diff = null
        }
        busy = false
    }

    val canReview = !busy && info != null && (revision == GitScope.Uncommitted || fields["target"].orEmpty().isNotBlank())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(info.workspaceString("branch") ?: stringResource(R.string.remote_git_status), color = cx.text,
                    style = RemoteType.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, enabled = !busy) { Icon(Icons.Filled.Refresh, stringResource(R.string.remote_workspace_refresh), tint = cx.accent) }
            }
        }
        if (info != null) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GitScope.entries.forEach { option ->
                        FilterChip(enabled = !busy, selected = revision == option, onClick = { revision = option; diff = null; error = null },
                            label = { Text(stringResource(option.label)) })
                    }
                }
            }
            if (revision == GitScope.Base) item { GitRefMenu(branch, info.workspaceStrings("branches"), !busy) { branch = it; diff = null } }
            if (revision == GitScope.Commit) item {
                DarkInput(commit, { if (!busy) { commit = it.take(240); diff = null } },
                    stringResource(R.string.remote_git_revision), Modifier.fillMaxWidth(), singleLine = true)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = canReview, onClick = {
                        busy = true
                        coroutine.launch { loadDiff(fields); busy = false }
                    }) { Text(stringResource(R.string.remote_git_diff)) }
                    Button(enabled = canReview && snapshot.model != null && !snapshot.isStreaming, onClick = {
                        busy = true
                        coroutine.launch {
                            val result = viewModel.workspaceRequest("workspaceGitReview", fields + ("conversationId" to snapshot.activeConversationId.orEmpty()))
                            busy = false
                            if (result.workspaceOk()) onReviewStarted() else error = result ?: JsonObject(emptyMap())
                        }
                    }) { Text(stringResource(R.string.remote_git_review)) }
                }
            }
        }
        if (busy) item { CircularProgressIndicator(color = cx.accent, modifier = Modifier.padding(6.dp)) }
        if (error != null) item { WorkspaceError(error) }
        val status = diff.workspaceString("status").orEmpty()
        val patch = diff.workspaceString("diff").orEmpty()
        if (diff != null && status.isBlank() && patch.isBlank()) item {
            Text(stringResource(R.string.remote_git_clean), color = cx.muted, style = RemoteType.body)
        }
        if (status.isNotBlank()) item { GitOutput(status) }
        if (patch.isNotBlank()) item { GitOutput(patch) }
        if ((diff?.get("truncated") as? JsonPrimitive)?.booleanOrNull == true) item {
            Text(stringResource(R.string.remote_git_truncated), color = cx.muted, style = RemoteType.bodySmall)
        }
    }
}

@Composable
private fun GitOutput(value: String) {
    val cx = LocalRemotePalette.current
    SelectionContainer(Modifier.horizontalScroll(rememberScrollState()).background(cx.bg1).padding(8.dp)) {
        Text(value.trimEnd(), color = cx.text, style = RemoteType.monoBody, softWrap = false)
    }
}

@Composable
private fun GitRefMenu(value: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled && options.isNotEmpty(), onClick = { expanded = true }) {
            Text(value.ifEmpty { stringResource(R.string.remote_git_base) }, overflow = TextOverflow.Ellipsis, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { ref -> DropdownMenuItem(text = { Text(ref) }, onClick = { expanded = false; onSelect(ref) }) }
        }
    }
}

@Serializable
private data class RemoteWorktree(
    val path: String,
    val head: String = "",
    val branch: String? = null,
    val isMain: Boolean = false,
    val managed: Boolean = false,
    val dirty: Int = 0,
)

@Composable
private fun RemoteWorktrees(root: String?, viewModel: RemoteViewModel) {
    if (root == null) {
        WorkspaceEmpty(R.string.remote_no_folder)
        return
    }
    val cx = LocalRemotePalette.current
    val coroutine = rememberCoroutineScope()
    var branches by remember { mutableStateOf(listOf("HEAD")) }
    var trees by remember { mutableStateOf<List<RemoteWorktree>>(emptyList()) }
    var currentPath by remember { mutableStateOf(root) }
    var error by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var ref by remember { mutableStateOf("HEAD") }
    var name by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<RemoteWorktree?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    suspend fun load() {
        val fields = mapOf("root" to root)
        val result = viewModel.workspaceRequest("workspaceWorktrees", fields)
        ready = result.workspaceOk()
        if (ready) {
            trees = (result?.get("worktrees") as? JsonArray).orEmpty().mapNotNull {
                runCatching { RemoteJson.decodeFromJsonElement(RemoteWorktree.serializer(), it) }.getOrNull()
            }
            currentPath = result.workspaceString("currentPath") ?: root
            branches = (listOf("HEAD") + viewModel.workspaceRequest("workspaceGitInfo", fields).workspaceStrings("branches")).distinct()
            if (ref !in branches) ref = "HEAD"
        } else {
            trees = emptyList()
            error = result ?: JsonObject(emptyMap())
        }
    }

    fun change(action: String, args: Map<String, String>, onSuccess: () -> Unit = {}) {
        busy = true
        error = null
        coroutine.launch {
            val result = viewModel.workspaceRequest(action, args + ("root" to root))
            if (result.workspaceOk()) {
                onSuccess()
                if (action != "workspaceOpenWorktree") load()
            } else {
                error = result ?: JsonObject(emptyMap())
            }
            busy = false
        }
    }

    LaunchedEffect(refresh) {
        busy = true
        error = null
        load()
        busy = false
    }
    removing?.let { item ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.remote_git_remove_worktree)) },
            text = { Text(item.path) }, confirmButton = {
                TextButton(onClick = { removing = null; change("workspaceRemoveWorktree", mapOf("path" to item.path)) }) {
                    Text(stringResource(R.string.action_delete), color = cx.danger)
                }
            }, dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_git_worktrees), color = cx.text, style = RemoteType.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, enabled = !busy) { Icon(Icons.Filled.Refresh, stringResource(R.string.remote_workspace_refresh), tint = cx.accent) }
            }
        }
        if (ready) item {
            GitRefMenu(ref, branches, !busy) { ref = it }
            DarkInput(name, { if (!busy) name = it.take(255) }, stringResource(R.string.remote_git_worktree_name), Modifier.fillMaxWidth(), singleLine = true)
            Button(enabled = !busy, onClick = { change("workspaceCreateWorktree", mapOf("ref" to ref, "name" to name.trim())) { name = "" } }) {
                Text(stringResource(R.string.remote_git_create_worktree))
            }
        }
        if (busy) item { CircularProgressIndicator(color = cx.accent, modifier = Modifier.padding(6.dp)) }
        if (error != null) item { WorkspaceError(error) }
        items(trees, key = { it.path }) { item ->
            Column(Modifier.fillMaxWidth().background(cx.bg1).padding(8.dp)) {
                Text(item.branch ?: item.head.take(8), color = cx.text, style = RemoteType.body)
                Text(item.path, color = cx.muted, style = RemoteType.monoBody)
                Text(if (item.dirty == 0) stringResource(R.string.remote_git_clean) else stringResource(R.string.remote_git_dirty, item.dirty),
                    color = cx.muted, style = RemoteType.bodySmall)
                if (item.path != currentPath) Row {
                    TextButton(enabled = !busy, onClick = { change("workspaceOpenWorktree", mapOf("path" to item.path)) }) {
                        Text(stringResource(R.string.remote_open_folder))
                    }
                    if (!item.isMain && item.managed && item.dirty == 0) {
                        TextButton(enabled = !busy, onClick = { removing = item }) { Text(stringResource(R.string.action_delete), color = cx.danger) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceEmpty(label: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(label), color = LocalRemotePalette.current.muted, style = RemoteType.body)
    }
}

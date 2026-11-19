package com.cone.agent.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import kotlinx.coroutines.launch

@Composable
internal fun RemoteWorkModes(snapshot: RemoteSnapshot, viewModel: RemoteViewModel) {
    if ("workModes" !in snapshot.capabilities) return
    val cx = LocalRemotePalette.current
    var expanded by remember { mutableStateOf(false) }
    val conversationActions = "conversationActions" in snapshot.capabilities
    val modes = buildList {
        add("standard" to R.string.remote_mode_standard)
        add("goal" to R.string.remote_mode_goal)
        if (conversationActions) add("orchestrate" to R.string.remote_mode_orchestrate)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            TextButton(onClick = { expanded = true }) { Text(stringResource(modes.firstOrNull { it.first == snapshot.agentMode }?.second ?: R.string.remote_mode_standard), color = cx.accent) }
            RemoteDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = cx.bg2) {
                modes.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label), color = if (id == snapshot.agentMode) cx.accent else cx.text) }, onClick = { viewModel.setAgentMode(id); expanded = false })
                }
            }
        }
        FilterChip(selected = snapshot.planMode, onClick = { viewModel.setPlanMode(!snapshot.planMode) }, label = { Text(stringResource(R.string.remote_plan_mode)) })
        FilterChip(selected = snapshot.reviewMode, onClick = { viewModel.setReviewMode(!snapshot.reviewMode) }, label = { Text(stringResource(R.string.remote_review_mode)) })
    }
}

@Composable
internal fun RemoteQueue(snapshot: RemoteSnapshot, viewModel: RemoteViewModel) {
    if (snapshot.queuedMessages.isEmpty()) return
    val cx = LocalRemotePalette.current
    val conversationId = snapshot.activeConversationId
    val canSteer = conversationId != null && snapshot.isStreaming && "conversationActions" in snapshot.capabilities
    Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        snapshot.queuedMessages.forEachIndexed { index, message ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val content = message.content.ifBlank { stringResource(R.string.remote_queue_images, message.imageCount) }
                    Text("${index + 1}. $content", maxLines = 2, color = cx.muted, style = MaterialTheme.typography.bodySmall)
                    if (message.steer) Text(stringResource(R.string.remote_queue_steering), color = cx.accent, style = RemoteType.caption)
                    message.steerError?.let { Text(it, color = cx.danger, style = RemoteType.caption, maxLines = 3) }
                }
                if (canSteer && conversationId != null && !message.steer) TextButton(
                    onClick = { viewModel.steerQueuedMessage(conversationId, message.id) },
                ) { Text(stringResource(R.string.remote_queue_steer), color = cx.accent) }
                TextButton(onClick = { viewModel.dequeueMessage(message.id) }) { Text(stringResource(R.string.remote_queue_remove), color = cx.danger) }
            }
        }
    }
}

@Composable
internal fun RemoteRenameDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(title) { mutableStateOf(title) }
    val rename = stringResource(R.string.remote_rename)
    val back = stringResource(R.string.cd_back)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(rename) },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(200) }, singleLine = true) },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { onRename(draft.trim()); onDismiss() }) { Text(rename) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(back) } })
}

@Composable
internal fun RemoteChangeDetail(change: RemoteChange, viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = uiState.snapshot
    val conversationId = snapshot?.activeConversationId
    val conversationActions = snapshot != null && "conversationActions" in snapshot.capabilities
    var detail by remember(change.id) { mutableStateOf<Pair<String, String>?>(null) }
    var loaded by remember(change.id) { mutableStateOf(false) }
    var confirmingUndo by remember(change.id) { mutableStateOf(false) }
    var showCheckpoint by remember(change.id) { mutableStateOf(false) }
    var undoing by remember(change.id) { mutableStateOf(false) }
    var error by remember(change.id) { mutableStateOf<String?>(null) }
    val failure = stringResource(R.string.remote_checkpoint_failed)
    LaunchedEffect(change.id) { detail = viewModel.changeDetail(change.id); loaded = true }
    RemoteDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = cx.bg0, modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(change.filePath, color = cx.text, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_back), color = cx.accent) }
                }
                HorizontalDivider(color = cx.border)
                if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (detail == null) Text(stringResource(R.string.remote_detail_failed), color = cx.danger)
                else SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Column {
                        Text(stringResource(R.string.remote_before), color = cx.danger, modifier = Modifier.padding(vertical = 8.dp))
                        Text(detail!!.first, color = cx.muted, style = RemoteType.monoBody)
                        HorizontalDivider(color = cx.border)
                        Text(stringResource(R.string.remote_after), color = cx.success, modifier = Modifier.padding(vertical = 8.dp))
                        Text(detail!!.second, color = cx.text, style = RemoteType.monoBody)
                    }
                }
                if (change.status == "pending") Row {
                    TextButton(onClick = { viewModel.reject(change.id); onDismiss() }) { Text(stringResource(R.string.remote_reject), color = cx.danger) }
                    TextButton(onClick = { viewModel.approve(change.id); onDismiss() }) { Text(stringResource(R.string.remote_approve), color = cx.accent) }
                }
                if (conversationActions && conversationId != null) {
                    if (change.status == "applied" && change.kind != "exec" && change.kind != "computer") {
                        TextButton(enabled = !undoing, onClick = { confirmingUndo = true }) {
                            Text(stringResource(R.string.remote_undo_change), color = cx.danger)
                        }
                    }
                    if (change.messageId != null) TextButton(
                        enabled = snapshot?.isStreaming == false && !undoing,
                        onClick = { showCheckpoint = true },
                    ) { Text(stringResource(R.string.remote_restore_checkpoint), color = cx.danger) }
                }
                error?.let { Text(it, color = cx.danger, style = RemoteType.bodySmall) }
            }
        }
    }
    if (showCheckpoint && conversationId != null) {
        RemoteCheckpointDialog(change, conversationId, viewModel) { showCheckpoint = false }
    }
    if (confirmingUndo && conversationId != null) ConfirmDialog(
        title = stringResource(R.string.remote_undo_change),
        message = stringResource(R.string.remote_undo_change_confirm),
        confirmLabel = stringResource(R.string.remote_undo_change),
        onConfirm = {
            confirmingUndo = false
            undoing = true
            scope.launch {
                val result = viewModel.undoConversationChange(conversationId, change.id)
                undoing = false
                if (result == null) onDismiss() else error = result.ifBlank { failure }
            }
        },
        onDismiss = { confirmingUndo = false },
    )
}

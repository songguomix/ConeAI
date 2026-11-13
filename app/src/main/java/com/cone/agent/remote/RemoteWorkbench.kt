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
import com.cone.agent.R

@Composable
internal fun RemoteWorkModes(snapshot: RemoteSnapshot, viewModel: RemoteViewModel) {
    if ("workModes" !in snapshot.capabilities) return
    val cx = LocalRemotePalette.current
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf("standard" to R.string.remote_mode_standard, "goal" to R.string.remote_mode_goal)
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
    Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        snapshot.queuedMessages.forEachIndexed { index, message ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${index + 1}. ${message.content}", maxLines = 2, color = cx.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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
    var detail by remember(change.id) { mutableStateOf<Pair<String, String>?>(null) }
    var loaded by remember(change.id) { mutableStateOf(false) }
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
            }
        }
    }
}

package com.cone.agent.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import kotlinx.coroutines.launch

/** RSI has its own status, direction, run history and console. It never borrows the Work mode menu. */
@Composable
internal fun RemoteRsiScreen(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    val cx = LocalRemotePalette.current
    val state = snapshot.rsi
    val connected = viewModel.state.collectAsStateWithLifecycle().value.phase == RemotePhase.CONNECTED
    var selected by rememberSaveable(snapshot.activeConversationId) { mutableIntStateOf(0) }
    var busy by remember(snapshot.activeConversationId) { mutableStateOf(false) }
    var failed by remember(snapshot.activeConversationId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val control: (String) -> Unit = { action ->
        val id = snapshot.activeConversationId
        if (id != null && !busy) {
            busy = true; failed = false
            scope.launch { failed = !viewModel.rsiControl(id, action); busy = false }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RSI", color = cx.text, style = RemoteType.serifBody)
                Text(rsiStatusText(state?.status ?: "idle"), color = if (snapshot.isStreaming) cx.accent else cx.muted, style = RemoteType.caption)
            }
            if (snapshot.isStreaming) OutlinedButton(enabled = connected && !busy, onClick = { control("stop") }) {
                Text(stringResource(R.string.remote_stop))
            } else Button(enabled = connected && !busy && state?.canStart == true, onClick = { control("start") }) {
                Text(stringResource(R.string.remote_rsi_start))
            }
        }
        if (failed) Text(stringResource(R.string.remote_rsi_failed), color = cx.danger, style = RemoteType.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp))
        TabRow(selectedTabIndex = selected, containerColor = cx.bg0, contentColor = cx.accent) {
            listOf(R.string.remote_rsi_console, R.string.remote_rsi_direction, R.string.remote_rsi_runs).forEachIndexed { index, title ->
                Tab(selected = selected == index, onClick = { selected = index }, text = { Text(stringResource(title), style = RemoteType.bodySmall) })
            }
        }
        when (selected) {
            0 -> RemoteConversationPane(snapshot, viewModel, Modifier.weight(1f), showWorkControls = false, showComposer = false)
            1 -> RsiDirectionEditor(snapshot, viewModel, connected, Modifier.weight(1f))
            else -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state?.runs.isNullOrEmpty()) Text(stringResource(R.string.remote_rsi_no_runs), color = cx.muted, style = RemoteType.body)
                state?.runs?.asReversed()?.forEach { run ->
                    Card(colors = CardDefaults.cardColors(containerColor = cx.bg2)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(run.id, color = cx.text, style = RemoteType.mono)
                            Text("${rsiStatusText(run.status)} · ${run.iteration}", color = cx.accent, style = RemoteType.bodySmall)
                            run.result?.let { Text(it, color = cx.muted, style = RemoteType.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RsiDirectionEditor(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, connected: Boolean, modifier: Modifier) {
    val cx = LocalRemotePalette.current
    var direction by remember(snapshot.activeConversationId, snapshot.rsi?.directionConfig) {
        mutableStateOf(snapshot.rsi?.directionConfig ?: RemoteRsiDirection())
    }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val enabled = connected && !snapshot.isStreaming && !busy && "rsiControls" in snapshot.capabilities
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            R.string.remote_rsi_objective to direction.objective,
            R.string.remote_rsi_priorities to direction.priorities,
            R.string.remote_rsi_metrics to direction.metrics,
            R.string.remote_rsi_scope to direction.scope,
            R.string.remote_rsi_constraints to direction.constraints,
        ).forEachIndexed { index, (label, value) ->
            OutlinedTextField(value = value, enabled = enabled, label = { Text(stringResource(label)) },
                modifier = Modifier.fillMaxWidth(), maxLines = 6, textStyle = RemoteType.body,
                onValueChange = { next ->
                    val text = next.take(16_384)
                    direction = when (index) {
                        0 -> direction.copy(objective = text)
                        1 -> direction.copy(priorities = text)
                        2 -> direction.copy(metrics = text)
                        3 -> direction.copy(scope = text)
                        else -> direction.copy(constraints = text)
                    }
                })
        }
        Button(enabled = enabled && snapshot.activeConversationId != null, onClick = {
            val id = snapshot.activeConversationId ?: return@Button
            busy = true; failed = false
            scope.launch { failed = !viewModel.saveRsiDirection(id, direction); busy = false }
        }) { Text(stringResource(R.string.remote_save)) }
        if (failed) Text(stringResource(R.string.remote_rsi_failed), color = cx.danger, style = RemoteType.bodySmall)
    }
}

@Composable
private fun rsiStatusText(status: String): String = stringResource(when (status) {
    "running" -> R.string.remote_rsi_running
    "failed" -> R.string.remote_rsi_failed_status
    "completed" -> R.string.remote_rsi_completed
    else -> R.string.remote_rsi_ready
})

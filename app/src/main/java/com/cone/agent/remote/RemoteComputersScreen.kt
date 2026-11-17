package com.cone.agent.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cone.agent.R

@Composable
internal fun RemoteComputersScreen(
    state: RemoteUiState,
    onBack: () -> Unit,
    onSelect: (RemoteComputer) -> Unit,
    onAdd: () -> Unit,
    onRepair: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    val connecting = state.phase == RemotePhase.CONNECTING || state.phase == RemotePhase.NEEDS_PASSWORD
    var renaming by remember { mutableStateOf<RemoteComputer?>(null) }
    var removing by remember { mutableStateOf<RemoteComputer?>(null) }

    Column(Modifier.fillMaxSize().background(cx.bg0)) {
        RemoteComputerHeader(title = stringResource(R.string.remote_computers), onBack = onBack)
        Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.phase == RemotePhase.CONNECTING || state.errorMessage != null) {
                    item { RemoteConnectionFeedback(state, onCancel) }
                }
                if (state.computers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(cx.accentSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Computer, contentDescription = null, tint = cx.accent)
                            }
                            Text(stringResource(R.string.remote_computer_empty), color = cx.text, style = RemoteType.heading)
                        }
                    }
                }
                items(state.computers, key = { it.id }) { computer ->
                    val isCurrent = state.activeComputerId == computer.id
                    val badge = when {
                        isCurrent && state.phase == RemotePhase.CONNECTED -> R.string.remote_computer_connected
                        !computer.canConnect -> R.string.remote_computer_pair_required
                        computer.id == state.lastComputerId -> R.string.remote_last_connected
                        else -> null
                    }
                    RemoteComputerRow(
                        computer = computer,
                        current = isCurrent && state.phase == RemotePhase.CONNECTED,
                        badge = badge,
                        enabled = !connecting,
                        onSelect = { onSelect(computer) },
                        onRename = { renaming = computer },
                        onRepair = { onRepair(computer.id) },
                        onRemove = { removing = computer },
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = onAdd,
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.remote_computer_add))
                    }
                }
            }
        }
    }

    renaming?.let { computer ->
        RenameComputerDialog(
            initialName = computer.name,
            onSave = { name -> onRename(computer.id, name); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    removing?.let { computer ->
        ConfirmDialog(
            title = stringResource(R.string.remote_computer_forget),
            message = stringResource(R.string.remote_computer_forget_confirm, computer.name),
            confirmLabel = stringResource(R.string.remote_computer_forget),
            onConfirm = { onRemove(computer.id); removing = null },
            onDismiss = { removing = null },
        )
    }
}

@Composable
internal fun RemoteComputerHeader(title: String, onBack: () -> Unit) {
    val cx = LocalRemotePalette.current
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), tint = cx.text)
        }
        Text(title, color = cx.text, style = RemoteType.heading, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = cx.border)
}

@Composable
private fun RemoteComputerRow(
    computer: RemoteComputer,
    current: Boolean,
    badge: Int?,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRepair: () -> Unit,
    onRemove: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    val shape = RoundedCornerShape(12.dp)
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(shape).background(cx.bg2)
            .border(1.dp, if (current) cx.accent else cx.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Computer, contentDescription = null, tint = if (current) cx.accent else cx.muted)
            Column(Modifier.weight(1f)) {
                Text(computer.name, color = cx.text, style = RemoteType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (computer.endpointLabel != computer.name) {
                    Text(computer.endpointLabel, color = cx.muted, style = RemoteType.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                badge?.let {
                    Text(stringResource(it), color = if (current) cx.success else cx.muted, style = RemoteType.caption)
                }
            }
        }
        Box {
            IconButton(onClick = { showMenu = true }, enabled = enabled) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.remote_computer_options, computer.name), tint = cx.muted)
            }
            RemoteDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = cx.bg2) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_computer_rename)) }, onClick = { showMenu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_computer_repair)) }, onClick = { showMenu = false; onRepair() })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_computer_forget), color = cx.danger) }, onClick = { showMenu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun RenameComputerDialog(initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val cx = LocalRemotePalette.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    val submit = { if (name.isNotBlank()) onSave(name.trim()) }
    RemoteDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cx.bg2).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.remote_computer_rename), color = cx.text, style = RemoteType.heading)
            DarkInput(
                value = name,
                onValueChange = { name = it.take(80) },
                placeholder = stringResource(R.string.remote_computer_name_optional),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                imeAction = ImeAction.Done,
                onImeAction = submit,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = submit, enabled = name.isNotBlank()) { Text(stringResource(R.string.remote_save)) }
            }
        }
    }
}

@Composable
internal fun RemoteConnectionFeedback(state: RemoteUiState, onCancel: () -> Unit) {
    val cx = LocalRemotePalette.current
    when {
        state.phase == RemotePhase.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.remote_connecting), color = cx.muted, style = RemoteType.body, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        state.errorMessage != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.remote_connect_failed), color = cx.danger, style = RemoteType.body, fontWeight = FontWeight.Medium)
            Text(state.errorMessage, color = cx.muted, style = RemoteType.bodySmall)
        }
        else -> Unit
    }
}

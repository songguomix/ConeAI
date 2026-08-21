package com.cone.agent.ui.screens.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.cone.agent.R
import com.cone.agent.ui.SystemActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    systemActions: SystemActions,
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val env by viewModel.environment.collectAsStateWithLifecycle()
    val wakeOn by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val wakeSensitivity by viewModel.wakeWordSensitivity.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            PermissionItem(
                title = stringResource(R.string.perm_model_title),
                description = stringResource(R.string.perm_model_desc),
                granted = env.modelReady,
                actionLabel = null,
                onAction = {},
            )
            PermissionItem(
                title = stringResource(R.string.perm_a11y_title),
                description = stringResource(R.string.perm_a11y_desc),
                granted = env.accessibilityReady,
                actionLabel = stringResource(R.string.perm_go_enable),
                onAction = systemActions.openAccessibilitySettings,
            )
            PermissionItem(
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                granted = env.overlayReady,
                actionLabel = stringResource(R.string.perm_go_authorize),
                onAction = systemActions.openOverlaySettings,
            )
            PermissionItem(
                title = stringResource(R.string.perm_capture_title),
                description = stringResource(R.string.perm_capture_desc),
                granted = env.captureReady,
                actionLabel = stringResource(if (env.captureReady) R.string.perm_reauthorize else R.string.perm_start_authorize),
                onAction = systemActions.requestScreenCapture,
            )
            PermissionItem(
                title = stringResource(R.string.perm_notif_title),
                description = stringResource(R.string.perm_notif_desc),
                granted = env.notificationsReady,
                actionLabel = stringResource(R.string.perm_authorize),
                onAction = systemActions.requestNotificationPermission,
            )
            PermissionItem(
                title = stringResource(R.string.perm_mic_title),
                description = stringResource(R.string.perm_mic_desc),
                granted = env.microphoneReady,
                actionLabel = stringResource(R.string.perm_authorize),
                onAction = systemActions.requestMicrophonePermission,
            )
            PermissionItem(
                title = stringResource(R.string.perm_applist_title),
                description = stringResource(R.string.perm_applist_desc),
                granted = env.appListReady,
                actionLabel = stringResource(R.string.perm_authorize),
                onAction = systemActions.requestAppListPermission,
            )
            PermissionItem(
                title = stringResource(R.string.perm_assistant_title),
                description = stringResource(R.string.perm_assistant_desc),
                granted = env.assistantReady,
                actionLabel = stringResource(R.string.perm_go_settings),
                onAction = systemActions.openAssistantSettings,
            )
            // Wake word is the last item (item 8): it depends on the mic above and is opt-in.
            WakeWordToggleItem(
                title = stringResource(R.string.perm_wake_title),
                description = stringResource(R.string.perm_wake_desc),
                checked = wakeOn,
                onCheckedChange = { on ->
                    // Wake word needs the mic; if missing, request it (the service starts once granted).
                    if (on && !env.microphoneReady) systemActions.requestMicrophonePermission()
                    viewModel.setWakeWord(on)
                },
            )
            // Sensitivity only matters when the listener is on, so it's revealed under the toggle.
            if (wakeOn) {
                WakeSensitivityItem(
                    sensitivity = wakeSensitivity,
                    onChange = viewModel::setWakeSensitivity,
                )
            }

            if (env.captureReady) {
                OutlinedButton(
                    onClick = systemActions.stopScreenCapture,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text(stringResource(R.string.perm_stop_capture)) }
            }
        }
    }
}

/** A switch-style row (not a one-shot permission): toggles the always-on wake-word listener. */
@Composable
private fun WakeWordToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** Slider revealed under the wake toggle: tunes how easily the wake phrase triggers (低 ↔ 高). */
@Composable
private fun WakeSensitivityItem(
    sensitivity: Float,
    onChange: (Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.perm_wake_sensitivity_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.perm_wake_sensitivity_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Slider(
                value = sensitivity,
                onValueChange = onChange,
                valueRange = 0f..1f,
                // Two intermediate stops → three positions (低 / 中 / 高), matching the match tiers.
                steps = 1,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.perm_wake_sensitivity_low),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.perm_wake_sensitivity_high),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!granted && actionLabel != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

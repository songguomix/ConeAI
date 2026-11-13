package com.cone.agent.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cone.agent.BuildConfig
import com.cone.agent.R
import com.cone.agent.update.UpdateCheckResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppUpdateSettingsSection(
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onShowPrompt: () -> Unit,
    onDismissPrompt: () -> Unit,
) {
    val available = (state.result as? UpdateCheckResult.Available)?.update
    SettingsGroupLabel(R.string.settings_group_updates)
    SettingsCard {
        SettingsSectionHeader(
            title = stringResource(R.string.update_title),
            description = stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
        )
        val status = when {
            state.checking -> stringResource(R.string.update_checking)
            state.failed -> stringResource(R.string.update_check_failed)
            available != null -> stringResource(R.string.update_available, available.version)
            state.result == UpdateCheckResult.UpToDate -> stringResource(R.string.update_up_to_date)
            state.result == UpdateCheckResult.NoRelease -> stringResource(R.string.update_no_release)
            else -> stringResource(R.string.update_check_description)
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                state.failed -> MaterialTheme.colorScheme.error
                available != null -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state.checking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCheck, enabled = !state.checking) {
                Text(stringResource(R.string.update_check))
            }
            if (available != null) {
                Button(onClick = onShowPrompt, enabled = !state.checking) {
                    Text(stringResource(R.string.update_view))
                }
            }
        }
    }

    if (available != null && state.showPrompt) {
        val context = LocalContext.current
        // Dialogs create a new window; resolve strings here to retain the app's selected language.
        val title = stringResource(R.string.update_available, available.version)
        val currentVersion = stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME)
        val fileSize = stringResource(R.string.update_file_size, available.fileSize)
        val notes = available.notes.ifBlank { stringResource(R.string.update_no_notes) }
        val downloadDescription = stringResource(R.string.update_download_description)
        val downloadLabel = stringResource(R.string.update_download)
        val laterLabel = stringResource(R.string.update_later)
        AlertDialog(
            onDismissRequest = onDismissPrompt,
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(currentVersion)
                    if (available.fileSize.isNotBlank()) {
                        Text(fileSize)
                    }
                    Text(notes)
                    Text(
                        downloadDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.downloadUrl)))
                        onDismissPrompt()
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(context, R.string.update_open_failed, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text(downloadLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPrompt) {
                    Text(laterLabel)
                }
            },
        )
    }
}

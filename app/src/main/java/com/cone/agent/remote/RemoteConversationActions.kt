package com.cone.agent.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cone.agent.R
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val REMOTE_IMAGE_COUNT = 4
private const val MAX_IMAGE_BYTES = 2_999_960
private val remoteImageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

internal suspend fun readRemoteImage(context: Context, uri: Uri, remainingData: Int): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.lowercase()?.takeIf(remoteImageTypes::contains) ?: return@runCatching null
            val prefix = "data:$mime;base64,"
            val maxBytes = ((remainingData - prefix.length) / 4 * 3).coerceAtMost(MAX_IMAGE_BYTES)
            if (maxBytes <= 0) return@runCatching null
            resolver.openInputStream(uri)?.use { stream ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (data.size() + read > maxBytes) return@use null
                    data.write(buffer, 0, read)
                }
                if (data.size() == 0) return@use null
                prefix + Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
            }
        }.getOrNull()
    }

private suspend fun decodeRemoteImage(dataUrl: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (dataUrl.length > 4_000_000 || !dataUrl.startsWith("data:image/")) return@runCatching null
        val bytes = Base64.decode(dataUrl.substringAfter(";base64,", ""), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

@Composable
internal fun RemoteMessageImages(message: RemoteMessage, conversationId: String?, viewModel: RemoteViewModel) {
    if (message.imageCount <= 0 || conversationId == null) return
    var openImage by remember(message.id) { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        repeat(message.imageCount) { index ->
            TextButton(onClick = { openImage = index }) { Text(stringResource(R.string.remote_message_image, index + 1)) }
        }
    }
    openImage?.let { index ->
        var loading by remember(message.id, index) { mutableStateOf(true) }
        var bitmap by remember(message.id, index) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(conversationId, message.id, index) {
            bitmap = viewModel.conversationImage(conversationId, message.id, index)?.let { decodeRemoteImage(it) }
            loading = false
        }
        val label = stringResource(R.string.remote_message_image, index + 1)
        AlertDialog(
            onDismissRequest = { openImage = null },
            title = { Text(label) },
            text = {
                val image = bitmap
                when {
                    loading -> CircularProgressIndicator()
                    image != null -> Image(image.asImageBitmap(), label, Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    else -> Text(stringResource(R.string.remote_image_unavailable))
                }
            },
            confirmButton = { TextButton(onClick = { openImage = null }) { Text(stringResource(R.string.cd_close)) } },
        )
    }
}

@Composable
internal fun RemoteChangeMessageDialog(
    message: RemoteMessage,
    conversationId: String,
    resend: Boolean,
    viewModel: RemoteViewModel,
    onDismiss: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    var draft by remember(message.id) { mutableStateOf(message.content) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val label = stringResource(if (resend) R.string.remote_resend_message else R.string.remote_edit)
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!resend) OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, enabled = !sending,
                    label = { Text(stringResource(R.string.remote_message_hint)) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 6,
                )
                Text(stringResource(R.string.remote_replace_message_warning))
                if (failed) Text(stringResource(R.string.remote_message_failed), color = cx.danger)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(enabled = (resend || draft.isNotBlank()) && !sending, onClick = {
                sending = true
                failed = false
                scope.launch {
                    val accepted = viewModel.changeConversationMessage(conversationId, message.id, draft.takeUnless { resend })
                    sending = false
                    if (accepted) onDismiss() else failed = true
                }
            }) { Text(stringResource(R.string.remote_send)) }
        },
    )
}

@Composable
internal fun RemoteConversationStatus(snapshot: RemoteSnapshot, viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    val conversationId = snapshot.activeConversationId
    snapshot.installLabel?.let { label ->
        Text(stringResource(R.string.remote_install_hold, label), color = cx.amber, style = RemoteType.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
    }
    snapshot.error?.takeIf { it.isNotBlank() }?.let { error ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = cx.danger, style = RemoteType.bodySmall,
                modifier = Modifier.weight(1f).heightIn(max = 96.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp))
            if ("conversationActions" in snapshot.capabilities) {
                IconButton(onClick = { viewModel.clearConversationError(conversationId) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.cd_close), tint = cx.muted)
                }
            }
        }
    }
    if (snapshot.canContinue && conversationId != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { viewModel.continueConversation(conversationId) }) {
                Text(stringResource(R.string.remote_continue_output), color = cx.accent)
            }
        }
    }
    if (conversationId != null) {
        snapshot.planMessageId?.let { RemotePlanProposal(conversationId, it, snapshot, viewModel) }
        snapshot.goal?.let { RemoteGoalControls(conversationId, it, snapshot, viewModel) }
    }
}

@Composable
private fun RemotePlanProposal(conversationId: String, id: String, snapshot: RemoteSnapshot, viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    var dismissed by rememberSaveable(id) { mutableStateOf(false) }
    if (dismissed) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.remote_plan_proposal), color = cx.text, style = RemoteType.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.remote_keep_planning)) }
            TextButton(
                onClick = { viewModel.approveConversationPlan(conversationId, id) },
                enabled = !snapshot.isEditingMessage && snapshot.installLabel == null,
            ) { Text(stringResource(R.string.remote_approve_plan)) }
        }
    }
}

@Composable
private fun RemoteGoalControls(
    conversationId: String,
    goal: RemoteConversationGoal,
    snapshot: RemoteSnapshot,
    viewModel: RemoteViewModel,
) {
    val cx = LocalRemotePalette.current
    var editing by remember(conversationId) { mutableStateOf(false) }
    var clearing by remember(conversationId) { mutableStateOf(false) }
    var expanded by rememberSaveable(conversationId) { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(goal.status, goal.runningSince) {
        if (goal.status == "running") {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val elapsed = (goal.elapsedMs ?: 0L) + (goal.runningSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L)
    val status = when (goal.status) {
        "running" -> R.string.remote_goal_running
        "completed" -> R.string.remote_goal_completed
        else -> R.string.remote_goal_paused
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(stringResource(status, elapsed / 1_000), color = cx.accent, style = RemoteType.caption)
        Text(goal.text, color = cx.text, style = RemoteType.bodySmall, maxLines = if (expanded) 10 else 2)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (goal.status == "running") TextButton(onClick = { viewModel.conversationGoal(conversationId, "pause") }) {
                Text(stringResource(R.string.remote_pause_goal))
            }
            if (goal.status == "paused") TextButton(
                onClick = { viewModel.conversationGoal(conversationId, "resume") },
                enabled = !snapshot.isStreaming && !snapshot.isEditingMessage && snapshot.installLabel == null && snapshot.model != null,
            ) { Text(stringResource(R.string.remote_resume_goal)) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.remote_collapse_goal else R.string.remote_expand_goal))
            }
            if (expanded) {
                if (goal.status != "completed") TextButton(onClick = { editing = true }) { Text(stringResource(R.string.remote_edit)) }
                TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.remote_clear_goal), color = cx.danger) }
            }
        }
    }
    if (editing) {
        var draft by remember(goal.text) { mutableStateOf(goal.text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.remote_edit_goal)) },
            text = { OutlinedTextField(draft, { draft = it.take(32_000) }, Modifier.fillMaxWidth(), maxLines = 6) },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    viewModel.conversationGoal(conversationId, "edit", draft)
                    editing = false
                }) { Text(stringResource(R.string.action_save)) }
            },
        )
    }
    if (clearing) ConfirmDialog(
        title = stringResource(R.string.remote_clear_goal),
        message = stringResource(R.string.remote_clear_goal_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        onConfirm = { viewModel.conversationGoal(conversationId, "clear"); clearing = false },
        onDismiss = { clearing = false },
    )
}

@Composable
internal fun RemoteSlashSuggestions(text: String, commands: List<RemoteSlashCommand>, onSelect: (String) -> Unit) {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/") || trimmed.drop(1).any(Char::isWhitespace)) return
    val matches = commands.filter { it.name.startsWith(trimmed.drop(1), ignoreCase = true) }
    if (matches.isEmpty()) return
    val cx = LocalRemotePalette.current
    Column(Modifier.fillMaxWidth().heightIn(max = 148.dp).verticalScroll(rememberScrollState())) {
        matches.forEach { command ->
            TextButton(onClick = { onSelect("/" + command.name + " ") }) {
                Column(Modifier.fillMaxWidth()) {
                    Text("/" + command.name, color = cx.accent, style = RemoteType.bodySmall)
                    Text(command.description, color = cx.muted, style = RemoteType.caption, maxLines = 2)
                }
            }
        }
    }
}

@Composable
internal fun RemoteHandoffDialog(conversationId: String, viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    var includeTools by rememberSaveable { mutableStateOf(true) }
    var includeResults by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<RemoteHandoff?>(null) }
    val savedLabel = stringResource(R.string.remote_handoff_saved)
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val export = pending
        pending = null
        if (uri == null || export == null) {
            busy = false
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(export.markdown)
                            true
                        } ?: false
                    }.getOrDefault(false)
                }
                busy = false
                if (saved) {
                    Toast.makeText(context, savedLabel, Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    error = R.string.remote_handoff_failed
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.remote_handoff_export)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeTools, { includeTools = it }, enabled = !busy)
                    Text(stringResource(R.string.remote_handoff_tools))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeResults, { includeResults = it }, enabled = !busy)
                    Text(stringResource(R.string.remote_handoff_results))
                }
                error?.let { Text(stringResource(it), color = cx.danger) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    val result = viewModel.conversationHandoff(conversationId, includeTools, includeResults)
                    if (result?.ok == true && result.filename.isNotBlank() && result.markdown.isNotEmpty()) {
                        pending = result
                        if (runCatching { createDocument.launch(result.filename) }.isFailure) {
                            pending = null
                            busy = false
                            error = R.string.remote_handoff_failed
                        }
                    } else {
                        busy = false
                        error = if (result?.error == "too-large") R.string.remote_handoff_too_large else R.string.remote_handoff_failed
                    }
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
    )
}

@Composable
internal fun RemoteCheckpointDialog(change: RemoteChange, conversationId: String, viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    var preview by remember(change.id) { mutableStateOf<RemoteCheckpointPreview?>(null) }
    var loading by remember(change.id) { mutableStateOf(true) }
    var restoring by remember(change.id) { mutableStateOf(false) }
    var error by remember(change.id) { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.remote_checkpoint_failed)
    LaunchedEffect(conversationId, change.id) {
        preview = viewModel.checkpointPreview(conversationId, change.id)
        loading = false
    }
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text(stringResource(R.string.remote_restore_checkpoint)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val checkpoint = preview
                when {
                    loading -> CircularProgressIndicator()
                    checkpoint == null -> Text(stringResource(R.string.remote_checkpoint_unavailable))
                    else -> {
                        Text(stringResource(R.string.remote_checkpoint_summary, checkpoint.messageCount, checkpoint.fileCount))
                        checkpoint.files.forEach { Text(it, style = RemoteType.monoSmall, color = cx.muted) }
                        if (checkpoint.retainedEffects > 0) {
                            Text(stringResource(R.string.remote_checkpoint_external, checkpoint.retainedEffects), color = cx.amber)
                        }
                    }
                }
                error?.let { Text(it, color = cx.danger) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !restoring) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(enabled = preview != null && !restoring, onClick = {
                val checkpoint = preview ?: return@TextButton
                restoring = true
                error = null
                scope.launch {
                    val result = viewModel.restoreCheckpoint(conversationId, change.id, checkpoint.revision)
                    restoring = false
                    if (result == null) {
                        onDismiss()
                    } else {
                        error = result.takeUnless { it.isBlank() || it == "stale" || it == "unavailable" } ?: failed
                    }
                }
            }) { Text(stringResource(R.string.remote_restore_checkpoint), color = cx.danger) }
        },
    )
}

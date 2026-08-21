package com.cone.agent.code

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R

/** A colored monogram per template — reads faster in a list than any generic file icon. */
@Composable
internal fun TemplateBadge(template: String, size: Int = 48) {
    val (text, color) = when (ProjectTemplate.of(template)) {
        ProjectTemplate.WEB -> "</>" to Color(0xFFE8642F)
        ProjectTemplate.PYTHON -> "PY" to Color(0xFF3776AB)
        ProjectTemplate.NODE -> "JS" to Color(0xFFCBAA0B)
        ProjectTemplate.KOTLIN -> "KT" to Color(0xFF7F52FF)
        ProjectTemplate.BLANK -> "MD" to Color(0xFF5B6472)
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = (size / 3.4f).sp,
        )
    }
}

/** What you can type on the 代码 page besides a plain request. */
@Composable
fun CodeHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_cmd_help_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.code_cmd_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_help_ok)) } },
    )
}

/* ---------------------------------- history ---------------------------------- */

/** Past projects, opened from the input island's 历史项目 button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeHistorySheet(
    viewModel: CodeViewModel,
    onDismiss: () -> Unit,
    onOpen: (CodeProject) -> Unit,
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingDelete by remember { mutableStateOf<CodeProject?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.code_history),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        if (projects.isEmpty()) {
            Text(
                stringResource(R.string.code_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        onOpen = { onOpen(project) },
                        onDelete = { pendingDelete = project },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.code_delete_project)) },
            text = { Text(stringResource(R.string.code_delete_project_msg, project.name)) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; viewModel.deleteProject(project) }) {
                    Text(stringResource(R.string.code_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }
}

@Composable
private fun ProjectRow(project: CodeProject, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpen),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TemplateBadge(project.template, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${stringResource(R.string.code_file_count, project.fileCount)} · " +
                        DateUtils.getRelativeTimeSpanString(
                            project.updatedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(R.string.code_action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---------------------------------- new project ---------------------------------- */

@Composable
fun CodeNewProjectDialog(onDismiss: () -> Unit, onCreate: (String, ProjectTemplate) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(ProjectTemplate.WEB) }
    val defaultName = stringResource(R.string.code_default_name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_new_project)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.code_project_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.code_template),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ProjectTemplate.entries.forEach { option ->
                    TemplateRow(option, selected = option == template) { template = option }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { defaultName }, template) }) {
                Text(stringResource(R.string.code_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_cancel)) } },
    )
}

@Composable
private fun TemplateRow(template: ProjectTemplate, selected: Boolean, onClick: () -> Unit) {
    val (title, desc) = when (template) {
        ProjectTemplate.WEB -> stringResource(R.string.code_tpl_web) to stringResource(R.string.code_tpl_web_desc)
        ProjectTemplate.PYTHON -> stringResource(R.string.code_tpl_python) to stringResource(R.string.code_tpl_python_desc)
        ProjectTemplate.NODE -> stringResource(R.string.code_tpl_node) to stringResource(R.string.code_tpl_node_desc)
        ProjectTemplate.KOTLIN -> stringResource(R.string.code_tpl_kotlin) to stringResource(R.string.code_tpl_kotlin_desc)
        ProjectTemplate.BLANK -> stringResource(R.string.code_tpl_blank) to stringResource(R.string.code_tpl_blank_desc)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TemplateBadge(template.id, size = 34)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/* ---------------------------------- folder picker ---------------------------------- */

/**
 * Picks the folder projects are created in. A plain directory browser rather than the system picker:
 * SAF hands back a `content://` tree, and everything downstream — the agent's file tools, the
 * `file://` preview — is built on real paths.
 */
@Composable
fun CodeFolderPickerDialog(viewModel: CodeViewModel, onDismiss: () -> Unit) {
    val storage by viewModel.storageState.collectAsStateWithLifecycle()
    var dir by remember { mutableStateOf(viewModel.browseStart()) }
    var creating by remember { mutableStateOf(false) }
    // Re-listed whenever we move or create, rather than on every recomposition.
    val children = remember(dir, creating) { viewModel.subDirectories(dir) }
    val start = remember { viewModel.browseStart() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_folder)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.code_folder_current, storage.root.absolutePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    dir.absolutePath,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (dir.absolutePath != start.absolutePath && dir.parentFile != null) {
                        FolderRow(stringResource(R.string.code_folder_up), up = true) {
                            dir.parentFile?.let { dir = it }
                        }
                    }
                    if (children.isEmpty()) {
                        Text(
                            stringResource(R.string.code_folder_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    children.forEach { child -> FolderRow(child.name, up = false) { dir = child } }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.code_folder_new))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.setRoot(null); onDismiss() }) {
                        Text(stringResource(R.string.code_folder_default))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.setRoot(dir); onDismiss() }) {
                Text(stringResource(R.string.code_folder_here))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_cancel)) } },
    )

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.code_folder_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.code_folder_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(dir, name)?.let { dir = it }
                        creating = false
                    },
                ) { Text(stringResource(R.string.code_create)) }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }
}

@Composable
private fun FolderRow(name: String, up: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            null,
            tint = if (up) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ---------------------------------- 工作流记忆 ---------------------------------- */

/**
 * The user's standing coding habits — what they prefer in *every* project, as distinct from the
 * per-project conventions that live in the project's own CONE.md.
 *
 * Automatic capture has to be visible to be acceptable: the agent writes these on its own when the
 * user states a preference, so the list, the switch and the per-item delete are what make that
 * bearable rather than unsettling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeWorkflowMemorySheet(viewModel: CodeViewModel, onDismiss: () -> Unit) {
    val memories by viewModel.workflowMemories.collectAsStateWithLifecycle()
    val enabled by viewModel.workflowEnabled.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var clearing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.code_workflow_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.code_workflow_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { viewModel.setWorkflowEnabled(it) })
        }
        Spacer(Modifier.height(8.dp))

        if (memories.isEmpty()) {
            Text(
                stringResource(R.string.code_workflow_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(memories, key = { it.id }) { memory ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                memory.content,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                            )
                            IconButton(onClick = { viewModel.deleteWorkflowMemory(memory.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.code_action_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { clearing = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(stringResource(R.string.code_workflow_clear)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.code_workflow_clear)) },
            text = { Text(stringResource(R.string.code_workflow_clear_msg)) },
            confirmButton = {
                TextButton(onClick = { clearing = false; viewModel.clearWorkflowMemories() }) {
                    Text(stringResource(R.string.code_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }
}

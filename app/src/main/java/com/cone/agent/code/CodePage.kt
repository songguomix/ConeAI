package com.cone.agent.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.ui.theme.ConeShapes

/**
 * 「写代码」 as the third page of the chat pager.
 *
 * It deliberately owns no chrome of its own: the drawer, the top bar with the model switcher, the
 * mode island and the input island all belong to the screen it lives in, exactly as 智能体 and 问答
 * do. What changes between the pages is only what fills the space between them — here, either the
 * model's project suggestions or an open project's conversation.
 */
@Composable
fun CodePage(
    viewModel: CodeViewModel,
    onFillInput: (String) -> Unit,
    onPickFolder: () -> Unit,
    onOpenWorkflowMemory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by viewModel.current.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        val project = current
        if (project == null) {
            CodeHomeContent(viewModel, onPickFolder, onOpenWorkflowMemory)
        } else {
            CodeWorkspaceContent(
                project = project,
                viewModel = viewModel,
                onFillInput = onFillInput,
                onOpenWorkflowMemory = onOpenWorkflowMemory,
            )
        }
    }
}

/* ---------------------------------- home ---------------------------------- */

@Composable
private fun CodeHomeContent(
    viewModel: CodeViewModel,
    onPickFolder: () -> Unit,
    onOpenWorkflowMemory: () -> Unit,
) {
    val ideas by viewModel.ideasState.collectAsStateWithLifecycle()
    val storage by viewModel.storageState.collectAsStateWithLifecycle()
    val modelId by viewModel.modelId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Ask for ideas as soon as there is a model to ask; a model picked later re-triggers it.
    LaunchedEffect(modelId) {
        if (modelId != null) viewModel.loadIdeas()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HomeHeader() }

        if (storage.fallback) {
            item {
                AccessBanner(
                    target = viewModel.defaultRoot().absolutePath,
                    onGrant = {
                        for (intent in viewModel.accessSettingsIntents()) {
                            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) break
                        }
                    },
                )
            }
        }

        item {
            val habits by viewModel.workflowMemories.collectAsStateWithLifecycle()
            val plan by viewModel.planMode.collectAsStateWithLifecycle()
            // Scrollable: three chips do not fit a narrow phone, and truncating the folder path
            // would hide the one thing on this row the user needs to be able to read.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FolderChip(path = storage.root.absolutePath, onClick = onPickFolder)
                HabitsChip(count = habits.size, onClick = onOpenWorkflowMemory)
                PlanChip(on = plan, onToggle = { viewModel.setPlanMode(!plan) })
            }
        }

        item { IdeasHeader(loading = ideas.loading) { viewModel.loadIdeas(force = true) } }

        when {
            ideas.loading -> item { IdeasLoading() }
            modelId == null -> item { IdeasNotice(stringResource(R.string.code_ideas_no_model), null) }
            ideas.error != null -> item {
                IdeasNotice(ideas.error!!) { viewModel.loadIdeas(force = true) }
            }
            else -> items(ideas.items, key = { it.title }) { idea ->
                IdeaCard(idea = idea) { viewModel.startIdea(idea) }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Code,
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.code_home_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.code_home_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shows where projects are written, and opens the picker to change it. */
@Composable
private fun FolderChip(path: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 工作流记忆 at a glance: how many standing habits the agent is currently carrying. */
@Composable
private fun HabitsChip(count: Int, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lightbulb,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.code_workflow_chip, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 计划模式 toggle, on the page rather than buried in 设置 — it is decided per task, not once. */
@Composable
private fun PlanChip(on: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (on) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier.clip(CircleShape).clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Checklist,
                null,
                tint = if (on) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (on) R.string.code_plan_mode_on else R.string.code_plan_mode_off),
                style = MaterialTheme.typography.labelMedium,
                color = if (on) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AccessBanner(target: String, onGrant: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.SdStorage,
                null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.code_access_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.code_access_desc, target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onGrant) { Text(stringResource(R.string.code_access_grant)) }
        }
    }
}

@Composable
private fun IdeasHeader(loading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.code_ideas_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.code_ideas_refresh))
        }
    }
}

@Composable
private fun IdeasLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.code_ideas_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IdeasNotice(message: String, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.code_ideas_retry)) } }
    }
}

/** One suggestion. The whole card starts it — reading it and deciding are the same gesture. */
@Composable
private fun IdeaCard(idea: ProjectIdea, onStart: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onStart),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TemplateBadge(idea.template.id, size = 44)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    idea.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    idea.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Text(
                    stringResource(R.string.code_start),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/* ---------------------------------- open project ---------------------------------- */

@Composable
private fun CodeWorkspaceContent(
    project: CodeProject,
    viewModel: CodeViewModel,
    onFillInput: (String) -> Unit,
    onOpenWorkflowMemory: () -> Unit,
) {
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val previewable by viewModel.previewable.collectAsStateWithLifecycle()
    val awaitingPlan by viewModel.awaitingPlan.collectAsStateWithLifecycle()
    val awaitingAnswer by viewModel.awaitingAnswer.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follow the stream without chasing every delta: re-scroll on a new turn, and otherwise only
    // once the growing reply has gained a couple of lines' worth of text.
    LaunchedEffect(turns.size, (turns.lastOrNull()?.text?.length ?: 0) / 200) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ProjectBar(
            project = project,
            previewable = previewable,
            onClose = { viewModel.closeProject() },
            onFiles = { viewModel.showPane(CodePane.FILES) },
            onPreview = { viewModel.showPane(CodePane.PREVIEW) },
            onRename = { viewModel.renameProject(it) },
            onClear = { viewModel.clearTranscript() },
            onOpenNotes = { viewModel.openInstructions() },
            onOpenWorkflowMemory = onOpenWorkflowMemory,
            planMode = viewModel.planMode.collectAsStateWithLifecycle().value,
            onTogglePlanMode = { viewModel.setPlanMode(!it) },
            onDelete = { viewModel.deleteProject(project) },
        )
        if (turns.isEmpty()) {
            WorkspaceEmptyState(modifier = Modifier.fillMaxSize(), onSuggestion = onFillInput)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(turns) { _, turn ->
                    TurnView(
                        turn = turn,
                        onOpenFile = { viewModel.openFile(it) },
                        canRestore = turn.checkpoint != null,
                        onRestore = { turn.checkpoint?.let { viewModel.restoreCheckpoint(it) } },
                        canPreview = previewable,
                        onPreview = { viewModel.showPane(CodePane.PREVIEW) },
                    )
                }
                if (running) item { WorkingIndicator() }
                if (awaitingPlan) item { PlanApprovalBar(viewModel) }
                if (awaitingAnswer) item { AwaitingAnswerHint() }
            }
        }
    }
}

/** 开始执行 / 取消 for a plan the agent is holding. Nothing has been written yet at this point. */
@Composable
private fun PlanApprovalBar(viewModel: CodeViewModel) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.code_plan_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.cancelPlan() }) {
                Text(stringResource(R.string.code_cancel))
            }
            Button(onClick = { viewModel.approvePlan() }) {
                Text(stringResource(R.string.code_plan_approve))
            }
        }
    }
}

@Composable
private fun AwaitingAnswerHint() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.code_ask_waiting),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The open project's own header, inside the page rather than in the app bar — the app bar belongs to
 * the screen and already carries the drawer, the model and the mode island.
 */
@Composable
private fun ProjectBar(
    project: CodeProject,
    previewable: Boolean,
    onClose: () -> Unit,
    onFiles: () -> Unit,
    onPreview: () -> Unit,
    onRename: (String) -> Unit,
    onClear: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenWorkflowMemory: () -> Unit,
    planMode: Boolean,
    onTogglePlanMode: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TemplateBadge(project.template, size = 30)
            Spacer(Modifier.width(10.dp))
            Text(
                project.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (previewable) {
                IconButton(onClick = onPreview) {
                    Icon(
                        Icons.Outlined.PlayCircleOutline,
                        stringResource(R.string.code_preview),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            IconButton(onClick = onFiles) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    stringResource(R.string.code_files),
                    modifier = Modifier.size(21.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        stringResource(R.string.cd_more),
                        modifier = Modifier.size(21.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.clip(ConeShapes.Menu),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_close_project)) },
                        onClick = { menuOpen = false; onClose() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_rename_project)) },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_plan_mode)) },
                        trailingIcon = {
                            if (planMode) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { menuOpen = false; onTogglePlanMode(planMode) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_notes)) },
                        onClick = { menuOpen = false; onOpenNotes() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_workflow_title)) },
                        onClick = { menuOpen = false; onOpenWorkflowMemory() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_clear_chat)) },
                        onClick = { menuOpen = false; onClear() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.code_delete_project)) },
                        onClick = { menuOpen = false; deleting = true },
                    )
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.code_rename_project)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.code_project_name)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; onRename(name) }) {
                    Text(stringResource(R.string.code_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.code_delete_project)) },
            text = { Text(stringResource(R.string.code_delete_project_msg, project.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; onDelete() }) {
                    Text(stringResource(R.string.code_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }
}

/* ---------------------------------- overlays ---------------------------------- */

/**
 * The editor, the preview and the file sheet. Hosted by the screen rather than by the page so they
 * cover the whole display — inside the pager they would be boxed into the page's bounds.
 */
@Composable
fun CodeOverlays(viewModel: CodeViewModel) {
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()

    if (pane == CodePane.FILES) {
        FileSheet(viewModel = viewModel, onDismiss = { viewModel.showPane(CodePane.NONE) })
    }

    AnimatedVisibility(
        visible = pane == CodePane.EDITOR && open != null,
        enter = slideInVertically { it / 6 } + fadeIn(),
        exit = slideOutVertically { it / 6 } + fadeOut(),
    ) {
        open?.let { file ->
            EditorPane(
                file = file,
                onChange = { viewModel.editBuffer(it) },
                onSave = { viewModel.saveFile() },
                onClose = { viewModel.closeFile() },
            )
        }
    }

    AnimatedVisibility(visible = pane == CodePane.PREVIEW, enter = fadeIn(), exit = fadeOut()) {
        val previewFile by viewModel.previewFile.collectAsStateWithLifecycle()
        val version by viewModel.previewVersion.collectAsStateWithLifecycle()
        previewFile?.let { file ->
            PreviewPane(file = file, version = version, onClose = { viewModel.closePane() })
        }
    }
}

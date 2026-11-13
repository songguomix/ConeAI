package com.cone.agent.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
            CodeHomeContent(viewModel, onPickFolder)
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
) {
    val storage by viewModel.storageState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 和「智能体 / 问答」空态完全同一套版式：同一张应用图标、同样 34sp 标题、同样 24dp 边距，
    // 且整块垂直居中，三个模式切来切去位置不会跳。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.code_home_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.code_home_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 权重槽给 chip 占位：路径长时 chip 自己省略，右侧按钮仍贴右边、保持单行。
            Box(modifier = Modifier.weight(1f)) {
                FolderChip(
                    path = storage.root.absolutePath,
                    onClick = onPickFolder,
                )
            }
            if (storage.fallback) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        for (intent in viewModel.accessSettingsIntents()) {
                            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) break
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        stringResource(R.string.code_storage_grant_short),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Shows where projects are written, and opens the picker to change it. */
@Composable
private fun FolderChip(path: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                // 手机上一行放不下绝对路径，只显示最后两级，完整路径留给无障碍与目录选择器。
                shortPath(path),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(Icons.Outlined.Folder, contentDescription = path, modifier = Modifier.size(16.dp))
        },
    )
}

/** 路径太长时折成「…/最后两级」，手机上更好读。 */
private fun shortPath(path: String): String {
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> path
        parts.size <= 2 -> "/" + parts.joinToString("/")
        else -> "…/" + parts.takeLast(2).joinToString("/")
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

    // 手机版的「工作台」：对话 / 改动 是内嵌页，文件 / 预览 仍是全屏浮层。
    var tab by rememberSaveable(project.id) { mutableStateOf(0) }
    val changes = remember(turns) { collectChanges(turns) }

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
        CodeWorkbenchTabs(
            selected = tab,
            changeCount = changes.size,
            onSelect = { index ->
                when (index) {
                    0, 1 -> tab = index
                    2 -> viewModel.showPane(CodePane.FILES)
                    3 -> viewModel.showPane(CodePane.PREVIEW)
                }
            },
        )
        if (tab == 1) {
            ChangesPane(
                changes = changes,
                onOpenFile = { viewModel.openFile(it) },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (turns.isEmpty()) {
            WorkspaceEmptyState(modifier = Modifier.fillMaxSize(), onSuggestion = onFillInput)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(turns) { index, turn ->
                    TurnView(
                        turn = turn,
                        onOpenFile = { viewModel.openFile(it) },
                        canRestore = turn.checkpoint != null,
                        onRestore = { turn.checkpoint?.let { viewModel.restoreCheckpoint(it) } },
                        canPreview = previewable,
                        onPreview = { viewModel.showPane(CodePane.PREVIEW) },
                        isLast = index == turns.lastIndex && !running,
                    )
                }
                if (running) item { WorkingIndicator() }
                if (awaitingPlan) item { PlanApprovalBar(viewModel) }
                if (awaitingAnswer) item { AwaitingAnswerHint() }
            }
        }
    }
}

/**
 * 工作台的四个入口，对应桌面端 ConeCode 的 tab 栏：对话、改动是页内切换，
 * 文件与预览打开原有的全屏面板（手机上全屏比并排更实用）。
 */
@Composable
private fun CodeWorkbenchTabs(selected: Int, changeCount: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.code_tab_chat),
        if (changeCount > 0) {
            stringResource(R.string.code_tab_changes_count, changeCount)
        } else {
            stringResource(R.string.code_tab_changes)
        },
        stringResource(R.string.code_files),
        stringResource(R.string.code_preview),
    )
    val icons = listOf(
        Icons.AutoMirrored.Outlined.Chat,
        Icons.Outlined.Difference,
        Icons.Outlined.Folder,
        Icons.Outlined.PlayCircleOutline,
    )
    TabRow(
        selectedTabIndex = selected,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {},
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                icon = { Icon(icons[index], contentDescription = null, modifier = Modifier.size(17.dp)) },
            )
        }
    }
}

/** 本次会话里被改动过的文件（每个文件只留最后一次写入），与桌面端的「改动」面板一致。 */
private fun collectChanges(turns: List<CodeTurn>): List<ToolOutcome> {
    val byTarget = LinkedHashMap<String, ToolOutcome>()
    turns.forEach { turn ->
        turn.results.forEach { outcome ->
            val writes = outcome.tool == "write" || outcome.tool == "edit"
            if ((writes || outcome.diff.isNotEmpty()) && outcome.target.isNotBlank()) {
                byTarget[outcome.target] = outcome
            }
        }
    }
    return byTarget.values.toList().asReversed()
}

@Composable
private fun ChangesPane(
    changes: List<ToolOutcome>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    if (changes.isEmpty()) {
        Column(
            modifier = modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Difference,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.code_changes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val added = changes.sumOf { it.added }
    val removed = changes.sumOf { it.removed }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.code_changes_summary, changes.size, added, removed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        items(changes, key = { it.target }) { outcome ->
            val open = expanded == outcome.target
            Card(
                onClick = { expanded = if (open) null else outcome.target },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Outlined.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            outcome.target.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "+${outcome.added} −${outcome.removed}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { onOpenFile(outcome.target) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = stringResource(R.string.code_changes_open),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    Text(
                        outcome.target,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                    if (open && outcome.diff.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        DiffBody(outcome.diff)
                    }
                }
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

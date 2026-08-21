package com.cone.agent.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cone.agent.R
import kotlinx.coroutines.launch

// File browser, file viewer/editor and terminal for the desktop-remote mirror. All run over the
// bridge's request/reply channel ([RemoteViewModel.listDir]/readFile/writeFile/runCommand/openFolder).
// They share the Codex palette via [LocalRemotePalette] and the [DarkInput] field from RemoteScreen.

// ---------------------------------------------------------------------------
// File browser
// ---------------------------------------------------------------------------

@Composable
internal fun FileBrowser(
    viewModel: RemoteViewModel,
    rootPath: String?,
    modifier: Modifier = Modifier,
    onPick: ((String) -> Unit)? = null,
) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    var cwd by remember(rootPath) { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var openPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cwd) {
        val dir = cwd ?: return@LaunchedEffect
        loading = true
        entries = viewModel.listDir(dir)
        loading = false
    }

    val viewing = openPath
    if (viewing != null) {
        FileViewer(viewModel, viewing, onBack = { openPath = null }, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize().background(cx.bg0)) {
        if (cwd == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.remote_no_folder), color = cx.muted, fontSize = 14.sp)
                Spacer(Modifier.size(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(cx.accent)
                        .clickable { scope.launch { viewModel.openFolder(null)?.let { cwd = it } } }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    Text(stringResource(R.string.remote_open_folder), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            return
        }

        // Path bar: up one level + current dir + "set as project root".
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { cwd = parentDir(cwd!!) }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.cd_back), tint = cx.text, modifier = Modifier.size(18.dp))
            }
            Text(
                cwd!!,
                color = cx.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.remote_set_root),
                color = cx.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { scope.launch { viewModel.openFolder(cwd) } }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        HorizontalDivider(color = cx.border)

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(22.dp))
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.remote_empty_dir), color = cx.muted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) cwd = entry.path
                                else if (onPick != null) onPick(entry.path) // pick mode: insert, don't open
                                else openPath = entry.path
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = if (entry.isDirectory) cx.accent else cx.muted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            entry.name,
                            color = cx.text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// File viewer / editor
// ---------------------------------------------------------------------------

@Composable
private fun FileViewer(viewModel: RemoteViewModel, path: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    var content by remember(path) { mutableStateOf<String?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    var editing by remember(path) { mutableStateOf(false) }
    var draft by remember(path) { mutableStateOf("") }
    var saving by remember(path) { mutableStateOf(false) }
    var statusRes by remember(path) { mutableStateOf<Int?>(null) }

    LaunchedEffect(path) {
        loading = true
        val text = viewModel.readFile(path)
        content = text
        draft = text ?: ""
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().background(cx.bg0)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = cx.text)
            }
            Text(
                baseName(path),
                color = cx.text,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (content != null) {
                if (editing) {
                    HeaderAction(Icons.Filled.Save, stringResource(R.string.remote_save), enabled = !saving) {
                        scope.launch {
                            saving = true
                            val ok = viewModel.writeFile(path, draft)
                            saving = false
                            statusRes = if (ok) R.string.remote_saved else R.string.remote_save_failed
                            if (ok) { content = draft; editing = false }
                        }
                    }
                } else {
                    HeaderAction(Icons.Filled.Edit, stringResource(R.string.remote_edit)) { editing = true; statusRes = null }
                }
            }
        }
        statusRes?.let {
            Text(stringResource(it), color = cx.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        HorizontalDivider(color = cx.border)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(22.dp))
            }
            content == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.remote_cant_open), color = cx.muted, fontSize = 13.sp)
            }
            editing -> BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = cx.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(cx.accent),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
            else -> SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(content!!, color = cx.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, softWrap = false)
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cx = LocalRemotePalette.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) cx.accent else cx.faint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(5.dp))
        Text(label, color = if (enabled) cx.accent else cx.faint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Terminal (one-shot command → output)
// ---------------------------------------------------------------------------

private enum class TermKind { CMD, OUT, ERR }
private data class TermLine(val text: String, val kind: TermKind)

@Composable
internal fun TerminalView(viewModel: RemoteViewModel, rootPath: String?, modifier: Modifier = Modifier) {
    val cx = LocalRemotePalette.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<TermLine>() }
    val listState = rememberLazyListState()

    val run: (String) -> Unit = { raw ->
        val cmd = raw.trim()
        if (cmd.isNotEmpty() && !running) {
            log.add(TermLine("$ $cmd", TermKind.CMD))
            running = true
            scope.launch {
                val r = viewModel.runCommand(cmd, rootPath)
                if (r.stdout.isNotBlank()) log.add(TermLine(r.stdout.trimEnd('\n'), TermKind.OUT))
                if (r.stderr.isNotBlank()) log.add(TermLine(r.stderr.trimEnd('\n'), TermKind.ERR))
                if (r.stdout.isBlank() && r.stderr.isBlank()) log.add(TermLine("(exit ${r.exitCode})", TermKind.OUT))
                running = false
            }
        }
    }

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().background(cx.bg0)) {
        Text(
            rootPath ?: "—",
            color = cx.faint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        HorizontalDivider(color = cx.border)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(cx.bg1),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(log) { line ->
                Text(
                    line.text,
                    color = when (line.kind) {
                        TermKind.CMD -> cx.accent
                        TermKind.ERR -> cx.danger
                        TermKind.OUT -> cx.text
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        HorizontalDivider(color = cx.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cx.bg0)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DarkInput(
                value = input,
                onValueChange = { input = it },
                placeholder = stringResource(R.string.remote_cmd_hint),
                modifier = Modifier.weight(1f),
                singleLine = true,
                imeAction = ImeAction.Go,
                onImeAction = { run(input); input = "" },
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (running || input.isBlank()) cx.bg3 else cx.accent)
                    .clickable(enabled = !running && input.isNotBlank()) { run(input); input = "" },
                contentAlignment = Alignment.Center,
            ) {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cx.muted, modifier = Modifier.size(18.dp))
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.remote_run),
                        tint = if (input.isBlank()) cx.faint else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

/** Parent directory of an absolute path; "/" when already at (or near) the root. */
private fun parentDir(path: String): String {
    val trimmed = path.trimEnd('/')
    val cut = trimmed.substringBeforeLast('/', "")
    return if (cut.isEmpty()) "/" else cut
}

private fun baseName(path: String): String {
    val i = path.trimEnd('/').lastIndexOf('/')
    return if (i >= 0) path.substring(i + 1) else path
}

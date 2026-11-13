package com.cone.agent.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    Column(modifier = modifier.fillMaxSize().background(cx.bg0).navigationBarsPadding()) {
        if (cwd == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.remote_no_folder), color = cx.muted, style = RemoteType.bodyLarge)
                Spacer(Modifier.size(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(cx.accent)
                        .clickable { scope.launch { viewModel.openFolder(null)?.let { cwd = it } } }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    Text(stringResource(R.string.remote_open_folder), color = Color.White, style = RemoteType.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            return
        }

        // Path bar: up one level + current dir + "set as project root".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cx.bg1)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { cwd = parentDir(cwd!!) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.cd_back), tint = cx.muted, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.size(6.dp))
            Text(
                cwd!!,
                color = cx.muted,
                style = RemoteType.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.remote_set_root),
                color = cx.accent,
                style = RemoteType.bodySmall,
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
                Text(stringResource(R.string.remote_empty_dir), color = cx.muted, style = RemoteType.body)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (entry.isDirectory) cwd = entry.path
                                else if (onPick != null) onPick(entry.path) // pick mode: insert, don't open
                                else openPath = entry.path
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = if (entry.isDirectory) cx.accent else cx.faint,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(9.dp))
                        Text(
                            entry.name,
                            color = if (entry.isDirectory) cx.text else cx.muted,
                            style = RemoteType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (entry.isDirectory) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = cx.faint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
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
internal fun FileViewer(viewModel: RemoteViewModel, path: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
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
                style = RemoteType.bodyLarge,
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
            Text(stringResource(it), color = cx.accent, style = RemoteType.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        HorizontalDivider(color = cx.border)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(22.dp))
            }
            content == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.remote_cant_open), color = cx.muted, style = RemoteType.body)
            }
            editing -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val editorMinHeight = maxHeight
                // Keep scrolling on the parent: putting it on BasicTextField masks the editor's
                // accessibility role as ScrollView and makes it hard to focus with assistive tools.
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = RemoteType.monoBody.copy(color = cx.text),
                        cursorBrush = SolidColor(cx.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = editorMinHeight).padding(12.dp),
                    )
                }
            }
            else -> SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(content!!, color = cx.text, style = RemoteType.monoBody, softWrap = false)
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
        Text(label, color = if (enabled) cx.accent else cx.faint, style = RemoteType.body, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Terminal (one-shot command with a locally tracked working directory)
// ---------------------------------------------------------------------------

internal enum class TermKind { CMD, OUT, ERR, NOTE }
internal data class TermLine(val text: String, val kind: TermKind)

/** Keep the scrollback bounded so long sessions cannot grow the heap. */
private const val TERM_MAX_LINES = 600

/**
 * Terminal scrollback for one mirror session.
 *
 * It lives above the tab switch and the exec runs on the ViewModel scope, so a command keeps
 * running (and keeps its output) while the user is over in chat or files.
 */
internal class TerminalSession {
    val lines = mutableStateListOf<TermLine>()
    val history = mutableStateListOf<String>()
    // Observability matters here: these are read by the terminal UI after tab switches.
    var cwd: String? by mutableStateOf(null)
    var input: String by mutableStateOf("")
    var running: Boolean by mutableStateOf(false)
    var startedAt: Long by mutableStateOf(0L)
    var job: Job? = null

    fun append(text: String, kind: TermKind) {
        lines.add(TermLine(text, kind))
        repeat(maxOf(0, lines.size - TERM_MAX_LINES)) { lines.removeAt(0) }
    }
}

@Composable
internal fun rememberTerminalSession(): TerminalSession = remember { TerminalSession() }

@Composable
internal fun TerminalView(
    viewModel: RemoteViewModel,
    rootPath: String?,
    session: TerminalSession,
    modifier: Modifier = Modifier,
) {
    val cx = LocalRemotePalette.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var elapsed by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    var stickToBottom by remember { mutableStateOf(true) }
    var histPos by remember { mutableStateOf(session.history.size) }

    // The session follows the desktop's project root; a manual `cd` then overrides it
    // until the root itself changes.
    LaunchedEffect(rootPath) { session.cwd = rootPath }

    val msgTimeout = stringResource(R.string.remote_term_timeout)
    val msgDisconnected = stringResource(R.string.remote_term_disconnected)
    val msgCancelled = stringResource(R.string.remote_term_cancel)

    val run: (String) -> Unit = run@{ raw ->
        val cmd = raw.trim()
        if (cmd.isEmpty() || session.running) return@run
        session.append("❯ $cmd", TermKind.CMD)
        if (session.history.isEmpty() || session.history.last() != cmd) {
            session.history.add(cmd)
            if (session.history.size > 50) session.history.removeAt(0)
        }
        histPos = session.history.size
        if (ui.phase != RemotePhase.CONNECTED) {
            session.append(msgDisconnected, TermKind.ERR)
            return@run
        }
        // Each exec starts a fresh shell on the desktop, so a standalone `cd` is resolved here.
        val cdTarget = resolveCd(cmd, session.cwd, rootPath)
        if (cdTarget != null) {
            session.cwd = cdTarget
            session.append("cwd -> " + cdTarget, TermKind.NOTE)
            return@run
        }
        session.running = true
        session.startedAt = System.currentTimeMillis()
        session.job = viewModel.launchSession {
            val r = viewModel.runCommand(cmd, session.cwd)
            when {
                r.disconnected -> session.append(msgDisconnected, TermKind.ERR)
                r.timedOut -> session.append(msgTimeout, TermKind.ERR)
                else -> {
                    if (r.stdout.isNotBlank()) session.append(r.stdout.trimEnd('\n'), TermKind.OUT)
                    if (r.stderr.isNotBlank()) session.append(r.stderr.trimEnd('\n'), TermKind.ERR)
                    if (r.exitCode != 0) {
                        session.append("(exit ${r.exitCode})", TermKind.ERR)
                    } else if (r.stdout.isBlank() && r.stderr.isBlank()) {
                        session.append("(exit 0)", TermKind.NOTE)
                    }
                }
            }
            session.running = false
        }
    }

    LaunchedEffect(session.running) {
        if (!session.running) {
            elapsed = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(500)
            elapsed = ((System.currentTimeMillis() - session.startedAt) / 1000).toInt()
        }
    }

    // Only follow new output while the user is already at the bottom.
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { stickToBottom = !it }
    }
    LaunchedEffect(session.lines.size) {
        if (session.lines.isNotEmpty() && stickToBottom) listState.animateScrollToItem(session.lines.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().background(cx.bg0)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = cx.faint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                session.cwd ?: "—",
                color = cx.faint,
                style = RemoteType.caption,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.running) {
                CircularProgressIndicator(strokeWidth = 1.5.dp, color = cx.accent, modifier = Modifier.size(11.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.remote_term_running, elapsed),
                    color = cx.accent,
                    style = RemoteType.tiny,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        HorizontalDivider(color = cx.border)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(cx.bg1),
            contentPadding = PaddingValues(12.dp),
        ) {
            if (session.lines.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.remote_term_empty),
                        color = cx.faint,
                        style = RemoteType.caption,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    )
                }
            }
            items(session.lines) { line ->
                Text(
                    line.text,
                    color = when (line.kind) {
                        TermKind.CMD -> cx.accent
                        TermKind.ERR -> cx.danger
                        TermKind.OUT -> cx.text
                        TermKind.NOTE -> cx.faint
                    },
                    fontFamily = FontFamily.Monospace,
                    style = RemoteType.bodySmall,
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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // History recall: tap to walk back through previous commands.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = session.history.isNotEmpty() && !session.running) {
                        histPos = (histPos - 1).coerceAtLeast(0)
                        if (session.history.isNotEmpty()) {
                            session.input = session.history[histPos.coerceIn(0, session.history.lastIndex)]
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.remote_term_history_prev),
                    tint = if (session.history.isEmpty()) cx.faint else cx.muted,
                    modifier = Modifier.size(15.dp),
                )
            }
            DarkInput(
                value = session.input,
                onValueChange = { session.input = it },
                placeholder = stringResource(R.string.remote_cmd_hint),
                modifier = Modifier.weight(1f),
                singleLine = true,
                imeAction = ImeAction.Go,
                // Keep the text when a command is already running instead of dropping it.
                onImeAction = {
                    if (!session.running) {
                        run(session.input)
                        session.input = ""
                    }
                },
            )
            Spacer(Modifier.size(6.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (session.running || session.input.isBlank()) cx.bg3 else cx.accent)
                    .clickable {
                        when {
                            // Stop waiting; the command itself keeps running on the desktop.
                            session.running -> {
                                session.job?.cancel()
                                session.running = false
                                session.append(msgCancelled, TermKind.NOTE)
                            }
                            session.input.isNotBlank() -> {
                                run(session.input)
                                session.input = ""
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (session.running) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.remote_term_cancel),
                        tint = cx.muted,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.remote_run),
                        tint = if (session.input.isBlank()) cx.faint else Color.White,
                        modifier = Modifier.size(19.dp),
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

/** Characters that mean "this is shell syntax, not a directory name". */
private val SHELL_META = charArrayOf('&', '|', ';', '<', '>', '$', '`')

/**
 * A one-shot exec cannot change the directory of the next call: the desktop bridge runs each
 * command in a fresh shell and echoes back the cwd it was given. So a standalone `cd` is
 * resolved here and only the resulting path is kept; anything else (`cd x && cmd`, `cdk …`)
 * still goes to the desktop untouched.
 *
 * @return the new cwd, or null when [raw] is not a standalone `cd`.
 */
internal fun resolveCd(raw: String, current: String?, rootPath: String?): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("cd")) return null
    val rest = trimmed.removePrefix("cd")
    if (!rest.isEmpty() && !rest.first().isWhitespace()) return null
    val base = current ?: rootPath ?: return null
    val rawArg = rest.trim()
    val quoted = rawArg.length >= 2 && rawArg.first() == rawArg.last() &&
        (rawArg.first() == '"' || rawArg.first() == '\'')
    val arg = if (quoted) rawArg.substring(1, rawArg.length - 1) else rawArg
    if (arg.any { it in SHELL_META }) return null
    if (!quoted && arg.any { it.isWhitespace() }) return null
    if (arg.isEmpty() || arg == "~") return rootPath ?: base
    if (arg == "-") return parentDir(base)
    val path = if (arg.startsWith("/")) arg else base + "/" + arg
    val parts = mutableListOf<String>()
    path.split('/').forEach { seg ->
        when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(seg)
        }
    }
    return "/" + parts.joinToString("/")
}

private fun baseName(path: String): String {
    val i = path.trimEnd('/').lastIndexOf('/')
    return if (i >= 0) path.substring(i + 1) else path
}

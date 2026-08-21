package com.cone.agent.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cone.agent.R
import com.cone.agent.ui.components.MarkdownText

/** Change colors, fixed rather than themed: green-adds / red-removes is the one diff convention
 *  everyone already reads, and a dynamic-color scheme would happily hand us a green "delete". */
private val AddTint = Color(0xFF3FB950)
private val DelTint = Color(0xFFF85149)

/**
 * One conversation turn.
 *
 * Assistant turns are re-derived from the model's raw text on every render — [CodeProtocol.parse]
 * splits prose from calls, and the calls are zipped in order with the outcomes recorded for the
 * turn. That way a transcript restored from disk renders identically to the one that streamed live,
 * with nothing about the presentation persisted.
 */
@Composable
internal fun TurnView(
    turn: CodeTurn,
    onOpenFile: (String) -> Unit,
    canRestore: Boolean = false,
    onRestore: () -> Unit = {},
    canPreview: Boolean = false,
    onPreview: () -> Unit = {},
) {
    when (turn.role) {
        CodeRole.USER -> UserBubble(turn.text, canRestore && turn.checkpoint != null, onRestore)
        CodeRole.ERROR -> ErrorCard(turn.text)
        CodeRole.NOTE -> NoteCard(turn.text)
        CodeRole.ASSISTANT -> AssistantTurn(turn, onOpenFile, canPreview, onPreview)
    }
}

@Composable
private fun UserBubble(text: String, restorable: Boolean, onRestore: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
        // A snapshot was taken before this request, so the files can be put back exactly as they were.
        if (restorable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(CircleShape)
                    .clickable { confirming = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.code_restore),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.code_restore_title)) },
            text = { Text(stringResource(R.string.code_restore_msg)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRestore() }) {
                    Text(stringResource(R.string.code_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }
}

/** Output from the app itself — a command's reply, a restore confirmation. Never styled as a fault. */
@Composable
private fun NoteCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.ErrorOutline,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun AssistantTurn(
    turn: CodeTurn,
    onOpenFile: (String) -> Unit,
    canPreview: Boolean,
    onPreview: () -> Unit,
) {
    // `done` never executes as a tool, so it takes no slot when pairing calls with outcomes.
    val paired = remember(turn.text, turn.results.size, turn.streaming) {
        val segments = CodeProtocol.parse(turn.text, streaming = turn.streaming)
        var index = 0
        segments.map { segment ->
            when (segment) {
                is CodeSegment.Prose -> segment to null
                is CodeSegment.Call ->
                    if (segment.call.tool == "done") segment to null
                    else segment to turn.results.getOrNull(index++)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        paired.forEach { (segment, outcome) ->
            when (segment) {
                is CodeSegment.Prose -> MarkdownText(
                    markdown = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )

                is CodeSegment.Call -> when (segment.call.tool) {
                    "done" -> DoneCard(segment.call.content, canPreview, onPreview)
                    // The plan is the point of a todo block, so it renders as the list itself
                    // rather than as a tool row you have to open.
                    "todo" -> TodoCard(segment.call.content)
                    else -> ToolCard(segment.call, outcome, onOpenFile)
                }
            }
        }
        turn.tokens?.let {
            Text(
                stringResource(R.string.code_tokens, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DoneCard(summary: String, canPreview: Boolean, onPreview: () -> Unit) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(AddTint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = AddTint, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                summary.ifBlank { stringResource(R.string.code_done) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // The moment a web project finishes is exactly when you want to look at it, so the run ends
        // with the preview one tap away instead of sending you back up to the project bar to find it.
        if (canPreview) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onPreview,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                modifier = Modifier.padding(start = 29.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.code_preview_now))
            }
        }
    }
}

/**
 * The agent's running task list. `[x]` done, `[>]` in progress, `[ ]` still to do — the same shape
 * Claude Code's todo list has, kept as one block the model rewrites in full each time so the list
 * can never drift out of order.
 */
@Composable
private fun TodoCard(body: String) {
    val items = remember(body) {
        body.lines().mapNotNull { raw ->
            val line = raw.trim().removePrefix("-").removePrefix("*").trim()
            val match = Regex("^\\[([ xX>~])]\\s*(.+)$").find(line) ?: return@mapNotNull null
            match.groupValues[1].lowercase() to match.groupValues[2].trim()
        }
    }
    if (items.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp)) {
            Text(
                stringResource(R.string.code_tool_todo),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(6.dp))
            items.forEach { (mark, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp),
                ) {
                    when (mark) {
                        "x" -> Icon(
                            Icons.Filled.CheckCircle,
                            null,
                            tint = AddTint,
                            modifier = Modifier.size(16.dp),
                        )
                        ">", "~" -> Icon(
                            Icons.Filled.PlayArrow,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        else -> Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mark == "x") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (mark == "x") TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

/**
 * A single tool invocation: verb, target, outcome — and, once tapped, whatever it actually produced
 * (file text, search hits, or the diff that was written).
 */
@Composable
private fun ToolCard(call: ToolCall, outcome: ToolOutcome?, onOpenFile: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val running = outcome == null
    val failed = outcome?.ok == false
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val hasBody = outcome != null && (outcome.detail.isNotBlank() || outcome.diff.isNotEmpty())
    val target = outcome?.target?.ifBlank { call.path } ?: call.path.ifBlank { call.query }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasBody) { expanded = !expanded }
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(7.dp).clip(CircleShape).background(accent),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    verbLabel(call.tool),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    target,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    outcome == null -> Unit
                    outcome.diff.isNotEmpty() || outcome.added + outcome.removed > 0 -> {
                        Text(
                            "+${outcome.added}",
                            style = MaterialTheme.typography.labelMedium,
                            color = AddTint,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "−${outcome.removed}",
                            style = MaterialTheme.typography.labelMedium,
                            color = DelTint,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    outcome.summary.isNotBlank() -> Text(
                        outcome.summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
                if (call.path.isNotBlank() && outcome?.ok == true && call.tool != "delete") {
                    IconButton(onClick = { onOpenFile(call.path) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            stringResource(R.string.code_open_editor),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded && outcome != null) {
                outcome?.let {
                    if (it.diff.isNotEmpty()) DiffBody(it.diff) else DetailBody(it.detail, it.target)
                }
            }
        }
    }
}

@Composable
private fun DiffBody(lines: List<DiffLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        val scroll = rememberScrollState()
        lines.forEach { line ->
            val bg = when (line.kind) {
                DiffLine.ADDED -> AddTint.copy(alpha = 0.13f)
                DiffLine.REMOVED -> DelTint.copy(alpha = 0.13f)
                else -> Color.Transparent
            }
            val sign = when (line.kind) {
                DiffLine.ADDED -> "+"
                DiffLine.REMOVED -> "−"
                DiffLine.GAP -> " "
                else -> " "
            }
            if (line.kind == DiffLine.GAP) {
                Text(
                    line.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .horizontalScroll(scroll)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                ) {
                    Text(
                        sign,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = when (line.kind) {
                            DiffLine.ADDED -> AddTint
                            DiffLine.REMOVED -> DelTint
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.text.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBody(detail: String, path: String) {
    if (detail.isBlank()) return
    val palette = rememberCodePalette()
    val lang = remember(path) { CodeLang.of(path) }
    val highlighted = remember(detail, lang, palette) {
        CodeHighlighter.highlight(detail, lang, palette)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            highlighted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            softWrap = false,
        )
    }
}

@Composable
private fun verbLabel(tool: String): String = stringResource(
    when (tool) {
        "read" -> R.string.code_tool_read
        "list" -> R.string.code_tool_list
        "search" -> R.string.code_tool_search
        "write" -> R.string.code_tool_write
        "edit" -> R.string.code_tool_edit
        "delete" -> R.string.code_tool_delete
        "rename" -> R.string.code_tool_rename
        "glob" -> R.string.code_tool_glob
        "todo" -> R.string.code_tool_todo
        "web" -> R.string.code_tool_web
        "fetch" -> R.string.code_tool_fetch
        "remember" -> R.string.code_tool_remember
        "ask" -> R.string.code_tool_ask
        "plan" -> R.string.code_tool_plan
        else -> R.string.code_tool_other
    },
)

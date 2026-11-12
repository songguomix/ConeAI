package com.cone.agent.ui.components

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cone.agent.R
import com.cone.agent.assistant.AnswerSpeech
import com.cone.agent.domain.model.Sender
import com.cone.agent.domain.model.UiMessage
import com.cone.agent.ui.theme.ConeShapes
import java.io.File
import java.util.Locale

/**
 * ChatGPT-style message: user turns are a right-aligned grey bubble; the assistant's answer is plain
 * left-aligned text with no bubble; agent execution logs (tool/system) are small, muted lines.
 */
@Composable
fun MessageBubble(message: UiMessage) {
    when {
        // 接口/工具执行（含失败）以专属卡片呈现，而不是一行灰字。
        message.sender == Sender.TOOL -> ToolCard(message)
        message.isError -> SystemLine(message.text, MaterialTheme.colorScheme.error)
        message.sender == Sender.USER -> UserBubble(message)
        message.sender == Sender.AGENT -> AssistantMessage(message)
        else -> SystemLine(message.text, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 接口执行卡片（Pixel 风格）：大圆角色调容器 + Pixel 设置页式的彩色圆形图标徽章。
 *  - 动作结果行（"导航到：公司 → ✓：已开始导航"）：主色圆徽章 ⚡ + 标题 + 状态副行；
 *  - 接口返回的多行信息（天气/汇率/历史检索…）：次色调圆徽章 🌐 + 首行标题，正文默认收起、
 *    点卡片平滑展开（与参考来源卡片同交互）；
 *  - 失败：整卡转 errorContainer，徽章转 error 圆。
 */
@Composable
private fun ToolCard(message: UiMessage) {
    val isError = message.isError
    val lines = remember(message.text) { message.text.trim().split('\n') }
    val first = lines.firstOrNull().orEmpty()
    val body = remember(message.text) { lines.drop(1).joinToString("\n").trim() }
    val arrow = first.indexOf(" → ")
    val rawTitle = if (arrow > 0) first.substring(0, arrow) else first
    val status = if (arrow > 0) first.substring(arrow + 3) else null
    val expandable = body.isNotBlank() || rawTitle.length > 60
    var expanded by remember { mutableStateOf(false) }

    val dotColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 一步一行，像 GitHub Actions 的步骤列表：左侧状态点 + 人话动作 + 结果，点开才看细节。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .let { if (expandable) it.clickable { expanded = !expanded } else it }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = humanAction(rawTitle),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            status?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error else subColor,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded && body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (expandable) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = subColor,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
        }
    }
}

/** 把工具名/英文动作翻成"正在做什么"，看不懂就原样显示。 */
internal fun humanAction(raw: String): String {
    val text = raw.trim()
    val map = listOf(
        "tap" to "点击", "click" to "点击", "long_press" to "长按",
        "swipe" to "滑动", "scroll" to "滚动", "type" to "输入", "input_text" to "输入",
        "open_app" to "打开应用", "launch" to "打开", "back" to "返回", "home" to "回到桌面",
        "screenshot" to "看屏幕", "observe" to "读取屏幕", "wait" to "等待",
    )
    for ((needle, zh) in map) {
        if (text.startsWith(needle, ignoreCase = true)) {
            return zh + text.removePrefix(needle).trimStart(':', '：', ' ', '(', ')')
        }
    }
    return text
}

@Composable
private fun UserBubble(message: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        message.imagePath?.let { path ->
            var showViewer by remember(path) { mutableStateOf(false) }
            // Bounded box, true proportions. A fixed 180dp square with Crop centre-cropped every
            // upload: a wide screenshot lost both its ends and a tall photo most of its middle, so
            // the thumbnail never matched what tapping it opened.
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .sizeIn(maxWidth = 220.dp, maxHeight = 260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showViewer = true },
            )
            // Tap to open the same full-screen viewer (view + 下载) used for model images.
            if (showViewer) {
                ImageViewerDialog(model = File(path), url = path, onDismiss = { showViewer = false })
            }
        }
        message.fileName?.let { name -> FileCard(name, message.fileSize) }
        if (message.text.isNotBlank()) {
            // Gemini 的用户消息：品牌色容器 + 大圆角，右下角收一个小角作为"气泡尾巴"。
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 6.dp, bottomStart = 24.dp),
            ) {
                Text(
                    text = message.text,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: UiMessage) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedText = stringResource(R.string.asst_copied)
    val copyLabel = stringResource(R.string.cd_copy)
    Column(modifier = Modifier.fillMaxWidth()) {
        // Model replies may contain Markdown (headings, lists, bold, code) — render it formatted.
        MarkdownText(
            markdown = message.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        // Grok-style 联网搜索 sources, shown as a card under the answer (citations [n] map to these).
        message.sources?.takeIf { it.isNotBlank() }?.let { SourcesCard(it) }
        // Claude-Code-style footer: generation time + token count, when known.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            answerFooter(message)?.let { footer ->
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            // Read-aloud, off unless asked for: nothing speaks on its own, the button starts it and
            // tapping again (or starting another answer) stops it.
            val speakingId by AnswerSpeech.speakingId.collectAsState()
            val speaking = speakingId == message.id
            IconButton(
                onClick = { AnswerSpeech.toggle(context, message.id, message.text) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (speaking) Icons.Filled.StopCircle else Icons.Filled.VolumeUp,
                    contentDescription = stringResource(
                        if (speaking) R.string.cd_stop_speaking else R.string.cd_speak,
                    ),
                    tint = if (speaking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message.text))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = copyLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun answerFooter(message: UiMessage): String? {
    val elapsed = message.elapsedMs ?: return null
    val dur = "%.1fs".format(elapsed / 1000.0)
    val tk = message.tokens
    return if (tk != null) {
        stringResource(R.string.answer_footer, dur, "%,d".format(tk))
    } else {
        stringResource(R.string.answer_footer_time_only, dur)
    }
}

/** A sent file shown as its own card in the conversation (icon + name + size), like Grok/ChatGPT. */
@Composable
private fun FileCard(name: String, size: Long?) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 260.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                fileMeta(name, size)?.let { meta ->
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "PDF · 1.2 MB" style subtitle for a file card, or null when neither piece is known. */
private fun fileMeta(name: String, size: Long?): String? {
    val ext = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val sizeText = size?.takeIf { it >= 0 }?.let { formatFileSize(it) }
    return listOfNotNull(ext.takeIf { it.isNotBlank() }, sizeText)
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

/**
 * Grok-style 参考来源 card: collapsed to a single "参考来源 · N" header by default; tapping the header
 * expands the numbered list. Each row is tappable and opens its URL; the numbers line up with the
 * [n] citations in the answer text.
 */
@Composable
private fun SourcesCard(encoded: String) {
    val sources = remember(encoded) { parseSources(encoded) }
    if (sources.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.TravelExplore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "${stringResource(R.string.sources_title)} · ${sources.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                sources.forEachIndexed { index, src ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { runCatching { uriHandler.openUri(src.url) } },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "[${index + 1}]",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = src.title.ifBlank { src.url },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = src.host(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class WebSource(val title: String, val url: String) {
    /** The bare host shown under each source (e.g. "en.wikipedia.org"). */
    fun host(): String = runCatching {
        url.substringAfter("://").substringBefore('/').removePrefix("www.")
    }.getOrDefault(url)
}

/** Decodes the "title\turl" lines stored on an answer back into sources for the card. */
private fun parseSources(encoded: String): List<WebSource> =
    encoded.split("\n").mapNotNull { line ->
        val parts = line.split("\t", limit = 2)
        val url = parts.getOrNull(1)?.trim().orEmpty()
        if (url.isBlank()) null else WebSource(parts[0].trim(), url)
    }

@Composable
private fun SystemLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

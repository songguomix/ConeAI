package com.cone.agent.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.cone.agent.R
import com.cone.agent.core.MarkdownInline
import com.cone.agent.vision.ImageDownloader

/**
 * A lightweight Markdown renderer for model replies. Handles the subset LLMs actually emit: ATX
 * headings (`#`–`######`), `-`/`*`/`+` and `1.` lists, fenced ``` code blocks, `>` block quotes,
 * horizontal rules, standalone `![alt](url)` images (http(s) or `data:` URLs, rendered via Coil), and
 * inline `**bold**`, `*italic*`/`_italic_`, `` `code` `` and clickable `[text](url)` links. This lets
 * the model "output" pictures/charts (e.g. QuickChart / mermaid.ink URLs) and downloadable
 * attachments. Unknown syntax is shown verbatim.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    // LaTeX the model may have emitted (\frac、$x^2$、\times…) becomes plain Unicode first —
    // this renderer shows unknown syntax verbatim, and raw LaTeX in a math answer is unreadable.
    val blocks = remember(markdown) { parseBlocks(com.cone.agent.core.MathText.normalize(markdown)) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = parseInline(block.text, codeBg, linkColor),
                    color = color,
                    style = style.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 19.sp
                            3 -> 17.sp
                            else -> 16.sp
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                )

                is MdBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        text = block.marker,
                        color = mutedColor,
                        style = style,
                        modifier = Modifier.padding(start = (block.indent * 14).dp, end = 6.dp),
                    )
                    Text(
                        text = parseInline(block.text, codeBg, linkColor),
                        color = color,
                        style = style,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .width(3.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(mutedColor),
                    )
                    Text(
                        text = parseInline(block.text, codeBg, linkColor),
                        color = mutedColor,
                        style = style,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Code -> Surface(
                    color = codeBg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        text = block.text,
                        color = color,
                        style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        modifier = Modifier.padding(12.dp),
                    )
                }

                is MdBlock.Image -> MarkdownImage(block.url, block.alt)

                is MdBlock.Table -> MarkdownTable(block, color, codeBg, linkColor, style, mutedColor)

                is MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(mutedColor.copy(alpha = 0.3f)),
                )

                is MdBlock.Paragraph ->
                    if (block.text.isBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    } else {
                        Text(
                            text = parseInline(block.text, codeBg, linkColor),
                            color = color,
                            style = style,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                    }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Image(val url: String, val alt: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    object Rule : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
// A GitHub-style table separator row, e.g. "| --- | :--: | ---: |" (alignment colons optional).
private val tableSepRegex = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

/**
 * Renders a Markdown image (Coil): a "生成中…" spinner while it loads, an error label on failure, and
 * tap-to-view a full-screen viewer with a download button. `data:` URLs are decoded to a Bitmap.
 */
@Composable
private fun MarkdownImage(url: String, alt: String) {
    val model = remember(url) { ImageDownloader.coilModel(url) }
    var showViewer by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    SubcomposeAsyncImage(
        model = model,
        contentDescription = alt.ifBlank { null },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .padding(vertical = 6.dp)
            .clip(shape)
            .clickable { showViewer = true },
        loading = { ImageStatus(generating = true) },
        error = { ImageStatus(generating = false) },
        success = { SubcomposeAsyncImageContent() },
    )

    if (showViewer) {
        ImageViewerDialog(model = model, url = url, onDismiss = { showViewer = false })
    }
}

/** The in-card placeholder: a spinner + "生成中…" while loading, or a failure label on error. */
@Composable
private fun ImageStatus(generating: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (generating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(if (generating) R.string.img_generating else R.string.img_load_failed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Full-screen image viewer: tap-outside / 关闭 to dismiss, 下载 to save the image. Shared with the
 *  chat's uploaded-image bubbles. [url] is the download source (http/data URL or local file path). */
@Composable
internal fun ImageViewerDialog(model: Any, url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            // Plain AsyncImage rather than the Subcompose variant: with a `loading` slot the latter
            // sizes itself from whichever child is composed, so the fixed-height placeholder was
            // still dictating the layout when the real bitmap arrived and the photo was drawn into
            // the placeholder's box — the wrong aspect ratio the user sees on tapping in. A single
            // Fit-scaled image inside a fixed full-screen box can only letterbox, never distort.
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 72.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.Center,
            ) {
                ViewerButton(stringResource(R.string.img_download), filled = true) {
                    ImageDownloader.save(context, url)
                }
                Spacer(Modifier.width(14.dp))
                ViewerButton(stringResource(R.string.cd_close), filled = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ViewerButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (filled) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

private fun parseBlocks(markdown: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                // Fenced code block: gather until the closing fence (or end of text).
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    sb.appendLine(lines[i]); i++
                }
                if (i < lines.size) i++ // consume closing fence
                blocks.add(MdBlock.Code(sb.toString().trimEnd('\n')))
                continue
            }
            // A table: a row with pipes immediately followed by a dashed separator row. Gather the
            // header, skip the separator, then take every following pipe row as a data row.
            line.contains('|') && i + 1 < lines.size && tableSepRegex.matches(lines[i + 1].trim()) &&
                lines[i + 1].contains('-') -> {
                val headers = splitTableRow(line)
                i += 2 // consume header + separator
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains('|') &&
                    !lines[i].trim().startsWith("```")
                ) {
                    rows.add(splitTableRow(lines[i])); i++
                }
                blocks.add(MdBlock.Table(headers, rows))
                continue
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" ->
                blocks.add(MdBlock.Rule)
            headingRegex.matches(line) -> {
                val m = headingRegex.find(line)!!
                blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            }
            bulletRegex.matches(line) -> {
                val m = bulletRegex.find(line)!!
                val indent = m.groupValues[1].length / 2
                addListItemWithImages(blocks, "•", m.groupValues[2], indent)
            }
            orderedRegex.matches(line) -> {
                val m = orderedRegex.find(line)!!
                val indent = m.groupValues[1].length / 2
                addListItemWithImages(blocks, "${m.groupValues[2]}.", m.groupValues[3], indent)
            }
            trimmed.startsWith(">") ->
                addParagraphWithImages(blocks, trimmed.removePrefix(">").trim())
            else -> addParagraphWithImages(blocks, line)
        }
        i++
    }
    return blocks
}

/**
 * Emits a paragraph line, splitting out any inline `![alt](url)` images into their own [MdBlock.Image]
 * blocks (with the surrounding text kept as paragraphs), so an image embedded mid-sentence still
 * renders instead of showing raw Markdown.
 */
private fun addParagraphWithImages(blocks: MutableList<MdBlock>, line: String) {
    val images = MarkdownInline.findImages(line)
    if (images.isEmpty()) {
        blocks.add(MdBlock.Paragraph(line))
        return
    }
    var cursor = 0
    for (img in images) {
        val before = line.substring(cursor, img.start)
        if (before.isNotBlank()) blocks.add(MdBlock.Paragraph(before))
        blocks.add(MdBlock.Image(img.url, img.alt))
        cursor = img.end
    }
    val after = line.substring(cursor)
    if (after.isNotBlank()) blocks.add(MdBlock.Paragraph(after))
}

/**
 * A list item that contains an image: the text keeps its bullet, and each image becomes its own
 * block. Models routinely present figures as a list（「1. 结构示意图：![图](url)」）, and without this
 * every one of them reached the reader as raw `![…](…)` source — only standalone paragraphs were
 * ever scanned for images.
 */
private fun addListItemWithImages(
    blocks: MutableList<MdBlock>,
    marker: String,
    content: String,
    indent: Int,
) {
    val images = MarkdownInline.findImages(content)
    if (images.isEmpty()) {
        blocks.add(MdBlock.ListItem(marker, content, indent))
        return
    }
    var cursor = 0
    var first = true
    for (img in images) {
        val before = content.substring(cursor, img.start)
        // The bullet belongs to the item's opening text; a bare image keeps the marker so the list
        // doesn't visually lose an entry.
        if (before.isNotBlank()) {
            blocks.add(MdBlock.ListItem(if (first) marker else "", before, indent))
            first = false
        } else if (first) {
            blocks.add(MdBlock.ListItem(marker, "", indent))
            first = false
        }
        blocks.add(MdBlock.Image(img.url, img.alt))
        cursor = img.end
    }
    val after = content.substring(cursor)
    if (after.isNotBlank()) blocks.add(MdBlock.ListItem("", after, indent))
}

/** Splits one Markdown table row on unescaped `|`, dropping the optional outer pipes. */
private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split("|").map { it.trim().replace("\\|", "|") }
}

/** Renders a Markdown table: a bold, tinted header row over data rows, boxed with light dividers. */
@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    color: Color,
    codeBg: Color,
    linkColor: Color,
    style: TextStyle,
    mutedColor: Color,
) {
    val border = mutedColor.copy(alpha = 0.35f)
    val columns = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

    @Composable
    fun row(cells: List<String>, header: Boolean, background: Color) {
        Row(modifier = Modifier.fillMaxWidth().background(background)) {
            for (c in 0 until columns) {
                Text(
                    text = parseInline(cells.getOrElse(c) { "" }, codeBg, linkColor),
                    color = color,
                    style = if (header) style.copy(fontWeight = FontWeight.Bold) else style,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp)),
    ) {
        row(table.headers, header = true, background = codeBg)
        table.rows.forEach { r ->
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(border))
            row(r, header = false, background = Color.Transparent)
        }
    }
}

/** Renders inline `**bold**`, `*italic*`/`_italic_`, `~~strike~~`, `` `code` `` and `[text](url)`. */
private fun parseInline(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendInline(text, codeBg, linkColor)
}

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit()

/** A `_`/`__` marker at [i] may open emphasis only at a word boundary (so file_name stays literal). */
private fun leftFlank(text: String, i: Int): Boolean = i == 0 || !isWordChar(text[i - 1])

/** [pos] is the char right after a closing `_`/`__`; it must also be a word boundary. */
private fun rightFlank(text: String, pos: Int): Boolean = pos >= text.length || !isWordChar(text[pos])

/**
 * Appends [text] with inline styling into the current builder, recursing into bold/italic/strike so
 * nested markup (e.g. **bold with `code`**) is rendered instead of shown raw. Underscore emphasis is
 * gated to word boundaries so snake_case / __dunder__ identifiers aren't mangled, and single `*`/`_`
 * italic requires non-space inner edges so "2 * 3" isn't italicised.
 */
private fun AnnotatedString.Builder.appendInline(text: String, codeBg: Color, linkColor: Color) {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(text.substring(i + 2, end), codeBg, linkColor) }
                    i = end + 2
                } else { append("**"); i += 2 }
            }
            rest.startsWith("__") -> {
                val end = if (leftFlank(text, i)) {
                    text.indexOf("__", i + 2).takeIf { it > i + 2 && rightFlank(text, it + 2) } ?: -1
                } else -1
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(text.substring(i + 2, end), codeBg, linkColor) }
                    i = end + 2
                } else { append("__"); i += 2 }
            }
            rest.startsWith("~~") -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(text.substring(i + 2, end), codeBg, linkColor) }
                    i = end + 2
                } else { append("~~"); i += 2 }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    // Code spans are verbatim — no nested parsing inside them.
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append('`'); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), codeBg, linkColor) }
                    i = end + 1
                } else { append('*'); i++ }
            }
            text[i] == '_' -> {
                val end = if (leftFlank(text, i)) text.indexOf('_', i + 1) else -1
                if (end > i + 1 && rightFlank(text, end + 1) && !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), codeBg, linkColor) }
                    i = end + 1
                } else { append('_'); i++ }
            }
            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > 0) text.indexOf('(', close) else -1
                // matchClosingParen lets URLs contain balanced parens (e.g. Wikipedia /wiki/Foo_(bar)).
                val end = if (open == close + 1) MarkdownInline.matchClosingParen(text, open) else -1
                if (close > 0 && open == close + 1 && end > 0) {
                    val label = text.substring(i + 1, close)
                    val url = MarkdownInline.cleanUrl(text.substring(open + 1, end))
                    // Clickable link: Compose's Text opens it via the platform's default URI handler.
                    val styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    )
                    withLink(LinkAnnotation.Url(url, styles)) { append(label) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

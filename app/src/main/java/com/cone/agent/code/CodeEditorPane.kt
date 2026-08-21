package com.cone.agent.code

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import java.io.File

private val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)

private val GutterWidth = 44.dp

/**
 * A real code editor, not a text box.
 *
 * Wrapping is off and the field lives inside a horizontal scroller, which is what makes the line
 * gutter trustworthy: with one visual line per logical line, the numbers column stays aligned with
 * the code no matter how long a line gets. Highlighting rides along as a [VisualTransformation], so
 * colors update as you type without the editor ever holding a second copy of the text.
 */
@Composable
internal fun EditorPane(
    file: OpenFile,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = rememberCodePalette()
    val lang = file.lang
    val transformation = remember(lang, palette) {
        VisualTransformation { text ->
            TransformedText(CodeHighlighter.highlight(text.text, lang, palette), OffsetMapping.Identity)
        }
    }
    val lineNumbers = remember(file.buffer) {
        (1..file.buffer.count { it == '\n' }.plus(1)).joinToString("\n")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            PaneHeader(
                title = file.path.substringAfterLast('/'),
                subtitle = if (file.dirty) {
                    "${lang.label} · ${stringResource(R.string.code_unsaved)}"
                } else {
                    lang.label
                },
                onClose = onClose,
            ) {
                IconButton(onClick = onSave, enabled = file.dirty) {
                    Icon(
                        Icons.Filled.Check,
                        stringResource(R.string.code_save),
                        tint = if (file.dirty) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                // The field is at least as wide as the viewport so taps anywhere on a line focus it,
                // and grows past that for long lines (the horizontal scroller takes over from there).
                val minEditorWidth = maxWidth - GutterWidth
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = lineNumbers,
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .width(GutterWidth)
                            .padding(end = 10.dp, top = 12.dp),
                    )
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        BasicTextField(
                            value = file.buffer,
                            onValueChange = onChange,
                            textStyle = CodeTextStyle.copy(color = palette.text),
                            visualTransformation = transformation,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            // Autocorrect and auto-capitalisation are actively harmful in code.
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                            ),
                            modifier = Modifier
                                .widthIn(min = minEditorWidth)
                                .padding(top = 12.dp, bottom = 40.dp, end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Live preview of the project's page. It loads straight off disk over `file://`, so relative
 * `styles.css` / `app.js` references resolve exactly as they would in a browser — write, save,
 * reload, see it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PreviewPane(file: File, version: Int, onClose: () -> Unit) {
    var reloadTick by remember { mutableIntStateOf(0) }
    val url = remember(file) { "file://${file.absolutePath}" }
    // [version] changes whenever the agent writes to the project, so a preview left open while a
    // task runs refreshes itself instead of showing the page as it was before the edits.
    val reloadKey = reloadTick + version

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            PaneHeader(
                title = stringResource(R.string.code_preview),
                subtitle = file.name,
                onClose = onClose,
            ) {
                IconButton(onClick = { reloadTick++ }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.code_reload))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            AndroidView(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The file behind this URL is rewritten between loads, and a cached copy of
                        // the previous version is exactly the wrong thing to show after a task ends.
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        // Needed to open the project's own page over file://. Cross-origin access
                        // FROM that page is left at the platform default (denied), so a script the
                        // model wrote cannot read the app's other private files.
                        settings.allowFileAccess = true
                    }
                },
                update = { webView ->
                    if (webView.tag != reloadKey) {
                        webView.tag = reloadKey
                        webView.clearCache(false)
                        webView.loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

/** The project's files, as a bottom sheet: tap to edit, plus create/delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileSheet(viewModel: CodeViewModel, onDismiss: () -> Unit) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CodeNode?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.code_files),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.code_new_file))
            }
        }

        if (tree.isEmpty()) {
            Text(
                stringResource(R.string.code_no_files),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(tree, key = { it.path }) { node ->
                    FileRow(
                        node = node,
                        // No explicit dismiss: opening a file switches the pane to the editor, which
                        // takes the sheet down with it — and leaves it up if the read failed, so the
                        // error lands with the file list still in front of the user.
                        onOpen = { if (!node.isDir) viewModel.openFile(node.path) },
                        onDelete = { pendingDelete = node },
                    )
                }
            }
        }
    }

    if (creating) {
        var path by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.code_new_file)) },
            text = {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.code_file_path)) },
                    placeholder = { Text("src/app.js") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        creating = false
                        viewModel.createFile(path)
                    },
                ) { Text(stringResource(R.string.code_create)) }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.code_confirm_delete_file, node.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteFile(node.path)
                    },
                ) { Text(stringResource(R.string.code_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.code_cancel)) }
            },
        )
    }
}

@Composable
private fun FileRow(node: CodeNode, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !node.isDir, onClick = onOpen)
            .padding(start = (16 + node.depth * 16).dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (node.isDir) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
            null,
            tint = if (node.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (node.isDir) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!node.isDir) {
            Text(
                formatSize(node.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Delete,
                stringResource(R.string.code_action_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

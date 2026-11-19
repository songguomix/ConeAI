package com.cone.agent.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.cone.agent.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_IMAGE_TEXT = 24_000_000
private const val MAX_IMAGE_BYTES = 18_000_000
private val formats = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")

private data class StudioArtwork(val bytes: ByteArray, val mime: String)

@Composable
internal fun RemoteImageStudio(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, onConversation: () -> Unit, onBack: () -> Unit) {
    val calls = rememberFeatureCalls(viewModel, "remoteImageStudio", null) { it.flag("running") }
    val studio = calls.result
    val config = studio?.obj("config")
    var prompt by remember(studio?.text("prompt")) { mutableStateOf(studio?.text("prompt").orEmpty()) }
    var ratio by remember(config?.text("aspectRatio")) { mutableStateOf(config?.text("aspectRatio") ?: "1:1") }
    var artStyle by remember(config?.text("artStyleId")) { mutableStateOf(config?.text("artStyleId").orEmpty()) }
    var quality by remember(config?.text("quality")) { mutableStateOf(config?.text("quality") ?: "auto") }
    var style by remember(config?.text("style")) { mutableStateOf(config?.text("style") ?: "vivid") }
    var background by remember(config?.text("background")) { mutableStateOf(config?.text("background") ?: "auto") }
    var negative by remember(config?.text("negativePrompt")) { mutableStateOf(config?.text("negativePrompt").orEmpty()) }
    var provider by remember(config?.text("providerId")) { mutableStateOf(config?.text("providerId") ?: "auto") }
    var model by remember(config?.text("model")) { mutableStateOf(config?.text("model").orEmpty()) }
    val current = studio?.obj("selected")
    var imageId by remember(current?.text("id")) { mutableStateOf(current?.text("id")) }
    val selected = studio?.objects("gallery")?.firstOrNull { it.text("id") == imageId }
        ?: current?.takeIf { it.text("id") == imageId }
    var loaded by remember(imageId) { mutableStateOf<StudioArtwork?>(null) }
    var imageError by remember(imageId) { mutableStateOf<String?>(null) }
    var lightbox by remember { mutableStateOf(false) }
    var clearGallery by remember { mutableStateOf(false) }
    val loadErrorLabel = stringResource(R.string.remote_image_load_failed)
    LaunchedEffect(imageId, selected?.flag("available")) {
        if (imageId != null && selected?.flag("available") == true) {
            loaded = loadArtwork(viewModel, imageId.orEmpty())
            if (loaded == null) imageError = loadErrorLabel
        }
    }
    val context = LocalContext.current
    val isChinese = LocalConfiguration.current.locales[0].language == "zh"
    val scope = rememberCoroutineScope()
    val saveErrorLabel = stringResource(R.string.remote_feature_failed)
    var export by remember { mutableStateOf<StudioArtwork?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val file = export
        export = null
        if (uri != null && file != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } != null }.getOrDefault(false)
            }
            if (!saved) imageError = saveErrorLabel
        }
    }

    FeaturePage(stringResource(R.string.remote_image_studio), onBack) {
        FeatureError(calls.error)
        if (studio == null) {
            if (calls.loading) CircularProgressIndicator()
            else FeatureButton(R.string.remote_feature_refresh) { calls.call("state") }
            return@FeaturePage
        }
        if (!studio.flag("enabled")) {
            FeatureError(stringResource(R.string.remote_image_disabled))
            return@FeaturePage
        }

        val running = studio.flag("running")
        FeatureInput(prompt, { prompt = it }, R.string.remote_project_prompt, maxLines = 6)
        val groups = studio.objects("models")
        val providerOptions = listOf("auto" to stringResource(R.string.remote_image_auto)) +
            groups.mapNotNull { group -> group.text("providerId")?.let { id -> id to (group.text("label") ?: id) } }
        StudioChoice(R.string.remote_image_provider, provider, providerOptions) { chosen ->
            provider = chosen
            model = groups.firstOrNull { it.text("providerId") == chosen }?.objects("models")?.firstOrNull()?.text("id").orEmpty()
        }
        val models = if (provider == "auto") groups.flatMap { it.objects("models") }
            else groups.firstOrNull { it.text("providerId") == provider }?.objects("models").orEmpty()
        StudioChoice(R.string.remote_image_model, model, models.mapNotNull { entry ->
            entry.text("id")?.let { it to (entry.text("name") ?: it) }
        }.distinctBy { it.first }) { model = it }
        FeatureInput(model, { model = it }, R.string.remote_image_model)
        StudioChoice(R.string.remote_image_ratio, ratio, listOf("1:1", "16:9", "9:16", "4:3", "3:4").map { it to it }) { ratio = it }
        StudioChoice(R.string.remote_image_preset, artStyle,
            listOf("" to stringResource(R.string.remote_image_auto)) + studio.objects("presets").mapNotNull { entry ->
                entry.text("id")?.let { it to (entry.text(if (isChinese) "name" else "nameEn") ?: it) }
            }) { artStyle = it }
        StudioChoice(R.string.remote_image_quality, quality, listOf("auto", "low", "medium", "high", "standard", "hd").map { it to it }) { quality = it }
        StudioChoice(R.string.remote_image_color, style, listOf("vivid", "natural").map { it to it }) { style = it }
        StudioChoice(R.string.remote_image_background, background, listOf("auto", "opaque", "transparent").map { it to it }) { background = it }
        FeatureInput(negative, { negative = it }, R.string.remote_image_negative, maxLines = 3)
        if (running) {
            CircularProgressIndicator()
            FeatureLine(studio.text("progress").orEmpty())
            FeatureButton(R.string.remote_feature_cancel) { calls.call("cancel") }
        } else {
            FeatureButton(R.string.remote_image_generate, enabled = prompt.isNotBlank() && !calls.loading) {
                calls.call("generate", mapOf("prompt" to prompt, "aspectRatio" to ratio, "artStyleId" to artStyle,
                    "quality" to quality, "style" to style, "background" to background, "negativePrompt" to negative,
                    "providerId" to provider, "model" to model))
            }
            FeatureButton(R.string.remote_image_random, enabled = !calls.loading) { calls.call("random") }
            FeatureButton(R.string.remote_image_enhance, enabled = prompt.isNotBlank() && !calls.loading) {
                calls.call("enhance", mapOf("prompt" to prompt, "artStyleId" to artStyle))
            }
        }
        FeatureError(studio.text("error"))
        if (selected != null) {
            HorizontalDivider(color = LocalRemotePalette.current.border)
            FeatureHeading(stringResource(R.string.remote_image_result))
            FeatureLine(selected.text("prompt").orEmpty())
            FeatureLine(selected.text("revisedPrompt").orEmpty())
            FeatureLine(listOfNotNull(selected.text("model"), selected.text("aspectRatio"), selected.text("size")).joinToString(" · "))
            val artwork = loaded
            if (artwork != null) {
                AsyncImage(artwork.bytes, selected.text("prompt"), Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
                FeatureButton(R.string.remote_image_enlarge) { lightbox = true }
                FeatureButton(R.string.remote_image_save) {
                    export = artwork
                    save.launch("conecode-${selected.text("id")?.take(12) ?: "image"}.${formats[artwork.mime] ?: "png"}")
                }
                FeatureButton(R.string.remote_image_copy) {
                    scope.launch {
                        if (!copyArtwork(context, artwork)) imageError = saveErrorLabel
                    }
                }
            }
            FeatureButton(R.string.remote_image_reuse_prompt) { prompt = selected.text("prompt").orEmpty() }
            if (selected.flag("available")) {
                FeatureButton(R.string.remote_image_insert, enabled = !calls.loading && !snapshot.isStreaming) {
                    calls.call("insert", mapOf("id" to imageId.orEmpty(), "conversationId" to snapshot.activeConversationId.orEmpty()),
                        update = false, onSuccess = onConversation)
                }
            } else FeatureError(loadErrorLabel)
            FeatureError(imageError)
        }
        HorizontalDivider(color = LocalRemotePalette.current.border)
        FeatureHeading(stringResource(R.string.remote_image_gallery))
        for (entry in studio.objects("gallery")) {
            TextButton(onClick = { imageId = entry.text("id") }, modifier = Modifier.fillMaxWidth()) {
                Text(entry.text("prompt").orEmpty().take(200), modifier = Modifier.fillMaxWidth(), maxLines = 2)
            }
        }
        if (studio.objects("gallery").isNotEmpty()) {
            FeatureButton(R.string.remote_image_clear_gallery, enabled = !calls.loading) { clearGallery = true }
        }
    }
    if (lightbox && loaded != null) {
        AlertDialog(onDismissRequest = { lightbox = false },
            text = { AsyncImage(loaded?.bytes, selected?.text("prompt"), Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) },
            confirmButton = { TextButton(onClick = { lightbox = false }) { Text(stringResource(R.string.remote_feature_back)) } })
    }
    if (clearGallery) {
        AlertDialog(
            onDismissRequest = { clearGallery = false },
            title = { Text(stringResource(R.string.remote_image_clear_gallery)) },
            text = { Text(stringResource(R.string.remote_image_clear_gallery_confirm)) },
            confirmButton = {
                TextButton(onClick = { clearGallery = false; calls.call("clearGallery") }) {
                    Text(stringResource(R.string.remote_image_clear_gallery))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearGallery = false }) { Text(stringResource(R.string.remote_feature_cancel)) }
            },
        )
    }
}

private suspend fun copyArtwork(context: Context, image: StudioArtwork): Boolean =
    runCatching {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "conecode-clipboard.${formats[image.mime] ?: "png"}").apply { writeBytes(image.bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "ConeCode", uri))
        true
    }.getOrDefault(false)

@Composable
private fun StudioChoice(label: Int, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = options.isNotEmpty(), onClick = { expanded = true }) {
            val name = options.firstOrNull { it.first == value }?.second ?: value
            Text(listOf(stringResource(label), name).filter { it.isNotBlank() }.joinToString(" · "))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            for ((id, name) in options) {
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

private suspend fun loadArtwork(viewModel: RemoteViewModel, id: String): StudioArtwork? {
    var offset = 0
    val data = StringBuilder()
    while (true) {
        val frame = viewModel.featureRequest("remoteImageStudio", mapOf("action" to "read", "id" to id),
            mapOf("offset" to JsonPrimitive(offset))) ?: return null
        if (!frame.flag("ok")) return null
        frame.text("url")?.let { return downloadArtwork(it) }
        val mime = frame.text("mime")?.takeIf(formats::containsKey) ?: return null
        val total = frame.text("total")?.toIntOrNull()?.takeIf { it in 1..MAX_IMAGE_TEXT } ?: return null
        val part = frame.text("data") ?: return null
        if (part.isEmpty() || part.length + offset > total) return null
        data.append(part)
        offset += part.length
        if (offset == total) return withContext(Dispatchers.Default) {
            runCatching { StudioArtwork(Base64.decode(data.toString(), Base64.DEFAULT), mime) }.getOrNull()
        }
    }
}

private suspend fun downloadArtwork(url: String): StudioArtwork? = withContext(Dispatchers.IO) {
    runCatching {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.host != null)
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            val mime = connection.contentType?.substringBefore(';')?.takeIf(formats::containsKey) ?: return@runCatching null
            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_IMAGE_BYTES) return@runCatching null
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(32_768)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_IMAGE_BYTES) return@runCatching null
                    output.write(buffer, 0, count)
                }
            }
            StudioArtwork(output.toByteArray(), mime)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

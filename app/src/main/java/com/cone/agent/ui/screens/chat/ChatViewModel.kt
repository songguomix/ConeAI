package com.cone.agent.ui.screens.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.agent.AgentController
import com.cone.agent.agent.AgentRuntimeState
import com.cone.agent.agent.ChatResponder
import com.cone.agent.data.remote.WebSearchClient
import com.cone.agent.data.repository.ChatRepository
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.R
import com.cone.agent.core.Constants
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.domain.model.ConversationMode
import com.cone.agent.domain.model.ConversationSummary
import com.cone.agent.domain.model.Sender
import com.cone.agent.domain.model.UiMessage
import com.cone.agent.vision.AccessibilityBridge
import com.cone.agent.vision.ImageUtils
import com.cone.agent.vision.ScreenCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SelectedLabel(val providerName: String, val modelId: String)

/**
 * The three things this screen does. All three are pages of the same pager, sharing one drawer, one
 * top bar and one input island — 代码 is not a separate destination, it is the third page.
 */
enum class ChatMode { AGENT, ASK, CODE }

data class EnvironmentStatus(
    val modelReady: Boolean = false,
    val accessibilityReady: Boolean = false,
    val overlayReady: Boolean = false,
    val captureReady: Boolean = false,
    val notificationsReady: Boolean = true,
    val microphoneReady: Boolean = false,
    val assistantReady: Boolean = false,
    val appListReady: Boolean = true,
) {
    /**
     * 起步只要求选好模型。屏幕捕获/无障碍/悬浮窗不再是硬门槛：简单的接口直达任务（打开应用、
     * 导航、播放、打电话…）不依赖屏幕能力，智能体在运行时按已授权的能力自适应（提示词会告知
     * 其能力边界），真正需要看屏/点屏时才提示用户去开启。
     */
    val canStart: Boolean
        get() = modelReady

    /** 全部核心能力就绪（模型+无障碍+悬浮窗+屏幕捕获）；仅用于环境提示条，不拦截任务。 */
    val fullyReady: Boolean
        get() = modelReady && accessibilityReady && overlayReady && captureReady
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: AgentController,
    private val chatRepository: ChatRepository,
    private val chatResponder: ChatResponder,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val bridge: AccessibilityBridge,
    private val captureManager: ScreenCaptureManager,
    private val webSearchClient: WebSearchClient,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    // Persisted across recreation/process death so returning to the screen keeps the same 智能体/问答
    // mode (and therefore the same displayed + used model) instead of silently resetting to 智能体.
    private val _mode = MutableStateFlow(
        savedStateHandle.get<String>(KEY_MODE)
            ?.let { runCatching { ChatMode.valueOf(it) }.getOrNull() }
            ?: ChatMode.AGENT,
    )
    val mode: StateFlow<ChatMode> = _mode

    /** 无痕问答：不写入普通问答会话历史，只在当前页面内临时保留消息。 */
    private val _incognitoAsk = MutableStateFlow(false)
    val incognitoAsk: StateFlow<Boolean> = _incognitoAsk
    private val incognitoMessages = MutableStateFlow<List<UiMessage>>(emptyList())

    /** True while a plain Q&A (ASK mode) request is in flight. */
    private val _asking = MutableStateFlow(false)

    /** 0f..1f while an attached image is being prepared; null when nothing is in flight. */
    private val _imageProgress = MutableStateFlow<Float?>(null)
    val imageProgress: StateFlow<Float?> = _imageProgress.asStateFlow()
    val asking: StateFlow<Boolean> = _asking

    /**
     * The answer text as it streams in (ChatGPT-style), shown as a live bubble under the conversation.
     * Null when no answer is streaming; becomes a real message once the stream completes.
     */
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText

    /** True while the 联网搜索 (grounding) step is running, so the UI can show "联网搜索中…". */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    /** The in-flight 问答 job, kept so the user can interrupt the 思考 ([cancelAsk]). */
    private var askJob: kotlinx.coroutines.Job? = null

    /** 智能体 and 问答 keep separate threads, so the screen shows the right one per page. */
    val agentMessages: StateFlow<List<UiMessage>> = chatRepository.agentMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val askMessages: StateFlow<List<UiMessage>> = combine(
        chatRepository.askMessages,
        incognitoMessages,
        _incognitoAsk,
    ) { stored, incognito, incognitoAsk -> if (incognitoAsk) incognito else stored }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var incognitoMessageId = -1L
    private val incognitoImagePaths = mutableListOf<String>()

    /** Past conversations, shown in the navigation drawer's history area. */
    val conversations: StateFlow<List<ConversationSummary>> = chatRepository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Selected UI language tag ("zh" / "en" / "ja"). */
    val language: StateFlow<String> = settingsRepository.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "zh")

    /**
     * Whether 问答 grounds answers in live web-search results (user toggle). Available for any
     * selected model: grounding prepends scraped search results as plain text context, so it does
     * not depend on the model's tool/function-calling support.
     */
    val webSearchEnabled: StateFlow<Boolean> = settingsRepository.webSearchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleWebSearch() {
        viewModelScope.launch { settingsRepository.setWebSearchEnabled(!webSearchEnabled.value) }
    }

    val agentState: StateFlow<AgentRuntimeState> = controller.state

    /** All discovered models, for the top-bar inline model switcher (usable any time, incl. mid-answer). */
    val allModels: StateFlow<List<com.cone.agent.data.local.entity.VisionModelView>> =
        providerRepository.allModels
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The model the *current* mode actually uses (问答 → chat model, else agent model), for highlighting. */
    val activeModel: StateFlow<com.cone.agent.data.repository.SelectedAgentModel?> = combine(
        _mode,
        settingsRepository.selectedModel,
        settingsRepository.chatModel,
    ) { mode, agent, chat ->
        if (mode == ChatMode.AGENT) agent else (chat ?: agent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Switch the model for the current mode instantly (问答 sets the chat model, 智能体 the agent model). */
    fun switchModel(providerId: Long, modelId: String) {
        viewModelScope.launch {
            if (_mode.value == ChatMode.AGENT) {
                settingsRepository.selectModel(providerId, modelId)
            } else {
                settingsRepository.selectChatModel(providerId, modelId)
            }
        }
    }

    /** 当前单次对话内已记录回答的累计 token 用量。 */
    val cumulativeTokens: StateFlow<Int> = combine(
        _mode,
        controller.state,
        askMessages,
    ) { mode, agent, ask ->
        when (mode) {
            ChatMode.AGENT -> agent.cumulativeTokens
            ChatMode.ASK -> ask.sumOf { it.tokens ?: 0 }
            // 写代码 shows usage per turn in its own transcript; nothing to total up here.
            ChatMode.CODE -> 0
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val selectedLabel: StateFlow<SelectedLabel?> =
        combine(
            _mode,
            settingsRepository.selectedModel,
            settingsRepository.chatModel,
            providerRepository.providers,
        ) { mode, agentModel, chatModel, providers ->
            // Show the model the *current* mode actually uses: 问答 uses the chat model (falling back
            // to the agent model when none is set separately); 智能体 always uses its vision model.
            val sel = if (mode == ChatMode.AGENT) agentModel else (chatModel ?: agentModel)
            if (sel == null) return@combine null
            val name = providers.firstOrNull { it.id == sel.providerId }?.name
                ?: com.cone.agent.core.LocaleHelper.string(context, R.string.unknown_provider)
            SelectedLabel(name, sel.modelId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val environment: StateFlow<EnvironmentStatus> = combine(
        bridge.connected,
        captureManager.running,
        settingsRepository.selectedModel,
        refreshTick,
    ) { accessibility, capture, model, _ ->
        EnvironmentStatus(
            modelReady = model != null,
            accessibilityReady = accessibility,
            overlayReady = canDrawOverlays(),
            captureReady = capture,
            notificationsReady = notificationsGranted(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnvironmentStatus())

    fun refreshEnvironment() {
        refreshTick.value += 1
    }

    fun setMode(newMode: ChatMode) {
        _mode.value = newMode
        savedStateHandle[KEY_MODE] = newMode.name
    }

    fun send(text: String, imageUri: Uri? = null, fileUri: Uri? = null) {
        val trimmed = text.trim()
        when (_mode.value) {
            ChatMode.AGENT -> {
                if (trimmed.isBlank()) return
                if (!environment.value.canStart) return
                controller.start(trimmed)
            }
            ChatMode.ASK -> {
                if (trimmed.isBlank() && imageUri == null && fileUri == null) return
                ask(trimmed, imageUri, fileUri)
            }
            // 写代码 has its own agent and its own transcript; the screen routes there directly.
            ChatMode.CODE -> Unit
        }
    }

    private fun ask(question: String, imageUri: Uri?, fileUri: Uri?) {
        if (_asking.value) return
        _asking.value = true
        askJob = viewModelScope.launch {
            val history = askMessages.value
            val incognito = _incognitoAsk.value
            val storageDir = if (incognito) File(context.cacheDir, "incognito_attachments") else File(context.filesDir, "attachments")
            val prepared = imageUri?.let {
                _imageProgress.value = 0f
                try {
                    withContext(Dispatchers.IO) { prepareImage(it, storageDir) { p -> _imageProgress.value = p } }
                } finally {
                    _imageProgress.value = null
                }
            }
            prepared?.path?.let { if (incognito) incognitoImagePaths.add(it) }
            val file = fileUri?.let { withContext(Dispatchers.IO) { prepareFile(it) } }

            // The file is now its own card on the message; keep the bubble text to the question itself.
            val displayText = question.ifBlank {
                if (prepared != null) com.cone.agent.core.LocaleHelper.string(context, R.string.img_placeholder) else question
            }
            val startedAt = System.currentTimeMillis()
            val imageUrls = listOfNotNull(prepared?.dataUrl)
            val builder = StringBuilder()
            var tokens: Int? = null

            // 问答自主转执行：模型判定请求需要实际操作手机时，经此回调自动切到智能体模式开跑；
            // 回调返回的说明文字就是本轮问答的回答气泡。无痕问答不提供该能力。
            val agentHandoff: (suspend (String) -> String)? = if (incognito) {
                null
            } else {
                { instruction ->
                    if (environment.value.canStart) {
                        setMode(ChatMode.AGENT)
                        controller.start(instruction)
                        com.cone.agent.core.LocaleHelper.string(context, R.string.ask_task_handoff, instruction)
                    } else {
                        com.cone.agent.core.LocaleHelper.string(context, R.string.ask_task_env_missing)
                    }
                }
            }

            suspend fun saveUserMessage() {
                if (incognito) {
                    incognitoMessages.value = incognitoMessages.value + UiMessage(
                        id = incognitoMessageId--,
                        sender = Sender.USER,
                        text = displayText,
                        timestamp = startedAt,
                        imagePath = prepared?.path,
                        fileName = file?.name,
                        fileSize = file?.size,
                    )
                } else {
                    chatRepository.add(
                        Sender.USER, displayText, ConversationMode.ASK,
                        imagePath = prepared?.path,
                        fileName = file?.name,
                        fileSize = file?.size,
                    )
                }
            }

            // sources is passed in: the grounding result is only computed further below, after the
            // user message is saved, so this nested helper can't close over it directly.
            suspend fun saveAnswer(sources: String?) {
                if (incognito) {
                    incognitoMessages.value = incognitoMessages.value + UiMessage(
                        id = incognitoMessageId--,
                        sender = Sender.AGENT,
                        text = builder.toString(),
                        timestamp = System.currentTimeMillis(),
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        tokens = tokens,
                        sources = sources,
                    )
                } else {
                    chatRepository.add(
                        Sender.AGENT, builder.toString(), ConversationMode.ASK,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        tokens = tokens,
                        sources = sources,
                    )
                }
            }

            suspend fun saveError(text: String) {
                if (incognito) {
                    incognitoMessages.value = incognitoMessages.value + UiMessage(
                        id = incognitoMessageId--,
                        sender = Sender.SYSTEM,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isError = true,
                    )
                } else {
                    chatRepository.add(
                        Sender.SYSTEM, text, ConversationMode.ASK,
                        isError = true,
                    )
                }
            }

            saveUserMessage()

            val modelQuestion = buildString {
                if (file != null) {
                    append("【附件文件：${file.name}】\n")
                    append(file.text ?: "（无法读取该文件的文本内容，可能是二进制文件）")
                    append("\n\n")
                }
                append(question)
            }
            val grounded = augmentWithWebSearch(question, modelQuestion)

            try {
                chatResponder.answerStream(grounded.prompt, history, imageDataUrls = imageUrls, incognito = incognito, onAgentTask = agentHandoff)
                    .collect { chunk ->
                        when (chunk) {
                            is com.cone.agent.data.remote.ChatChunk.Delta -> {
                                builder.append(chunk.text)
                                _streamingText.value = builder.toString()
                            }
                            is com.cone.agent.data.remote.ChatChunk.Done -> tokens = chunk.totalTokens
                        }
                    }
                saveAnswer(grounded.sources)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // User hit stop: persist whatever streamed so far (outside the cancelled scope).
                if (builder.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { saveAnswer(grounded.sources) }
                }
                throw c
            } catch (e: Exception) {
                if (builder.isEmpty()) {
                    // Streaming unavailable/failed before any text — fall back to a normal answer.
                    chatResponder.answer(grounded.prompt, history, imageDataUrls = imageUrls, incognito = incognito, onAgentTask = agentHandoff)
                        .onSuccess {
                            builder.append(it.text); tokens = it.totalTokens; saveAnswer(grounded.sources)
                        }
                        .onFailure {
                            saveError(com.cone.agent.core.LocaleHelper.string(context, R.string.ask_answer_failed, it.message ?: ""))
                        }
                } else {
                    // Partial answer then an error — keep the partial text and note the failure.
                    saveAnswer(grounded.sources)
                    saveError(com.cone.agent.core.LocaleHelper.string(context, R.string.ask_answer_failed, e.message ?: ""))
                }
            } finally {
                _streamingText.value = null
            }
        }
        // Runs on normal completion, failure, OR cancellation (interrupt) — always clears the flag.
        askJob?.invokeOnCompletion { _asking.value = false }
    }

    /** Interrupt an in-flight 问答 (思考): cancel the model call and post a "已停止思考" notice. */
    fun cancelAsk() {
        if (askJob?.isActive != true) return
        askJob?.cancel()
        askJob = null
        viewModelScope.launch {
            if (_incognitoAsk.value) {
                incognitoMessages.value = incognitoMessages.value + UiMessage(
                    id = incognitoMessageId--,
                    sender = Sender.SYSTEM,
                    text = com.cone.agent.core.LocaleHelper.string(context, R.string.stopped_thinking),
                    timestamp = System.currentTimeMillis(),
                )
            } else {
                chatRepository.add(
                    Sender.SYSTEM,
                    com.cone.agent.core.LocaleHelper.string(context, R.string.stopped_thinking),
                    ConversationMode.ASK,
                )
            }
        }
    }

    /** 切换无痕问答模式：开启时清空当前临时消息，关闭时不影响普通问答历史。
     * 如果当前有进行中的 问答 请求，先取消它，避免回答写入错误位置。 */
    fun setIncognitoAsk(enabled: Boolean) {
        if (_asking.value) askJob?.cancel()
        _incognitoAsk.value = enabled
        incognitoMessages.value = emptyList()
        clearIncognitoImages()
    }

    private fun clearIncognitoImages() {
        incognitoImagePaths.forEach { runCatching { File(it).delete() } }
        incognitoImagePaths.clear()
    }

    /** The grounded prompt sent to the model plus the sources (encoded) to show as a Grok-style card. */
    private data class Grounded(val prompt: String, val sources: String?)

    private suspend fun augmentWithWebSearch(question: String, modelQuestion: String): Grounded {
        if (question.isBlank()) return Grounded(modelQuestion, null)
        _searching.value = true
        val hybrid = try { webSearchClient.searchHybrid(question, maxResults = 8, maxTokens = 3500) } finally { _searching.value = false }
        if (hybrid.results.isEmpty()) return Grounded(modelQuestion, null)
        val prompt = com.cone.agent.core.LocaleHelper.string(context, R.string.web_search_prompt, hybrid.context, modelQuestion)
        val encoded = hybrid.results.joinToString("\n") { r -> "${r.title.replace('\t',' ').replace('\n',' ')}\t${r.url}" }
        return Grounded(prompt, encoded)
    }

    private data class PreparedFile(val name: String, val size: Long?, val text: String?)

    /** Reads a picked file's display name, size and (for text-like files) its content, truncated. */
    private fun prepareFile(uri: Uri): PreparedFile {
        val (queriedName, size) = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    val s = if (it.isNull(1)) null else it.getLong(1)
                    n to s
                } else null
            }
        }.getOrNull() ?: (null to null)
        val name = queriedName ?: com.cone.agent.core.LocaleHelper.string(context, R.string.file_default)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val textLike = mime.startsWith("text/") ||
            mime == "application/json" || mime == "application/xml" ||
            ext in TEXT_EXTENSIONS
        val text = if (textLike) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Read a bounded prefix only — never slurp a whole (possibly huge) file into memory.
                    val buffer = ByteArray(MAX_FILE_BYTES)
                    var read = 0
                    while (read < buffer.size) {
                        val n = stream.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buffer, 0, read, Charsets.UTF_8)
                }
            }.getOrNull()?.take(MAX_FILE_CHARS)
        } else {
            null
        }
        return PreparedFile(name, size, text)
    }

    private data class PreparedImage(val path: String?, val dataUrl: String?)

    /** Decodes a picked image, persists a copy for display, and builds a data URL for the model. */
    private fun prepareImage(
        uri: Uri,
        storageDir: File = File(context.filesDir, "attachments"),
        onProgress: (Float) -> Unit = {},
    ): PreparedImage? {
        // Reported per stage rather than per byte: the wait the user is actually looking at is this
        // local work — a 12 MP photo has to be decoded, rotated, downscaled, JPEG-encoded and then
        // base64-encoded before anything can be sent — and these are the points where it advances.
        // Weighted by roughly how long each stage takes, so the bar doesn't stall on the slowest.
        onProgress(0.05f)
        val bitmap = runCatching { decodeBounded(uri) }.getOrNull() ?: return null
        onProgress(0.55f)
        val path = ImageUtils.saveScreenshot(
            storageDir,
            bitmap,
            Constants.SCREENSHOT_MAX_EDGE,
            Constants.SCREENSHOT_JPEG_QUALITY,
        )
        onProgress(0.75f)
        val dataUrl = ImageUtils.toDataUrl(
            bitmap,
            Constants.SCREENSHOT_MAX_EDGE,
            Constants.SCREENSHOT_JPEG_QUALITY,
        )
        onProgress(1f)
        return PreparedImage(path, dataUrl)
    }

    /**
     * Decodes [uri] with an [BitmapFactory.Options.inSampleSize] computed from its bounds, so a large
     * camera/gallery photo (e.g. 12 MP) isn't fully decoded into a huge bitmap and OOM the app.
     */
    private fun decodeBounded(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > 2 * Constants.SCREENSHOT_MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        // Apply the photo's EXIF orientation so portrait shots aren't shown rotated 90° (the saved
        // copy + data URL are re-compressed and would otherwise lose the orientation tag).
        val orientation = context.contentResolver.openInputStream(uri)?.use { ImageUtils.readOrientation(it) }
            ?: return bitmap
        return ImageUtils.applyExifOrientation(bitmap, orientation)
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun confirm(approved: Boolean) = controller.confirm(approved)

    /** 计划模式：同意开始执行已生成的计划，或取消整个任务。 */
    fun confirmPlan(approved: Boolean) = controller.confirmPlan(approved)

    /** Deletes every saved conversation (both 智能体 and 问答) and starts fresh empty sessions. */
    fun clearAllHistory() {
        viewModelScope.launch { chatRepository.clearAll() }
        controller.clearUsage()
        // A full wipe also drops any incognito scratch state.
        _incognitoAsk.value = false
        incognitoMessages.value = emptyList()
        clearIncognitoImages()
    }

    fun newConversation() {
        if (_mode.value == ChatMode.CODE) return
        viewModelScope.launch { chatRepository.startNewConversation(_mode.value.toConversationMode()) }
        // The 智能体 token tally lives on the agent runtime (only reset when a task starts), so a new
        // conversation must clear it explicitly or the usage bar keeps last task's count.
        controller.clearUsage()
        if (_mode.value == ChatMode.ASK) {
            _incognitoAsk.value = false
            incognitoMessages.value = emptyList()
            clearIncognitoImages()
        }
    }

    /** Reopen a past conversation from the drawer, switching the UI to that conversation's mode. */
    fun openConversation(id: Long, mode: ConversationMode) {
        chatRepository.switchTo(id, mode)
        // 打开历史对话时关闭无痕，确保历史消息能正常显示。
        if (mode == ConversationMode.ASK) setIncognitoAsk(false)
        setMode(if (mode == ConversationMode.AGENT) ChatMode.AGENT else ChatMode.ASK)
    }

    /** Delete a past conversation (from the drawer history list). */
    fun deleteConversation(id: Long) {
        viewModelScope.launch { chatRepository.deleteConversation(id) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { settingsRepository.setAppLanguage(tag) }
    }

    private fun ChatMode.toConversationMode(): ConversationMode =
        if (this == ChatMode.AGENT) ConversationMode.AGENT else ConversationMode.ASK

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val KEY_MODE = "chat_mode"
        const val MAX_FILE_BYTES = 256 * 1024 // read at most 256 KB of a text file
        const val MAX_FILE_CHARS = 8000        // and include at most this many chars in the prompt
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm", "yml", "yaml",
            "ini", "conf", "properties", "gradle", "kt", "kts", "java", "py", "js", "ts", "c", "cpp",
            "h", "hpp", "cs", "go", "rs", "rb", "php", "sh", "sql",
        )
    }
}

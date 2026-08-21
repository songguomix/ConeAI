package com.cone.agent.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cone.agent.R
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.MainActivity
import com.cone.agent.data.repository.ChatRepository
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.domain.model.ConversationMode
import com.cone.agent.domain.model.Sender
import com.cone.agent.domain.model.TaskStatus
import com.cone.agent.vision.ImageUtils
import com.cone.agent.vision.ScreenCaptureManager
import com.cone.agent.vision.ScreenCaptureService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The assistant popup, hosted in a real (translucent) Activity rather than the voice-interaction
 * session. This matters: [android.speech.SpeechRecognizer] reliably fails to record from inside a
 * [android.service.voice.VoiceInteractionSession] on many devices (every engine returns a
 * permission/client error), whereas a normal foreground Activity can record and can also request the
 * RECORD_AUDIO runtime permission itself. [ConeVoiceInteractionSession] simply launches this Activity.
 *
 * Recognition is imperfect, so the final transcript is dropped into the editable field for the user to
 * review / correct and explicitly send. On send the 执行 / 思考 toggle decides: 执行 hands off to
 * [AssistantLauncherActivity] (capture + start the agent), 思考 answers via
 * [com.cone.agent.agent.ChatResponder] in a Gemini-style card and reads it aloud via [TtsSpeaker].
 */
@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {

    @Inject lateinit var captureManager: ScreenCaptureManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var webSearchClient: com.cone.agent.data.remote.WebSearchClient
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var agentController: com.cone.agent.agent.AgentController

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var pill: AssistantPillView

    /** The in-flight 问答 (思考) job, kept so the user can interrupt it via the pill's stop button. */
    private var askJob: Job? = null

    /** Watches the agent while it runs under the pill（接口任务不退窗，见 [handOff]）. */
    private var agentWatchJob: Job? = null

    /** False until this assistant session's first question opens its own fresh 问答 conversation. */
    private var sessionConversationStarted = false
    private var speech: SpeechController? = null
    private var listening = false
    private var handedOff = false

    /** Whether 思考 answers are read aloud; user-toggleable from the answer card and persisted. */
    private var soundEnabled = true

    /** Whether 思考 grounds answers with web search (Bing); user-toggleable and persisted. */
    private var webSearchEnabled = false

    // Offline on-device recognition (Vosk) — the fall-back when no system recognizer will run.
    private var vosk: VoskSpeechEngine? = null
    private var voskListening = false
    private val voskAvailable by lazy { VoskSpeechEngine.isModelAvailable(this) }

    // "圈选屏幕" / 相机 / 图片 → ask: one or more images (cropped regions, photos, or gallery picks)
    // encoded as data URLs, all sent with the next 问答 question. 文件 → the picked file's name +
    // extracted text, prepended to the question.
    private val attachedImages = mutableListOf<String>()
    private var attachedFileName: String? = null
    private var attachedFileText: String? = null
    private var pendingCameraUri: Uri? = null
    private var snipOverlay: SnipOverlayView? = null

    private val ttsSpeaker by lazy { TtsSpeaker(this) }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                pill.enterTypingMode()
                pill.setInputHint(getString(R.string.asst_mic_denied))
            }
        }

    // 相机: take a photo (no CAMERA permission needed — the system camera app handles it), attach it.
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) attachImageFromUri(uri) else if (!success) {
                pill.showStatus(getString(R.string.asst_capture_failed_retry))
            }
        }

    // 图片: pick one or more images from the gallery; each is decoded and attached for the next 问答.
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) attachImagesFromUris(uris)
        }

    // 文件: pick any file; we attach its name and (for text-like files) a bounded slice of its content.
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) attachFileFromUri(uri)
        }

    // Screen-capture consent, used for the live "圈选" fallback when no assist screenshot is available.
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
                // Give the projection time to start. A region circled before consent was granted is
                // still valid — the screen behind has not changed — so finish that instead of
                // dropping the user back into the manual picker.
                mainHandler.postDelayed(
                    { if (pendingSnipRect != null) captureAndCrop() else captureAndSnip() },
                    700L,
                )
            } else {
                Toast.makeText(this, getString(R.string.asst_no_capture_consent), Toast.LENGTH_SHORT).show()
            }
        }

    // Apply the user-selected UI language to this (View-based) Activity's resources.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pause the always-on wake-word listener while the assistant owns the mic, so the two never
        // capture / decode at the same time.
        com.cone.agent.service.WakeWordService.suspended = true
        pill = AssistantPillView(
            context = this,
            onMic = { onMicTapped() },
            onStop = { cancelAsk() },
            onExpand = { openMainApp() },
            onSnip = { onSnipTapped() },
            onCamera = { launchCamera() },
            onImage = { runCatching { imagePicker.launch("image/*") } },
            onFile = { runCatching { filePicker.launch("*/*") } },
            onDismiss = { finish() },
            onSnipRect = { rect -> onSnipGesture(rect) },
            onModeChange = { agent -> if (agent) clearAttachments() },
            onSubmitText = { text -> onTextSubmitted(text) },
            onKeyboard = { typing -> onKeyboardToggled(typing) },
            onToggleSound = { toggleSound() },
            onToggleWebSearch = { toggleWebSearch() },
            onCopy = { Toast.makeText(this, getString(R.string.asst_copied), Toast.LENGTH_SHORT).show() },
        )
        setContentView(pill)
        // Play the slide-up + fade entrance only on a fresh open (not on config-change recreation).
        if (savedInstanceState == null) pill.animateIn()

        // Restore the saved mute + web-search preferences and reflect them in the pill.
        scope.launch {
            soundEnabled = settingsRepository.voiceReplyEnabled.first()
            pill.setSoundEnabled(soundEnabled)
            webSearchEnabled = settingsRepository.webSearchEnabled.first()
            pill.setWebSearchEnabled(webSearchEnabled)
        }

        // Drive the keyboard ourselves: the scrim stays full-screen and only the pill (bottom stack)
        // lifts above the IME, instead of the translucent window jumping around with adjustResize.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(pill) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            pill.applyBottomInset(maxOf(ime, nav))
            insets
        }

        ttsSpeaker.warmUp()

        if (hasMicPermission()) {
            startVoiceInput()
        } else {
            // An Activity (unlike the voice session) can ask for the mic permission directly.
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        // Resume the wake-word listener now that the assistant is releasing the mic.
        com.cone.agent.service.WakeWordService.suspended = false
        // 胶囊离场：告知控制器屏幕已让出（等待中的屏幕动作会随之重新观察真实屏幕）。
        runCatching { agentController.setAssistantOverlayVisible(false) }
        scope.cancel()
        listening = false
        voskListening = false
        runCatching { speech?.destroy() }
        speech = null
        runCatching { vosk?.destroy() }
        vosk = null
        ttsSpeaker.shutdown()
        super.onDestroy()
    }

    /**
     * Prefer the phone's system recognizer (more accurate, esp. Google's online STT) and fall back
     * to the bundled offline Vosk model only when no system recognizer will run. [startListening]
     * handles that fall-back itself, so it's the single entry point for both.
     */
    private fun startVoiceInput() {
        startListening()
    }

    /* ---------------- offline (Vosk) recognition ---------------- */

    private fun startVosk() {
        voskListening = true
        pill.showStatus(getString(R.string.asst_prep_offline))
        val engine = VoskSpeechEngine(this).also { vosk = it }
        engine.start(autoEndpoint = true, callbacks = object : VoskSpeechEngine.Callbacks {
            override fun onReady() {
                pill.showListening()
            }

            override fun onPartial(text: String) {
                if (text.isNotBlank()) pill.showTranscript(text)
            }

            override fun onFinal(text: String) {
                voskListening = false
                if (text.isBlank()) {
                    pill.showStatus(getString(R.string.asst_not_heard))
                } else {
                    // Speech ended (silence end-pointing) → send straight away, no manual tap. The
                    // model is told to tolerate transcription errors (see PromptBuilder / ChatResponder).
                    onTextSubmitted(text)
                }
            }

            override fun onError(message: String) {
                voskListening = false
                pill.showStatus(getString(R.string.asst_err_type_suffix, message))
            }
        })
    }

    private fun startListening() {
        val controller = SpeechController(this)
        // No system recognizer will run here — use the offline Vosk model if bundled, else typing.
        if (!controller.isAvailable) {
            if (voskAvailable) startVosk() else {
                pill.enterTypingMode()
                pill.setInputHint(getString(R.string.asst_voice_unavailable))
            }
            return
        }
        listening = true
        speech = controller
        ttsSpeaker.warmUp()
        pill.showListening()
        controller.start(object : SpeechController.Callbacks {
            override fun onLevel(normalized: Float) {
                pill.waveform.setLevel(normalized)
            }

            override fun onPartial(text: String) {
                pill.showTranscript(text)
            }

            override fun onFinal(text: String) {
                listening = false
                if (text.isBlank()) {
                    pill.showStatus(getString(R.string.asst_not_heard))
                    return
                }
                // Speech ended (the recognizer's silence end-pointing fired) → auto-send without a
                // manual review tap. Transcription slips are fine: the model is instructed to read
                // through same-sound / mis-heard words to the intended meaning.
                onTextSubmitted(text)
            }

            override fun onError(message: String) {
                listening = false
                pill.showStatus(getString(R.string.asst_err_type_suffix, message))
            }

            override fun onUnavailable(message: String) {
                listening = false
                runCatching { speech?.destroy() }
                speech = null
                // No system recognizer could run — fall back to the offline Vosk model if bundled,
                // otherwise to typing.
                if (voskAvailable) {
                    startVosk()
                } else {
                    pill.enterTypingMode()
                    pill.setInputHint(getString(R.string.asst_voice_unavailable))
                }
            }
        })
    }

    private fun onMicTapped() {
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        // Stop whichever engine is currently listening; if none is, start a fresh listen (system
        // recognizer first, Vosk fall-back — decided inside startListening).
        when {
            voskListening -> vosk?.stop()
            listening -> speech?.stop()
            else -> {
                speech?.destroy()
                vosk?.destroy()
                startListening()
            }
        }
    }

    /** The user typed/edited an instruction and tapped send — route it like a transcript. */
    private fun onTextSubmitted(text: String) {
        val instruction = text.trim()
        if (instruction.isBlank()) return
        listening = false
        speech?.destroy()
        speech = null
        if (pill.agentMode) { pill.showTranscript(instruction); handOff(instruction) }
        else { pill.showTranscript(instruction); askModel(instruction) }
    }

    private fun onKeyboardToggled(typing: Boolean) {
        if (typing) {
            // Switching to typing: stop any listening and release the mic.
            listening = false
            speech?.destroy()
            speech = null
            voskListening = false
            vosk?.destroy()
            vosk = null
            pill.waveform.reset()
        } else if (hasMicPermission()) {
            startVoiceInput()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * 执行 mode: run the agent **under the pill**. 纯接口任务（信息查询/闹钟/手电筒/系统设置…）
     * 全程在胶囊里完成——结果直接显示并朗读，悬浮窗不退出；智能体一旦标记 usingScreen（遇到第一个
     * 需要真实屏幕的动作），胶囊立即让位（finish → onDestroy 通知控制器，控制器等胶囊离场后重新
     * 观察真实屏幕再继续）。
     */
    private fun handOff(instruction: String) {
        if (handedOff) return
        handedOff = true
        pill.showThinking()
        agentController.setAssistantOverlayVisible(true)
        agentController.start(instruction)
        agentWatchJob?.cancel()
        agentWatchJob = scope.launch {
            var started = false
            agentController.state.collect { st ->
                // start() 重置状态是异步的，先跳过上一次任务残留的终态。
                if (!started) {
                    if (!st.isBusy) return@collect
                    started = true
                }
                when {
                    st.usingScreen -> {
                        // 需要真实屏幕：让位。onDestroy 会调 setAssistantOverlayVisible(false)。
                        finish()
                        throw kotlinx.coroutines.CancellationException()
                    }
                    st.status == TaskStatus.SUCCEEDED -> {
                        handedOff = false
                        val text = st.summary.ifBlank { getString(R.string.agent_status_done) }
                        pill.showAnswer(text)
                        if (soundEnabled) ttsSpeaker.speak(text)
                        throw kotlinx.coroutines.CancellationException()
                    }
                    st.status == TaskStatus.FAILED -> {
                        handedOff = false
                        pill.showAnswer(st.lastError ?: getString(R.string.agent_status_failed))
                        throw kotlinx.coroutines.CancellationException()
                    }
                    st.status == TaskStatus.CANCELLED -> {
                        handedOff = false
                        pill.showAnswer(getString(R.string.status_cancelled))
                        throw kotlinx.coroutines.CancellationException()
                    }
                    // 运行中：执行任务卡片实时刷新当前动作与思考。
                    else -> pill.showAgentTask(
                        st.currentAction.ifBlank { getString(R.string.agent_status_preparing) },
                        st.currentThought.take(140),
                    )
                }
            }
        }
    }

    /** 问答 mode: answer with the model (optionally about an image / file), show it, read it aloud. */
    private fun askModel(question: String) {
        // Consume the attachments so each is sent only once.
        val images = attachedImages.toList()
        val fileName = attachedFileName
        val fileText = attachedFileText
        clearAttachments()
        pill.showThinking()
        askJob = scope.launch {
            // Each assistant session lives in its own fresh 问答 conversation (created lazily on the
            // first question), so assistant chats start a new conversation instead of piling onto the
            // last one. Follow-up questions in the same session stay grouped together.
            if (!sessionConversationStarted) {
                sessionConversationStarted = true
                chatRepository.startNewConversation(ConversationMode.ASK)
            }
            // Record the turn in the shared 问答 history so assistant chats also show up in the drawer.
            val displayQuestion = buildString {
                if (question.isNotBlank()) append(question)
                if (fileName != null) { if (isNotEmpty()) append('\n'); append("📎 $fileName") }
            }.ifBlank {
                if (images.isNotEmpty()) getString(R.string.img_placeholder) else question
            }
            if (displayQuestion.isNotBlank()) {
                runCatching { chatRepository.add(Sender.USER, displayQuestion, ConversationMode.ASK) }
            }

            val responder = runCatching {
                EntryPointAccessors
                    .fromApplication(applicationContext, AssistantEntryPoint::class.java)
                    .chatResponder()
            }.getOrNull()

            if (responder == null) {
                val msg = getString(R.string.asst_init_failed)
                pill.showAnswer(msg)
                runCatching { chatRepository.add(Sender.SYSTEM, msg, ConversationMode.ASK, isError = true) }
                return@launch
            }

            // Prepend any attached file's content to the question sent to the model.
            val modelQuestion = if (fileName != null) {
                buildString {
                    append("【附件文件：$fileName】\n")
                    append(fileText ?: "（无法读取该文件的文本内容，可能是二进制文件）")
                    append("\n\n")
                    append(question)
                }
            } else {
                question
            }
            val grounded = augmentWithWebSearch(question, modelQuestion)
            val startedAt = System.currentTimeMillis()
            // 思考模式也能自主转执行：模型判定要操作手机时，走与「执行」模式相同的 handOff 交给智能体。
            responder.answer(
                grounded.prompt,
                imageDataUrls = images,
                onAgentTask = { instruction ->
                    handOff(instruction)
                    getString(R.string.ask_task_handoff, instruction)
                },
            )
                .onSuccess {
                    pill.showAnswer(it.text, grounded.sources)
                    if (soundEnabled) ttsSpeaker.speak(it.text)
                    runCatching {
                        chatRepository.add(
                            Sender.AGENT, it.text, ConversationMode.ASK,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            tokens = it.totalTokens,
                            sources = grounded.sources,
                        )
                    }
                }
                .onFailure {
                    val msg = getString(R.string.asst_answer_failed, it.message ?: "")
                    pill.showAnswer(msg)
                    runCatching { chatRepository.add(Sender.SYSTEM, msg, ConversationMode.ASK, isError = true) }
                }
        }
    }

    /** Expand the assistant into the full Cone app (dragging the pill's handle up). */
    private fun openMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        // Cross-fade so the pill's slide-up hands off smoothly to the app instead of a hard cut.
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /** Interrupt the in-flight 思考 request and show a "已停止思考" notice in the pill. */
    private fun cancelAsk() {
        if (askJob?.isActive != true) return
        askJob?.cancel()
        askJob = null
        pill.showAnswer(getString(R.string.stopped_thinking))
    }

    /** The grounded prompt for the model plus the encoded sources to render as a Grok-style card. */
    private data class Grounded(val prompt: String, val sources: String?)

    /**
     * Like the main screen: when 联网搜索 is on, search the raw [query], prepend the numbered results to
     * [modelQuestion] (which may already carry attached-file content) as grounding context, and return
     * the sources so the pill can show a 参考来源 card and cite [n].
     */
    private suspend fun augmentWithWebSearch(query: String, modelQuestion: String): Grounded {
        if (query.isBlank()) return Grounded(modelQuestion, null)
        pill.showSearching()
        val hybrid = webSearchClient.searchHybrid(query, maxResults = 8, maxTokens = 3500)
        pill.showThinking()
        if (hybrid.results.isEmpty()) return Grounded(modelQuestion, null)
        val prompt = LocaleHelper.string(this, R.string.web_search_prompt, hybrid.context, modelQuestion)
        val encoded = hybrid.results.joinToString("\n") { r -> "${r.title.replace('\t',' ').replace('\n',' ')}\t${r.url}" }
        return Grounded(prompt, encoded)
    }

    /** Toggle (and persist) whether 思考 answers are spoken aloud; silence any current speech if muting. */
    private fun toggleSound() {
        soundEnabled = !soundEnabled
        pill.setSoundEnabled(soundEnabled)
        if (!soundEnabled) ttsSpeaker.stop()
        scope.launch { settingsRepository.setVoiceReplyEnabled(soundEnabled) }
    }

    /** Toggle (and persist) 联网搜索 for the assistant's 思考 answers. */
    private fun toggleWebSearch() {
        webSearchEnabled = !webSearchEnabled
        pill.setWebSearchEnabled(webSearchEnabled)
        scope.launch { settingsRepository.setWebSearchEnabled(webSearchEnabled) }
    }

    /* ---------------- 圈选屏幕 → 提问 (Gemini-style) ---------------- */

    private fun onSnipTapped() {
        stopAllVoice()
        val assist = AssistantSnapshot.screen
        when {
            // Prefer the privileged assist screenshot (no consent prompt).
            assist != null && !assist.isRecycled -> showSnip(assist)
            // Else live-capture the current screen via MediaProjection.
            captureManager.isRunning -> captureAndSnip()
            else -> requestCaptureThenSnip()
        }
    }

    /* ---------------- 圈选手势：直接在屏幕上画出区域 ---------------- */

    /** A region circled on screen, in screen pixels, waiting for a frame to crop it out of. */
    private var pendingSnipRect: RectF? = null

    /**
     * The user drew a box over the screen. [rectInPill] is in the pill view's coordinates; it is
     * lifted to screen pixels here because the frame we crop from is a screenshot of the whole
     * display, and the assistant window does not necessarily start at (0,0).
     */
    private fun onSnipGesture(rectInPill: RectF) {
        stopAllVoice()
        val origin = IntArray(2)
        pill.getLocationOnScreen(origin)
        pendingSnipRect = RectF(rectInPill).apply { offset(origin[0].toFloat(), origin[1].toFloat()) }

        val assist = AssistantSnapshot.screen
        when {
            // The privileged assist screenshot is the screen as it was when the assistant opened —
            // exactly what the user just drew on — and needs no consent and no capture round-trip.
            assist != null && !assist.isRecycled -> cropAndAttach(assist, recycleSource = false)
            captureManager.isRunning -> captureAndCrop()
            else -> requestCaptureThenSnip()
        }
    }

    /** Takes a fresh frame with our own UI hidden, then crops it to the circled region. */
    private fun captureAndCrop() {
        if (!::pill.isInitialized) return
        pill.hideForCapture()
        mainHandler.postDelayed({
            scope.launch {
                val bmp = runCatching { captureManager.capture() }.getOrNull()
                if (!::pill.isInitialized) return@launch
                pill.showAfterCapture()
                if (bmp == null) {
                    pendingSnipRect = null
                    pill.showStatus(getString(R.string.asst_capture_failed_retry))
                } else {
                    cropAndAttach(bmp, recycleSource = true)
                }
            }
        }, 180L)
    }

    /**
     * Cuts the circled region out of [source]. The rectangle is in screen pixels and the bitmap may
     * be captured at a different resolution than the display, so it is rescaled rather than assumed
     * to line up.
     */
    private fun cropAndAttach(source: Bitmap, recycleSource: Boolean) {
        val rect = pendingSnipRect
        pendingSnipRect = null
        if (rect == null) return

        val metrics = resources.displayMetrics
        val scaleX = source.width / metrics.widthPixels.toFloat()
        val scaleY = source.height / metrics.heightPixels.toFloat()
        val left = (rect.left * scaleX).toInt().coerceIn(0, source.width - 1)
        val top = (rect.top * scaleY).toInt().coerceIn(0, source.height - 1)
        val right = (rect.right * scaleX).toInt().coerceIn(left + 1, source.width)
        val bottom = (rect.bottom * scaleY).toInt().coerceIn(top + 1, source.height)

        val cropped = runCatching {
            Bitmap.createBitmap(
                ImageUtils.toSoftwareBitmap(source),
                left,
                top,
                right - left,
                bottom - top,
            )
        }.getOrNull()
        if (recycleSource) runCatching { source.recycle() }
        onSnipConfirmed(cropped)
    }

    private fun requestCaptureThenSnip() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Toast.makeText(this, getString(R.string.asst_no_capture_support), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { projectionLauncher.launch(mgr.createScreenCaptureIntent()) }
            .onFailure {
                Toast.makeText(
                    this, getString(R.string.asst_capture_start_failed, it.message ?: ""), Toast.LENGTH_SHORT,
                ).show()
            }
    }

    /** Hide our (translucent) UI so the capture shows the app behind, grab a frame, then restore. */
    private fun captureAndSnip() {
        if (!::pill.isInitialized) return
        pill.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            scope.launch {
                val bmp = runCatching { captureManager.capture() }.getOrNull()
                if (::pill.isInitialized) {
                    pill.visibility = View.VISIBLE
                    if (bmp == null) pill.showStatus(getString(R.string.asst_capture_failed_retry)) else showSnip(bmp)
                }
            }
        }, 180L)
    }

    private fun showSnip(bitmap: Bitmap) {
        dismissSnip()
        val overlay = SnipOverlayView(
            context = this,
            source = bitmap,
            onConfirm = { cropped -> onSnipConfirmed(cropped) },
            onCancel = { dismissSnip() },
        ).also { snipOverlay = it }
        pill.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun dismissSnip() {
        snipOverlay?.let { runCatching { pill.removeView(it) } }
        snipOverlay = null
    }

    private fun onSnipConfirmed(cropped: Bitmap?) {
        dismissSnip()
        if (cropped == null) {
            pill.showStatus(getString(R.string.asst_no_region))
            return
        }
        val dataUrl = runCatching {
            ImageUtils.toDataUrl(cropped, Constants.SCREENSHOT_MAX_EDGE, Constants.SCREENSHOT_JPEG_QUALITY)
        }.getOrNull()
        runCatching { cropped.recycle() }
        if (dataUrl == null) {
            pill.showStatus(getString(R.string.asst_image_failed))
            return
        }
        attachedImages.add(dataUrl)
        // Auto-switch to 问答 and let the user ask about the cropped region (text or voice).
        pill.setAskMode()
        pill.enterTypingMode()
        refreshAttachedImages()
        pill.setInputHint(getString(R.string.asst_image_captured_hint))
    }

    /* ---------------- 相机 / 文件 attachments (from the 三条杠 menu) ---------------- */

    /** Launch the system camera to take a photo (no CAMERA permission needed) and attach it. */
    private fun launchCamera() {
        val photo = File(cacheDir, "asst_cam_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
        }.getOrNull()
        if (uri == null) { pill.showStatus(getString(R.string.asst_capture_failed_retry)); return }
        pendingCameraUri = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { pendingCameraUri = null; pill.showStatus(getString(R.string.asst_capture_failed_retry)) }
    }

    /** Decode an image [uri] (a photo) into a data URL and attach it for the next 问答 question. */
    private fun attachImageFromUri(uri: Uri) {
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) {
                runCatching {
                    decodeBoundedBitmap(uri)?.let { bmp ->
                        ImageUtils.toDataUrl(bmp, Constants.SCREENSHOT_MAX_EDGE, Constants.SCREENSHOT_JPEG_QUALITY)
                            .also { bmp.recycle() }
                    }
                }.getOrNull()
            }
            if (dataUrl == null) { pill.showStatus(getString(R.string.asst_image_failed)); return@launch }
            attachedFileName = null
            attachedFileText = null
            attachedImages.add(dataUrl)
            pill.setAskMode()
            pill.enterTypingMode()
            refreshAttachedImages()
            pill.setInputHint(getString(R.string.asst_images_attached_hint, attachedImages.size))
        }
    }

    /** Decode multiple gallery [uris] into data URLs and attach them all for the next 问答 question. */
    private fun attachImagesFromUris(uris: List<Uri>) {
        scope.launch {
            val dataUrls = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        decodeBoundedBitmap(uri)?.let { bmp ->
                            ImageUtils.toDataUrl(bmp, Constants.SCREENSHOT_MAX_EDGE, Constants.SCREENSHOT_JPEG_QUALITY)
                                .also { bmp.recycle() }
                        }
                    }.getOrNull()
                }
            }
            if (dataUrls.isEmpty()) { pill.showStatus(getString(R.string.asst_image_failed)); return@launch }
            attachedFileName = null
            attachedFileText = null
            attachedImages.addAll(dataUrls)
            pill.setAskMode()
            pill.enterTypingMode()
            refreshAttachedImages()
            pill.setInputHint(getString(R.string.asst_images_attached_hint, attachedImages.size))
        }
    }

    /** Push the current attachment list to the pill's thumbnail strip, wiring per-image removal. */
    private fun refreshAttachedImages() {
        pill.showAttachedImages(attachedImages.toList()) { index ->
            if (index in attachedImages.indices) {
                attachedImages.removeAt(index)
                refreshAttachedImages()
                if (attachedImages.isEmpty()) pill.setInputHint(getString(R.string.asst_input_hint))
            }
        }
    }

    /** Read a picked file's name and (for text-like files) a bounded slice of its content; attach it. */
    private fun attachFileFromUri(uri: Uri) {
        scope.launch {
            val (name, text) = withContext(Dispatchers.IO) { readFile(uri) }
            attachedImages.clear()
            attachedFileName = name
            attachedFileText = text
            pill.setAskMode()
            pill.enterTypingMode()
            refreshAttachedImages()
            pill.setInputHint(getString(R.string.asst_file_attached_hint, name))
        }
    }

    private fun clearAttachments() {
        attachedImages.clear()
        attachedFileName = null
        attachedFileText = null
        refreshAttachedImages()
    }

    /** Decode [uri] with an inSampleSize from its bounds so a large photo doesn't OOM. */
    private fun decodeBoundedBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > 2 * Constants.SCREENSHOT_MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Returns the file's display name and (for text-like files) a bounded prefix of its text. */
    private fun readFile(uri: Uri): Pair<String, String?> {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: getString(R.string.file_default)
        val mime = contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val textLike = mime.startsWith("text/") || mime == "application/json" ||
            mime == "application/xml" || ext in TEXT_EXTENSIONS
        val text = if (textLike) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
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
        return name to text
    }

    /** Stop every voice path (system/Vosk) and release the mic. */
    private fun stopAllVoice() {
        listening = false
        runCatching { speech?.destroy() }
        speech = null
        voskListening = false
        runCatching { vosk?.destroy() }
        vosk = null
        if (::pill.isInitialized) pill.waveform.reset()
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_FILE_CHARS = 8000
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm", "yml", "yaml",
            "ini", "conf", "properties", "gradle", "kt", "kts", "java", "py", "js", "ts", "c", "cpp",
            "h", "hpp", "cs", "go", "rs", "rb", "php", "sh", "sql",
        )
    }
}

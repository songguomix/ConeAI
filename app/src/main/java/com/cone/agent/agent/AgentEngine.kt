package com.cone.agent.agent

import android.content.Context
import com.cone.agent.R
import com.cone.agent.agent.action.ActionParser
import com.cone.agent.agent.action.StreamingPlanParser
import com.cone.agent.agent.action.ThinkEvent
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.remote.ChatChunk
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.ResolvedProvider
import com.cone.agent.data.repository.ScreenMode
import com.cone.agent.data.repository.SelectedAgentModel
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.mcp.McpManager
import com.cone.agent.vision.AccessibilityBridge
import com.cone.agent.vision.ImageUtils
import com.cone.agent.vision.OcrEngine
import com.cone.agent.vision.OcrResult
import com.cone.agent.vision.ScreenCaptureManager
import com.cone.agent.vision.ScreenSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Implements one Observe (snapshot) and one streamed Think (model call) of the agent loop. */
@Singleton
class AgentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val captureManager: ScreenCaptureManager,
    private val ocrEngine: OcrEngine,
    private val bridge: AccessibilityBridge,
    private val promptBuilder: PromptBuilder,
    private val memoryContext: MemoryContext,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val mcpManager: McpManager,
) {

    /** OBSERVE: real screenshot + OCR + accessibility UI tree + foreground package. */
    suspend fun observe(): ScreenSnapshot = coroutineScope {
        // 屏幕识别方式（见 ScreenMode）决定这一轮观察产出什么：本地 OCR 只出文字与坐标、不上传截图；
        // 云端识别不跑本地 OCR，改为上传截图（单模型直接看图，双模型交给画面理解模型描述）。
        // The chosen mode is the whole decision. Nothing here second-guesses whether the model can
        // read an image: the catalogue's capability flag comes from whatever the provider happened
        // to advertise, it is absent for hand-typed models, and overriding an explicit choice with
        // it meant a mode the user had selected silently did something else.
        val mode = settingsRepository.screenMode.first()
        val useLocalOcr = mode == ScreenMode.LOCAL_OCR
        val encodeImage = mode != ScreenMode.LOCAL_OCR
        // The accessibility tree and the screen frame are independent sources, and walking the tree is
        // a long chain of binder calls (up to MAX_NODES nodes). Dumping it in its own job means the
        // frame's OCR starts the moment the capture lands instead of queueing behind the walk — the
        // two longest local steps then overlap rather than adding up.
        // The screen-change counter is sampled with the tree so the controller can tell, mid-batch,
        // whether the screen has since changed under the plan.
        val treeDeferred = async(Dispatchers.Default) {
            Triple(bridge.dumpUiTree(), bridge.currentPackage(), bridge.screenChangeToken())
        }
        val bitmap = captureManager.capture()

        val metrics = context.resources.displayMetrics
        val realWidth = bitmap?.width ?: metrics.widthPixels
        val realHeight = bitmap?.height ?: metrics.heightPixels

        // An uploaded image is downscaled so its longest edge ≤ SCREENSHOT_MAX_EDGE, and the model
        // then reasons in that smaller "image space": we declare those dimensions to it and keep the
        // factor that maps its answers back to real screen pixels. The same value must drive the
        // scale, the dimensions we declare and the bytes we encode — if they ever disagree, every
        // coordinate the model returns lands in the wrong place.
        //
        // With no image there is no image space, so the model works in real screen pixels (scale 1).
        // OCR boxes and accessibility bounds are already real pixels, so this drops a rounding step
        // that used to cost a couple of pixels each way for nothing — the downscale only ever existed
        // to shrink an upload that no longer happens.
        val longest = maxOf(realWidth, realHeight)
        val scale = if (encodeImage && longest > Constants.SCREENSHOT_MAX_EDGE) {
            longest.toFloat() / Constants.SCREENSHOT_MAX_EDGE
        } else {
            1f
        }
        val modelWidth = (realWidth / scale).toInt().coerceAtLeast(1)
        val modelHeight = (realHeight / scale).toInt().coerceAtLeast(1)

        // The two heaviest local steps run in parallel on the same captured frame instead of
        // back-to-back. Both only read it, so sharing is safe; it is freed once both are done.
        //
        // OCR gets its own, far more generous size cap (Constants.OCR_MAX_EDGE) rather than the
        // upload one: shrinking exists to save image tokens, a budget the on-device pass doesn't
        // share, and small labels fall under ML Kit's per-character floor once shrunk — which now
        // matters directly, because these boxes drive real taps. Any scaling it does apply is undone
        // here, so the boxes leave this function in real screen pixels like the accessibility bounds.
        val ocrDeferred = async(Dispatchers.Default) {
            if (!useLocalOcr || bitmap == null) return@async OcrResult.EMPTY
            val frame = ImageUtils.downscale(bitmap, Constants.OCR_MAX_EDGE)
            val ocrScale = if (frame === bitmap) 1f else bitmap.width.toFloat() / frame.width
            try {
                val result = runCatching { ocrEngine.recognizeStructured(frame) }.getOrNull()
                    ?: return@async OcrResult.EMPTY
                if (ocrScale == 1f) {
                    result
                } else {
                    result.copy(elements = result.elements.map { it.scaledBy(ocrScale) })
                }
            } finally {
                if (frame !== bitmap) runCatching { frame.recycle() }
            }
        }
        // Local-OCR mode uploads nothing. Sending an image is what breaks a text-only agent model —
        // the very kind of model this mode exists to support — and the screen's text and coordinates
        // are already covered by the OCR pass, so the encode and the upload are both pure waste here.
        val imageDeferred = async(Dispatchers.Default) {
            if (!encodeImage) return@async null
            bitmap?.let {
                ImageUtils.toDataUrl(it, Constants.SCREENSHOT_MAX_EDGE, Constants.SCREENSHOT_JPEG_QUALITY)
            }
        }

        val (uiElements, currentPackage, screenToken) = treeDeferred.await()
        val ocr = ocrDeferred.await()
        val imageDataUrl = imageDeferred.await()
        runCatching { bitmap?.recycle() }

        ScreenSnapshot(
            width = modelWidth,
            height = modelHeight,
            scale = scale,
            ocrText = ocr.text,
            ocrElements = ocr.elements,
            uiElements = uiElements,
            currentPackage = currentPackage,
            imageDataUrl = imageDataUrl,
            hasFrame = bitmap != null,
            canAct = bridge.isReady,
            screenChangeToken = screenToken,
        )
    }

    /**
     * THINK, streamed: asks the selected model for this screen's actions and emits [ThinkEvent]s as
     * the response arrives — the thought and plan outline as soon as their fields complete, and
     * every action the moment its array element closes, so the controller can execute while the
     * model is still writing the rest. Always ends with [ThinkEvent.Completed] carrying the
     * authoritative full plan (the exact same parse the non-streaming path used).
     *
     * Providers that answer with a plain (non-SSE) completion despite `stream=true` leave the
     * stream buffer empty; those are transparently retried as a normal completion call.
     */
    fun thinkStream(
        instruction: String,
        snapshot: ScreenSnapshot,
        stepIndex: Int,
        maxSteps: Int,
        history: List<String>,
        overallPlan: List<String> = emptyList(),
        lastThought: String = "",
    ): Flow<ThinkEvent> = flow {
        val selected = settingsRepository.selectedModel.first()
            ?: throw IllegalStateException(LocaleHelper.string(context, R.string.engine_no_model))
        val provider = providerRepository.resolve(selected.providerId)
            ?: throw IllegalStateException(LocaleHelper.string(context, R.string.engine_no_provider))

        // 拼凑双模型：先让视觉模型把截图转成文字描述，再让思考模型据此决策（思考模型可为纯文本）。
        // 仅当选了该模式、已配置视觉模型、且本步确有截图时启用；任一条件不满足则回退成单模型看图。
        // 视觉调用失败或返回空描述时同样回退，而不是让整步失败计入失败次数。
        val dualMode = settingsRepository.screenMode.first() == ScreenMode.CLOUD_DUAL
        val visionSel = settingsRepository.visionModel.first()
        val described = if (dualMode && visionSel != null && snapshot.imageDataUrl != null) {
            describeScreen(visionSel, snapshot.imageDataUrl).getOrNull()?.takeIf { it.first.isNotBlank() }
        } else {
            null
        }
        val visionTokens: Int? = described?.second
        val screenDescription = described?.first

        // Dual mode reasons from the description and drops the raw image; every other path sends
        // whatever the observation produced, unfiltered.
        val imageForModel = if (screenDescription != null) null else snapshot.imageDataUrl

        // What the user has told us about themselves is as relevant to doing a task for them as to
        // answering a question: 「订个外卖」 needs the address and the allergy, 「给女儿发条消息」 needs
        // the daughter's name. Selected against the task instruction, and kept short — this prompt
        // is already carrying a screen dump.
        val memories = memoryContext.relevant(instruction, MemoryContext.AGENT_LIMIT)

        val messages = promptBuilder.buildMessages(
            instruction = instruction,
            snapshot = snapshot,
            stepIndex = stepIndex,
            maxSteps = maxSteps,
            history = history,
            memorySection = if (memories.isEmpty()) "" else memoryContext.format(memories),
            mcpSection = mcpManager.promptSection(),
            // In dual mode the thinking model reasons from the text description, not the raw image.
            imageDataUrl = imageForModel,
            screenDescription = screenDescription,
            overallPlan = overallPlan,
            lastThought = lastThought,
        )

        val parser = StreamingPlanParser()
        var streamTokens: Int? = null
        try {
            llmClient.chatStream(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = selected.modelId,
                messages = messages,
                protocol = provider.protocol,
            ).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> parser.feed(chunk.text).forEach { emit(it) }
                    is ChatChunk.Done -> streamTokens = chunk.totalTokens
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Nothing received yet → the endpoint may not stream at all; ask again without SSE.
            // A mid-stream failure (or an abort thrown by the collector) surfaces as-is instead.
            if (!parser.isEmpty) throw t
            fallbackThink(provider, selected.modelId, messages, visionTokens)
            return@flow
        }
        if (parser.isEmpty) {
            // 200 OK but no SSE data lines — a server that ignored stream=true.
            fallbackThink(provider, selected.modelId, messages, visionTokens)
            return@flow
        }
        val plan = parser.finish()
        if (!parser.thoughtEmitted && plan.thought.isNotBlank()) emit(ThinkEvent.Thought(plan.thought))
        if (!parser.planEmitted && plan.plan.isNotEmpty()) emit(ThinkEvent.PlanOutline(plan.plan))
        plan.actions.drop(parser.emittedActions).forEach { emit(ThinkEvent.Action(it)) }
        emit(ThinkEvent.Completed(plan, sumTokens(visionTokens, streamTokens)))
    }

    /** Non-streaming completion used when the provider doesn't actually support SSE. */
    private suspend fun FlowCollector<ThinkEvent>.fallbackThink(
        provider: ResolvedProvider,
        modelId: String,
        messages: List<ChatMessageDto>,
        visionTokens: Int?,
    ) {
        val result = llmClient.chat(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = modelId,
            messages = messages,
            protocol = provider.protocol,
        ).getOrThrow()
        val plan = ActionParser.parse(result.content).getOrThrow()
        if (plan.thought.isNotBlank()) emit(ThinkEvent.Thought(plan.thought))
        if (plan.plan.isNotEmpty()) emit(ThinkEvent.PlanOutline(plan.plan))
        plan.actions.forEach { emit(ThinkEvent.Action(it)) }
        emit(ThinkEvent.Completed(plan, sumTokens(visionTokens, result.totalTokens)))
    }

    /** Vision + thinking token usage combined; null only when neither call reported usage. */
    private fun sumTokens(vision: Int?, thinking: Int?): Int? = when {
        vision == null -> thinking
        thinking == null -> vision
        else -> vision + thinking
    }

    /** DESCRIBE: the 画面理解 model turns the screenshot into a textual description of the screen.
     *  Returns the description together with the vision model's token usage. */
    private suspend fun describeScreen(
        visionSel: SelectedAgentModel,
        imageDataUrl: String,
    ): Result<Pair<String, Int?>> {
        val visionProvider = providerRepository.resolve(visionSel.providerId)
            ?: return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.engine_no_vision_provider)))
        return llmClient.chat(
            baseUrl = visionProvider.baseUrl,
            apiKey = visionProvider.apiKey,
            model = visionSel.modelId,
            messages = promptBuilder.buildVisionMessages(imageDataUrl),
            protocol = visionProvider.protocol,
        ).map { it.content to it.totalTokens }
    }
}

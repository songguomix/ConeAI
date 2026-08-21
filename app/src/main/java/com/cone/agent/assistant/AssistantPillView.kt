package com.cone.agent.assistant

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cone.agent.R
import com.cone.agent.core.MarkdownInline
import com.cone.agent.ui.theme.ConeColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.cone.agent.vision.ImageDownloader

/**
 * The Gemini-style assistant popup: a white rounded pill docked at the bottom of the screen. A small
 * 执行 / 思考 segmented toggle at the top chooses whether the spoken request drives the screen agent
 * or is answered as a plain question. The middle shows a live voice waveform / transcript / answer,
 * flanked by a "+" affordance and a circular mic button. Built programmatically so it can serve as a
 * [android.service.voice.VoiceInteractionSession] content view.
 */
@SuppressLint("ViewConstructor")
class AssistantPillView(
    context: Context,
    private val onMic: () -> Unit,
    private val onStop: () -> Unit = {},
    private val onExpand: () -> Unit = {},
    private val onSnip: () -> Unit,
    private val onCamera: () -> Unit = {},
    private val onImage: () -> Unit = {},
    private val onFile: () -> Unit = {},
    private val onDismiss: () -> Unit,
    /** A region circled directly on the screen, in this view's coordinates. */
    private val onSnipRect: (RectF) -> Unit,
    private val onModeChange: (agent: Boolean) -> Unit,
    private val onSubmitText: (String) -> Unit,
    private val onKeyboard: (typing: Boolean) -> Unit,
    private val onToggleSound: () -> Unit,
    private val onToggleWebSearch: () -> Unit,
    private val onCopy: () -> Unit = {},
) : FrameLayout(context) {

    val waveform: WaveformView
    private val transcript: TextView
    private val input: EditText
    private val micButton: ImageView
    private lateinit var attachButton: ImageView
    private val keyboardButton: ImageView
    private val centerStack: FrameLayout
    private val segAgent: TextView
    private val segAsk: TextView

    // Gemini-style answer card that pops above the pill in 思考 mode.
    private val answerCard: LinearLayout
    private val answerContent: LinearLayout
    private val answerScroll: ScrollView
    // Raw answer markdown, kept for 复制; the card itself renders text + 图片 blocks from it.
    private var answerRaw: String = ""
    private var imageViewerOverlay: View? = null
    private val soundButton: ImageView
    private val copyButton: ImageView

    // Bottom-anchored stack (answer card + pill); raised above the IME via [applyBottomInset].
    private val bottomStack: LinearLayout
    private val baseBottomMarginPx: Int
    private var attachMenuPopup: PopupWindow? = null

    /** 联网搜索 now lives inside the 三条杠 menu; we track its state to render the menu item's toggle. */
    private var webSearchEnabled = false
    private var webMenuIcon: ImageView? = null

    // Horizontal strip of attached-image thumbnails, shown inside the pill above the input row.
    private val imageStrip: HorizontalScrollView
    private val imageStripContent: LinearLayout

    /** True = 执行 (drive the agent); false = 思考 (plain Q&A). Defaults to 问答 (Q&A). */
    var agentMode: Boolean = false
        private set

    /** True while the keyboard/text-input mode is active (mic off, typing instead). */
    var typing: Boolean = false
        private set

    /** True while a 思考 (Q&A) request is in flight; the round button is a stop/pause control then. */
    private var thinking: Boolean = false

    // Material 3 colors — incl. Android 12+ wallpaper dynamic color ("取色") — matching the main app.
    private val tokens = ConeColors.tokens(context)
    private val SURFACE = tokens.surface
    private val ICON = tokens.onSurface
    private val MIC_BG = tokens.primary
    private val MIC_ICON = tokens.onPrimary
    private val TOGGLE_TRACK = tokens.surfaceVariant
    private val TOGGLE_UNSELECTED = tokens.onSurfaceVariant
    private val HANDLE = tokens.outline
    private val PRIMARY = tokens.primary
    private val ON_PRIMARY = tokens.onPrimary
    private val BRAND = tokens.primary
    private val BRAND_BRIGHT = tokens.primary

    private lateinit var card: LinearLayout
    private lateinit var snipHint: TextView

    /* ---------------- 圈选：直接在屏幕上画，不用先点按钮 ---------------- */

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var snipping = false
    private var snipRect: RectF? = null
    private val snipDim = Paint().apply { color = SNIP_DIM }
    private val snipBorder = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = tokens.primary
        isAntiAlias = true
    }

    init {
        // Scrim: a tap outside the pill dismisses; a drag circles a region to ask about (see
        // [onTouchEvent]). Handled manually rather than with an OnClickListener because the two
        // gestures start identically and only diverge once the finger has moved.
        setBackgroundColor(SCRIM)

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedRect(SURFACE, dp(34))
            elevation = dp(18).toFloat()
            setPadding(dp(20), dp(12), dp(20), dp(18))
            isClickable = true // swallow taps so they don't fall through to the scrim
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(14); rightMargin = dp(14) }
        }

        // Mode toggle (执行 / 思考)
        val toggle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedRect(TOGGLE_TRACK, dp(15))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        segAgent = segment(context.getString(R.string.mode_agent)) { selectMode(agent = true) }
        segAsk = segment(context.getString(R.string.mode_ask)) { selectMode(agent = false) }
        // 问答 on the left (default), 智能体 on the right.
        toggle.addView(segAsk)
        toggle.addView(segAgent)
        card.addView(toggle)
        applyToggleStyle()

        // Drag handle — a generous, full-width touch strip with the little bar centred. Dragging it up
        // expands the assistant into the full app (see [setupDragToExpand]).
        val handleArea = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply {
                bottomMargin = dp(6)
            }
            addView(View(context).apply {
                background = roundedRect(HANDLE, dp(2))
                layoutParams = FrameLayout.LayoutParams(dp(34), dp(4), Gravity.CENTER)
            })
        }
        setupDragToExpand(handleArea)
        card.addView(handleArea)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Expandable attachment menu (三条杠): tap to choose 相机 / 文件 / 截图.
        attachButton = ImageView(context).apply {
            setImageDrawable(tinted(R.drawable.ic_assistant_menu, ICON))
            contentDescription = context.getString(R.string.cd_add_attachment)
            val s = dp(26)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(6) }
            isClickable = true
            setOnClickListener { showAttachMenu(it) }
        }

        centerStack = FrameLayout(context).apply {
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            }
        }
        waveform = WaveformView(context).apply {
            barColor = ICON
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER)
        }
        transcript = TextView(context).apply {
            setTextColor(ICON)
            textSize = 16f
            maxLines = 3
            gravity = Gravity.CENTER
            visibility = View.GONE
            movementMethod = ScrollingMovementMethod()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        // Text-input fallback for when speech recognition is unavailable / the user prefers typing.
        input = EditText(context).apply {
            setTextColor(ICON)
            setHintTextColor(TOGGLE_UNSELECTED)
            hint = context.getString(R.string.asst_input_hint)
            textSize = 16f
            maxLines = 1
            setSingleLine(true)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            visibility = View.GONE
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            )
            setOnEditorActionListener { _, actionId, event ->
                val send = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (send) { submitTyped(); true } else false
            }
        }
        centerStack.addView(waveform)
        centerStack.addView(transcript)
        centerStack.addView(input)

        keyboardButton = ImageView(context).apply {
            setImageDrawable(tinted(R.drawable.ic_assistant_keyboard, ICON))
            val s = dp(26)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(6) }
            isClickable = true
            setOnClickListener { toggleTyping() }
        }

        micButton = ImageView(context).apply {
            setImageDrawable(tinted(R.drawable.ic_assistant_mic, MIC_ICON))
            background = circle(MIC_BG)
            val s = dp(44)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s)
            isClickable = true
            // While 思考 the button stops the request; otherwise it's mic (voice) or send (typing).
            setOnClickListener {
                when {
                    thinking -> onStop()
                    typing -> submitTyped()
                    else -> onMic()
                }
            }
        }

        // 附件菜单(三条杠) on the far left, then 键盘, then the field, with mic on the right.
        // 联网搜索 has moved inside the 三条杠 menu (see [showAttachMenu]).
        row.addView(attachButton)
        row.addView(keyboardButton)
        row.addView(centerStack)
        row.addView(micButton)

        // Attached-image thumbnails: a horizontally scrollable strip shown above the input row, hidden
        // until the user attaches one or more images (相机 / 图片 / 截图).
        imageStripContent = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        imageStrip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            addView(imageStripContent)
        }
        card.addView(imageStrip)
        card.addView(row)
        // 联网搜索 only applies to 思考(Q&A) — mirror the main screen and hide it in 执行 mode.
        updateModeToolVisibility()

        // Gemini-style answer card: a separate rounded card that pops up above the pill in 思考 mode.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        header.addView(View(context).apply {
            background = gradientCircle(BRAND_BRIGHT, BRAND)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
        })
        header.addView(TextView(context).apply {
            text = "Cone"
            setTextColor(BRAND)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        // Spacer pushes the copy + mute toggles to the right edge of the answer card header.
        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })
        copyButton = ImageView(context).apply {
            setImageDrawable(tinted(R.drawable.ic_assistant_copy, ICON))
            contentDescription = context.getString(R.string.cd_copy)
            val s = dp(26)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(8) }
            isClickable = true
            setOnClickListener { copyAnswerToClipboard() }
        }
        header.addView(copyButton)
        soundButton = ImageView(context).apply {
            setImageDrawable(tinted(R.drawable.ic_assistant_sound_on, ICON))
            val s = dp(26)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(s, s)
            isClickable = true
            setOnClickListener { onToggleSound() }
        }
        header.addView(soundButton)
        // Vertical content: alternating text blocks and rendered 图片 blocks (see [renderAnswer]).
        answerContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Cap the answer height to ~42% of the screen so the card never fills it; scroll past that.
        val maxAnswerHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        answerScroll = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(maxAnswerHeight, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(answerContent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        answerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(SURFACE, dp(28))
            elevation = dp(18).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true // swallow taps so reading/scrolling the answer doesn't dismiss
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(10) }
            addView(header)
            addView(answerScroll)
        }

        baseBottomMarginPx = dp(30)
        bottomStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = baseBottomMarginPx
            }
        }
        bottomStack.addView(answerCard)
        bottomStack.addView(card)
        addView(bottomStack)

        // The circle gesture leaves no mark on screen, so it does not exist until something says so.
        // One quiet line, above the pill, gone the moment a drag starts.
        snipHint = TextView(context).apply {
            text = context.getString(R.string.asst_snip_hint)
            setTextColor(ON_PRIMARY)
            textSize = 12f
            background = roundedRect(SNIP_HINT_BG, dp(14))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            alpha = 0.9f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply { topMargin = dp(96) }
        }
        addView(snipHint)
        updateSnipHint()
    }

    /**
     * Lift the pill (and answer card) above the soft keyboard / navigation bar. The scrim keeps
     * filling the whole screen — only this bottom stack moves, so the popup rises cleanly instead of
     * the whole window jumping. [px] is the bottom inset (IME or nav bar, whichever is larger).
     */
    fun applyBottomInset(px: Int) {
        (bottomStack.layoutParams as? MarginLayoutParams)?.let {
            it.bottomMargin = baseBottomMarginPx + px
            bottomStack.layoutParams = it
        }
    }

    /**
     * Entrance animation, run once when the assistant opens. Two coordinated tracks:
     *  1. the full-screen scrim fades in (its background ColorDrawable's alpha 0 → opaque), and
     *  2. the bottom pill slides up from below the screen while fading in and easing from 95% → 100%
     *     scale (pivoted at its bottom edge, so it "grows" out of the bottom like a bottom sheet).
     * A DecelerateInterpolator makes it rush in then settle, which reads as smooth/fluid. The view is
     * launched with FLAG_ACTIVITY_NO_ANIMATION, so this *is* the entrance — no window animation fights it.
     */
    fun animateIn() {
        // Hide everything synchronously so the very first frame is blank (no pre-animation flash).
        val scrim = background?.mutate()
        scrim?.alpha = 0
        bottomStack.alpha = 0f
        bottomStack.post {
            val margin = (bottomStack.layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0
            bottomStack.pivotX = bottomStack.width / 2f
            bottomStack.pivotY = bottomStack.height.toFloat() // scale grows from the bottom edge
            bottomStack.translationY = (bottomStack.height + margin).toFloat() // start fully off-screen
            bottomStack.scaleX = 0.95f
            bottomStack.scaleY = 0.95f

            scrim?.let { drawable ->
                ValueAnimator.ofInt(0, 255).apply {
                    duration = 200
                    addUpdateListener { drawable.alpha = it.animatedValue as Int }
                    start()
                }
            }
            bottomStack.animate()
                .translationY(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(340)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }
    }

    /**
     * "Pull up to open the app": the handle recognises an upward drag (past a distance threshold or a
     * quick upward fling) and hands off to the full app via [onExpand]. The pill itself stays put — it
     * does NOT follow the finger up — and the hand-off is a plain fade (no upward slide).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragToExpand(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = ev.rawY
                    dragging = false
                    tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Recognise the upward drag, but deliberately do NOT move the pill with the finger.
                    tracker?.addMovement(ev)
                    if (!dragging && (downRawY - ev.rawY) > slop) dragging = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    var flungUp = false
                    tracker?.apply {
                        addMovement(ev)
                        computeCurrentVelocity(1000)
                        if (yVelocity < -800f) flungUp = true
                        recycle()
                    }
                    tracker = null
                    val liftedBy = downRawY - ev.rawY
                    if (dragging && (liftedBy > dp(56) || flungUp)) playExpandAnimation()
                    true
                }
                else -> false
            }
        }
    }

    /** Hand off to the full app with a plain fade — the pill does not slide up. */
    private fun playExpandAnimation() {
        bottomStack.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { onExpand() }
            .start()
    }

    /** Listening state: show the breathing waveform, hide any transcript. */
    fun showListening() {
        hideAnswer()
        input.visibility = View.GONE
        transcript.maxLines = 3
        transcript.visibility = View.GONE
        waveform.visibility = View.VISIBLE
    }

    /** Show recognized text (partial or final) in place of the waveform. */
    fun showTranscript(text: String) {
        input.visibility = View.GONE
        transcript.maxLines = 3
        transcript.text = text
        transcript.visibility = View.VISIBLE
        waveform.visibility = View.INVISIBLE
    }

    /** Show a short status / error message. */
    fun showStatus(text: String) = showTranscript(text)

    /* ---------------- 思考-mode answer card (Gemini style) ---------------- */

    /** Show the "thinking…" state in the answer card; the round button becomes a stop/pause control. */
    fun showThinking() {
        thinking = true
        answerRaw = ""
        answerContent.removeAllViews()
        answerContent.addView(makeAnswerTextView(context.getString(R.string.asst_thinking)))
        answerCard.visibility = View.VISIBLE
        micButton.setImageDrawable(tinted(R.drawable.ic_fc_pause, MIC_ICON))
    }

    /* ---------------- 智能体任务卡片（接口任务不退窗时的实时状态） ---------------- */

    private var agentStatusView: TextView? = null
    private var agentDetailView: TextView? = null

    /**
     * Live agent-task card: a bold header, the current action, and the latest thought — updated in
     * place on every state emission instead of rebuilding the card, so fast streamed updates don't
     * flicker. Any other card call (showAnswer/showThinking) naturally replaces it; the stale child
     * references are detected via [View.getParent] and the card is rebuilt on the next update.
     */
    fun showAgentTask(action: String, thought: String) {
        if (agentStatusView?.parent == null) {
            thinking = true
            answerRaw = ""
            answerContent.removeAllViews()
            answerContent.addView(TextView(context).apply {
                text = context.getString(R.string.asst_agent_task_title)
                setTextColor(PRIMARY)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            })
            agentStatusView = makeAnswerTextView("").also { answerContent.addView(it) }
            agentDetailView = makeAnswerTextView("").apply {
                textSize = 13f
                alpha = 0.65f
            }.also { answerContent.addView(it) }
            answerCard.visibility = View.VISIBLE
            micButton.setImageDrawable(tinted(R.drawable.ic_fc_pause, MIC_ICON))
        }
        agentStatusView?.text = action
        agentDetailView?.apply {
            text = thought
            visibility = if (thought.isBlank()) View.GONE else View.VISIBLE
        }
    }

    /** "联网搜索中…" state, shown in the answer card while the grounding search runs. */
    fun showSearching() {
        answerRaw = ""
        answerContent.removeAllViews()
        answerContent.addView(makeAnswerTextView(context.getString(R.string.web_searching)))
        answerCard.visibility = View.VISIBLE
    }

    /**
     * Show a (possibly long) model answer in the Gemini-style card above the pill. [sources] (encoded
     * "title\turl" lines from 联网搜索) are rendered as a tappable 参考来源 card under the answer.
     */
    fun showAnswer(text: String, sources: String? = null) {
        thinking = false
        restoreActionButton()
        renderAnswer(text)
        sources?.takeIf { it.isNotBlank() }?.let { answerContent.addView(makeSourcesCard(it)) }
        answerCard.visibility = View.VISIBLE
        answerScroll.post { answerScroll.scrollTo(0, 0) }
    }

    /** Grok-style 参考来源 card for the pill: header + numbered rows that open each source URL. */
    private fun makeSourcesCard(encoded: String): View {
        val sources = encoded.split("\n").mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            val url = parts.getOrNull(1)?.trim().orEmpty()
            if (url.isBlank()) null else parts[0].trim() to url
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(TOGGLE_TRACK, dp(12))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        card.addView(TextView(context).apply {
            text = "${context.getString(R.string.sources_title)} · ${sources.size}"
            setTextColor(PRIMARY)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        sources.forEachIndexed { index, (title, url) ->
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
                isClickable = true
                setOnClickListener {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                addView(TextView(context).apply {
                    text = "[${index + 1}] ${title.ifBlank { url }}"
                    setTextColor(ICON)
                    textSize = 14f
                    maxLines = 2
                })
                addView(TextView(context).apply {
                    text = hostOf(url)
                    setTextColor(TOGGLE_UNSELECTED)
                    textSize = 12f
                    maxLines = 1
                })
            })
        }
        return card
    }

    /** The bare host shown under each source row in the pill (e.g. "en.wikipedia.org"). */
    private fun hostOf(url: String): String = runCatching {
        url.substringAfter("://").substringBefore('/').removePrefix("www.")
    }.getOrDefault(url)

    /**
     * Renders the answer with the app's shared Compose Markdown renderer inside a [ComposeView] —
     * the pill therefore shows the exact same formatting as the main chat: headings, bold/italic,
     * lists, code blocks, tables, clickable links, `![alt](url)` images (with the 查看/下载 viewer)
     * and LaTeX→Unicode math normalization. 复制 and TTS keep using the raw [answerRaw].
     */
    private fun renderAnswer(text: String) {
        answerRaw = text
        answerContent.removeAllViews()
        answerContent.addView(
            androidx.compose.ui.platform.ComposeView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setContent {
                    com.cone.agent.ui.theme.ConeAgentTheme {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            com.cone.agent.ui.components.MarkdownText(markdown = text)
                        }
                    }
                }
            },
        )
    }

    private fun makeAnswerTextView(text: String) = TextView(context).apply {
        setTextColor(ICON)
        textSize = 16f
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextIsSelectable(true)
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2); bottomMargin = dp(2) }
    }

    /**
     * One 图片 block: a rounded frame showing a "生成中…" spinner until the image loads (then the
     * image), or 加载失败 on error. Tapping it opens a full-screen viewer with 下载/关闭.
     */
    private fun makeAnswerImage(url: String, alt: String): View {
        val frame = FrameLayout(context).apply {
            background = roundedRect(TOGGLE_TRACK, dp(12))
            clipToOutline = true
            isClickable = true
            minimumHeight = dp(120)
            setOnClickListener { openImageViewer(url) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val placeholder = loadingRow(context.getString(R.string.img_generating))
        frame.addView(image)
        frame.addView(placeholder)
        loadImageInto(url, image, placeholder, alt)
        return frame
    }

    /** A centered [ProgressBar] + label row used as the 生成中 / 加载失败 placeholder inside an image frame. */
    private fun loadingRow(label: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(40), dp(12), dp(40))
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            val s = dp(18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(8) }
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(TOGGLE_UNSELECTED)
            textSize = 14f
        })
    }

    /** Loads [url] into [image] via Coil; hides [placeholder] on success or shows 加载失败 on error. */
    private fun loadImageInto(url: String, image: ImageView, placeholder: LinearLayout, alt: String) {
        val request = ImageRequest.Builder(context)
            .data(ImageDownloader.coilModel(url))
            .target(image)
            .listener(object : ImageRequest.Listener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    placeholder.visibility = View.GONE
                }

                override fun onError(request: ImageRequest, result: ErrorResult) {
                    (placeholder.getChildAt(0) as? ProgressBar)?.visibility = View.GONE
                    (placeholder.getChildAt(1) as? TextView)?.text =
                        alt.ifBlank { context.getString(R.string.img_load_failed) }
                }
            })
            .build()
        context.imageLoader.enqueue(request)
    }

    /** Full-screen 图片 viewer overlaid on the pill: tap-outside / 关闭 to dismiss, 下载 to save. */
    private fun openImageViewer(url: String) {
        dismissImageViewer()
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true
            setOnClickListener { dismissImageViewer() }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        overlay.addView(ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply { setMargins(dp(16), dp(48), dp(16), dp(96)) }
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(ImageDownloader.coilModel(url)).target(this).build(),
            )
        })
        overlay.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(40))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            addView(viewerButton(context.getString(R.string.img_download), PRIMARY, ON_PRIMARY) {
                ImageDownloader.save(context, url)
            })
            addView(View(context), LinearLayout.LayoutParams(dp(14), 1))
            addView(viewerButton(context.getString(R.string.cd_close), 0x33FFFFFF, Color.WHITE) {
                dismissImageViewer()
            })
        })
        imageViewerOverlay = overlay
        addView(overlay)
    }

    private fun dismissImageViewer() {
        imageViewerOverlay?.let { runCatching { removeView(it) } }
        imageViewerOverlay = null
    }

    private fun viewerButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(fg)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(12), dp(22), dp(12))
        background = roundedRect(bg, dp(24))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Restore the round button's icon to "send" (typing mode) or "mic" (voice mode). */
    private fun restoreActionButton() {
        val icon = if (typing) R.drawable.ic_assistant_send else R.drawable.ic_assistant_mic
        micButton.setImageDrawable(tinted(icon, MIC_ICON))
    }

    /** Hide the answer card (e.g. when a new request begins). */
    fun hideAnswer() {
        answerCard.visibility = View.GONE
    }

    /** Reflect whether 联网搜索 is on; if the 三条杠 menu is open, restyle its toggle item live. */
    fun setWebSearchEnabled(enabled: Boolean) {
        webSearchEnabled = enabled
        webMenuIcon?.let { styleWebMenuIcon(it, enabled) }
    }

    /** A filled primary chip (on) vs a plain surface chip (off) for the 联网搜索 menu toggle. */
    private fun styleWebMenuIcon(icon: ImageView, enabled: Boolean) {
        icon.background = circle(if (enabled) PRIMARY else TOGGLE_TRACK)
        icon.setImageDrawable(tinted(R.drawable.ic_assistant_web, if (enabled) ON_PRIMARY else PRIMARY))
    }

    /** Reflect whether spoken replies are on; updates the speaker / muted icon in the answer header. */
    fun setSoundEnabled(enabled: Boolean) {
        soundButton.setImageDrawable(
            tinted(
                if (enabled) R.drawable.ic_assistant_sound_on else R.drawable.ic_assistant_sound_off,
                ICON,
            ),
        )
    }

    /* ---------------- keyboard / typing mode ---------------- */

    private fun toggleTyping() {
        if (typing) exitTypingMode() else enterTypingMode()
    }

    /** Switch to text input: hide the waveform/transcript, show the field and raise the keyboard. */
    fun enterTypingMode() {
        if (typing) { focusInput(); return }
        typing = true
        hideAnswer()
        waveform.visibility = View.GONE
        transcript.visibility = View.GONE
        input.visibility = View.VISIBLE
        // The round button becomes "send"; the toggle now offers a way back to voice.
        micButton.setImageDrawable(tinted(R.drawable.ic_assistant_send, MIC_ICON))
        keyboardButton.setImageDrawable(tinted(R.drawable.ic_assistant_mic, ICON))
        focusInput()
        onKeyboard(true)
    }

    /** Switch back to voice input. */
    fun exitTypingMode() {
        if (!typing) return
        typing = false
        hideIme()
        input.setText("")
        input.visibility = View.GONE
        micButton.setImageDrawable(tinted(R.drawable.ic_assistant_mic, MIC_ICON))
        keyboardButton.setImageDrawable(tinted(R.drawable.ic_assistant_keyboard, ICON))
        showListening()
        onKeyboard(false)
    }

    /**
     * Speech finished: switch to the editable field pre-filled with the recognized [text] so the user
     * can review / correct it and explicitly send (rather than auto-sending a possibly-wrong result).
     */
    fun fillRecognizedText(text: String) {
        enterTypingMode()
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
    }

    /** Replace the input field's placeholder (e.g. to explain the mic isn't available). */
    fun setInputHint(text: String) {
        input.hint = text
    }

    /**
     * Render thumbnails for the currently attached images (or hide the strip when empty). Each has an
     * ✕ that calls [onRemove] with that image's index, so the user can see and manage what they're
     * about to send.
     */
    fun showAttachedImages(dataUrls: List<String>, onRemove: (Int) -> Unit) {
        imageStripContent.removeAllViews()
        if (dataUrls.isEmpty()) {
            imageStrip.visibility = View.GONE
            updateSnipHint()
            return
        }
        val size = dp(56)
        dataUrls.forEachIndexed { index, dataUrl ->
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(8) }
            }
            frame.addView(ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedRect(TOGGLE_TRACK, dp(10))
                clipToOutline = true
                setImageBitmap(decodeThumb(dataUrl, size))
            })
            frame.addView(TextView(context).apply {
                text = "✕"
                setTextColor(ON_PRIMARY)
                textSize = 10f
                gravity = Gravity.CENTER
                background = circle(MIC_BG)
                contentDescription = context.getString(R.string.cd_remove_image)
                val s = dp(18)
                layoutParams = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END)
                isClickable = true
                setOnClickListener { onRemove(index) }
            })
            imageStripContent.addView(frame)
        }
        imageStrip.visibility = View.VISIBLE
        updateSnipHint()
    }

    /** Decode a `data:image/...;base64,...` URL into a small bitmap sized for a thumbnail. */
    private fun decodeThumb(dataUrl: String, target: Int): android.graphics.Bitmap? = runCatching {
        val bytes = Base64.decode(dataUrl.substringAfter("base64,", ""), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > target * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun submitTyped() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        hideIme()
        input.setText("")
        onSubmitText(text)
    }

    private fun focusInput() {
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Programmatically switch to 问答 (Q&A) mode, e.g. after cropping a region to ask about. */
    fun setAskMode() = selectMode(agent = false)

    private fun selectMode(agent: Boolean) {
        if (agentMode == agent) return
        agentMode = agent
        applyToggleStyle()
        updateModeToolVisibility()
        onModeChange(agent)
    }

    /** Upload/search tools only belong to 问答; 智能体 mode should stay focused on task execution. */
    private fun updateModeToolVisibility() {
        // The 三条杠 menu (and the 联网搜索 toggle inside it) only applies to 问答.
        attachButton.visibility = if (agentMode) View.GONE else View.VISIBLE
        if (agentMode) attachMenuPopup?.dismiss()
        if (::snipHint.isInitialized) updateSnipHint()
    }

    /** Expand the attachment menu: 相机 / 图片 / 文件 / 截图 / 联网搜索, routed to the host's handlers. */
    private fun showAttachMenu(anchor: View) {
        if (agentMode) return
        attachMenuPopup?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }

        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRectWithStroke(SURFACE, dp(22), HANDLE, 1)
            elevation = dp(18).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
        }
        menu.addView(attachMenuItem(R.drawable.ic_assistant_camera, context.getString(R.string.asst_attach_camera)) {
            attachMenuPopup?.dismiss()
            onCamera()
        })
        menu.addView(attachMenuItem(R.drawable.ic_assistant_image, context.getString(R.string.asst_attach_image)) {
            attachMenuPopup?.dismiss()
            onImage()
        })
        menu.addView(attachMenuItem(R.drawable.ic_assistant_file, context.getString(R.string.asst_attach_file)) {
            attachMenuPopup?.dismiss()
            onFile()
        })
        menu.addView(attachMenuItem(R.drawable.ic_assistant_screenshot, context.getString(R.string.asst_attach_screenshot)) {
            attachMenuPopup?.dismiss()
            onSnip()
        })
        // 联网搜索已改为每次对话自动触发，隐藏手动开关

        attachMenuPopup = PopupWindow(
            menu,
            dp(168),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(16).toFloat()
            setOnDismissListener { attachMenuPopup = null; webMenuIcon = null }
            // Anchored above the 三条杠; offset grows with the extra 联网搜索 row so it clears the pill.
            showAsDropDown(anchor, -dp(12), -dp(234))
        }
    }

    /** The 联网搜索 row inside the 三条杠 menu — a toggle whose chip fills primary when active. */
    private fun webSearchMenuItem(): View {
        val icon = ImageView(context).apply {
            val s = dp(34)
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(10) }
        }
        styleWebMenuIcon(icon, webSearchEnabled)
        webMenuIcon = icon
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(Color.TRANSPARENT, dp(16))
            setPadding(dp(8), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onToggleWebSearch() } // host flips state, then calls setWebSearchEnabled
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
            addView(icon)
            addView(TextView(context).apply {
                text = context.getString(R.string.cd_web_search)
                setTextColor(ICON)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun attachMenuItem(iconRes: Int, label: String, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(Color.TRANSPARENT, dp(16))
            setPadding(dp(8), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46),
            )
            addView(ImageView(context).apply {
                setImageDrawable(tinted(iconRes, PRIMARY))
                background = circle(TOGGLE_TRACK)
                val s = dp(34)
                val p = dp(8)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(10) }
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(ICON)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

    /** Copy the current answer to the clipboard and notify the host (for a toast). */
    private fun copyAnswerToClipboard() {
        val text = answerRaw
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ConeAI", text))
        onCopy()
    }

    private fun applyToggleStyle() {
        styleSegment(segAgent, selected = agentMode)
        styleSegment(segAsk, selected = !agentMode)
    }

    private fun styleSegment(seg: TextView, selected: Boolean) {
        // MD3 segmented look: the selected segment is a filled primary chip with on-primary text.
        seg.background = if (selected) roundedRect(PRIMARY, dp(13)) else null
        seg.setTextColor(if (selected) ON_PRIMARY else TOGGLE_UNSELECTED)
        seg.setTypeface(seg.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun segment(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(6), dp(18), dp(6))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tinted(resId: Int, color: Int) =
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply { setTint(color) }

    private fun roundedRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun roundedRectWithStroke(color: Int, radius: Int, strokeColor: Int, strokeDp: Int) =
        roundedRect(color, radius).apply { setStroke(dp(strokeDp), strokeColor) }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun gradientCircle(top: Int, bottom: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(top, bottom),
    ).apply { shape = GradientDrawable.OVAL }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Tap outside the pill dismisses; drag outside it circles a region of the screen to ask about.
     *
     * Gemini's "circle to search" gesture, and the reason the 截图 menu item is no longer the only way
     * in: by the time you have opened the menu and tapped 截图, you have spent three taps reaching a
     * thing that was already on screen. Here the first drag *is* the request. A plain tap still just
     * dismisses, so nothing that worked before stops working.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                snipping = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!snipping && snipAvailable() && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    beginSnip()
                }
                if (snipping) {
                    snipRect = RectF(
                        min(downX, event.x),
                        min(downY, event.y),
                        max(downX, event.x),
                        max(downY, event.y),
                    )
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val rect = snipRect
                if (snipping) {
                    endSnip()
                    // Too small to be a deliberate selection — a slip, not a request.
                    if (rect != null && rect.width() > minSnipPx() && rect.height() > minSnipPx()) {
                        onSnipRect(rect)
                    }
                } else {
                    onDismiss()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (snipping) endSnip()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 圈选 attaches an image to a 问答 turn, which 智能体 mode has no use for. */
    private fun snipAvailable(): Boolean = !agentMode

    /**
     * Clears our own chrome for the duration of the drag: the scrim goes and the pill hides, so the
     * user draws over the real screen and sees exactly what they are selecting — and the frame
     * captured on release contains none of our UI.
     */
    private fun beginSnip() {
        snipping = true
        setBackgroundColor(Color.TRANSPARENT)
        card.visibility = View.INVISIBLE
        snipHint.visibility = View.GONE
    }

    private fun endSnip() {
        snipping = false
        snipRect = null
        setBackgroundColor(SCRIM)
        card.visibility = View.VISIBLE
        updateSnipHint()
        invalidate()
    }

    /** Hides everything of ours so a screenshot taken right now shows only the app behind. */
    fun hideForCapture() {
        setBackgroundColor(Color.TRANSPARENT)
        card.visibility = View.INVISIBLE
        snipHint.visibility = View.GONE
        snipRect = null
        invalidate()
    }

    fun showAfterCapture() {
        setBackgroundColor(SCRIM)
        card.visibility = View.VISIBLE
        updateSnipHint()
        invalidate()
    }

    /** Once an image is attached the hint has done its job and only takes up screen. */
    private fun updateSnipHint() {
        val show = snipAvailable() && !snipping && imageStripContent.childCount == 0
        snipHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun minSnipPx(): Float = dp(24).toFloat()

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val r = snipRect ?: return
        // Dim everything except the selection: four bands around it rather than a cleared layer, so
        // there is no offscreen buffer allocated on every drag frame.
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, snipDim)
        canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), snipDim)
        canvas.drawRect(0f, r.top, r.left, r.bottom, snipDim)
        canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, snipDim)
        canvas.drawRoundRect(r, dp(6).toFloat(), dp(6).toFloat(), snipBorder)
    }

    private companion object {
        // Semi-transparent scrim behind the pill (fixed; not part of the dynamic palette).
        const val SCRIM = 0x66000000
        const val SNIP_HINT_BG = 0xB3000000.toInt()

        /** Heavier than the scrim: while circling, the unselected screen recedes. */
        val SNIP_DIM = 0x99000000.toInt()
        const val MENU_CAMERA = 1
        const val MENU_FILE = 2
        const val MENU_SNIP = 3
    }
}

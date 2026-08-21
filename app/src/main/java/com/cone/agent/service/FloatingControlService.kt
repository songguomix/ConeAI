package com.cone.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.cone.agent.R
import com.cone.agent.agent.AgentController
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.domain.model.TaskStatus
import com.cone.agent.ui.theme.ConeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * The floating control center (Material You / Gemini styled) shown while the agent runs. Supports
 * dragging, folding/expanding, live status, plan inspection, pause/resume, take-over, stop and the
 * inline high-risk confirmation prompt.
 */
@AndroidEntryPoint
class FloatingControlService : Service() {

    @Inject lateinit var controller: AgentController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null

    // Views we update on state changes.
    private lateinit var collapsedView: TextView
    private lateinit var expandedView: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var pauseButton: ImageView
    private lateinit var confirmRow: LinearLayout
    private lateinit var planRow: LinearLayout

    private var expanded = true

    // Last-rendered dot status / pause state, so a busy stream of status updates only rebuilds the
    // (relatively expensive) dot drawable and pause icon when they actually change — keeping the
    // status *text* repaint immediate instead of stuck behind per-frame drawable work.
    private var lastStatus: TaskStatus? = null
    private var lastPaused: Boolean? = null

    // Material 3 colors — incl. Android 12+ wallpaper dynamic color ("取色") — matching the main app.
    private val tokens by lazy { ConeColors.tokens(this) }
    private fun translucent(color: Int, alpha: Int) = (alpha shl 24) or (color and 0x00FFFFFF)

    private val params by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!canOverlay()) {
            stopSelf()
            return
        }
        rootView = buildView().also { windowManager.addView(it, params) }
        observeState()
    }

    private fun observeState() {
        // Plain collect (not collectLatest): render is a synchronous UI update, so we want every
        // status delivered in order without the per-emit coroutine cancel/relaunch collectLatest does
        // — that overhead was showing up as laggy text under the fast streamed action updates.
        scope.launch {
            controller.state.collect { state -> render(state) }
        }
    }

    private fun render(state: com.cone.agent.agent.AgentRuntimeState) {
        // Text first and unconditionally — this is what the user watches, so keep it immediate.
        statusText.text = state.currentAction
            .ifBlank { state.currentThought }
            .ifBlank {
                if (state.maxSteps >= Constants.UNLIMITED_MAX_STEPS) {
                    str(R.string.agent_step_format_unlimited, statusLabel(state.status), state.displayStep)
                } else {
                    str(R.string.agent_step_format, statusLabel(state.status), state.displayStep, state.maxSteps)
                }
            }

        // Dot colour + pause icon change rarely; rebuild them only on an actual change so a burst of
        // status updates doesn't re-decode a drawable every frame and delay the text repaint above.
        if (state.status != lastStatus) {
            lastStatus = state.status
            statusDot.background = circle(statusColor(state.status))
        }
        if (state.isPaused != lastPaused) {
            lastPaused = state.isPaused
            pauseButton.setImageResource(if (state.isPaused) R.drawable.ic_fc_play else R.drawable.ic_fc_pause)
            pauseButton.setColorFilter(tokens.onSurfaceVariant)
        }

        val pending = state.pending
        confirmRow.visibility = if (pending != null) View.VISIBLE else View.GONE
        // 计划模式：任务可能是在主界面之外（如语音助手）发起的，计划确认必须在悬浮窗上也能完成。
        val planPending = state.pendingPlan != null
        planRow.visibility = if (planPending) View.VISIBLE else View.GONE
        // A confirmation must be visible even if the user had collapsed the pill to the ball.
        if ((pending != null || planPending) && !expanded) toggleExpanded()
    }

    /* ----------------------- view construction ----------------------- */

    private fun buildView(): View {
        val container = FrameLayout(this)

        // Collapsed: a small floating ball.
        collapsedView = TextView(this).apply {
            text = "AI"
            setTextColor(tokens.onPrimary)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val size = dp(44)
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = circle(tokens.primary)
            elevation = dp(8).toFloat()
            visibility = View.GONE
            attachDrag(this) { toggleExpanded() }
        }

        // Expanded: a compact single-row status pill (dot · action · pause · stop), Super Xiao Ai style.
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card()
            setPadding(dp(12), dp(6), dp(7), dp(6))
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        statusDot = View(this).apply {
            background = circle(tokens.primary)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
        }
        statusText = TextView(this).apply {
            text = str(R.string.status_idle)
            setTextColor(tokens.onSurface)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(124)
        }
        val takeOverButton = iconButton(R.drawable.ic_fc_handoff) { onTakeOver() }
        (takeOverButton.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
        pauseButton = iconButton(R.drawable.ic_fc_pause) { onPauseResume() }
        (pauseButton.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(4)
        val stopButton = iconButton(R.drawable.ic_fc_stop) { onStop() }
        (stopButton.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(4)

        pill.addView(statusDot)
        pill.addView(statusText)
        pill.addView(takeOverButton)
        pill.addView(pauseButton)
        pill.addView(stopButton)
        // Drag to move; a tap on the dot/text area collapses to the ball (buttons handle their own taps).
        attachDrag(pill) { toggleExpanded() }

        // High-risk confirmation: a compact extra row, shown only when pending.
        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), 0)
            visibility = View.GONE
        }
        val approve = button(str(R.string.confirm_approve), tokens.error, tokens.onError) { controller.confirm(true) }
        val reject = button(str(R.string.confirm_reject), tokens.surfaceVariant, tokens.onSurfaceVariant) { controller.confirm(false) }
        confirmRow.addView(approve, rowParams())
        confirmRow.addView(spacer())
        confirmRow.addView(reject, rowParams())

        // 计划模式确认：与高风险确认同样的紧凑按钮行（完整计划在主界面卡片里查看）。
        planRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), 0)
            visibility = View.GONE
        }
        val planStart = button(str(R.string.plan_confirm_start), tokens.primary, tokens.onPrimary) { controller.confirmPlan(true) }
        val planCancel = button(str(R.string.plan_confirm_cancel), tokens.surfaceVariant, tokens.onSurfaceVariant) { controller.confirmPlan(false) }
        planRow.addView(planStart, rowParams())
        planRow.addView(spacer())
        planRow.addView(planCancel, rowParams())

        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        expandedView.addView(pill)
        expandedView.addView(confirmRow)
        expandedView.addView(planRow)

        container.addView(expandedView)
        container.addView(collapsedView)
        return container
    }

    private fun onPauseResume() {
        if (controller.state.value.isPaused) controller.resume() else controller.pause()
    }

    /**
     * Explicit "take over": unlike a plain pause, this tells the agent the user is about to operate
     * the screen themselves, so on 继续 it re-observes instead of replaying a now-stale action.
     */
    private fun onTakeOver() {
        controller.takeOver()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        expandedView.visibility = if (expanded) View.VISIBLE else View.GONE
        collapsedView.visibility = if (expanded) View.GONE else View.VISIBLE
    }

    private fun iconButton(resId: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(resId)
        setColorFilter(tokens.onSurfaceVariant)
        background = circle(tokens.surfaceVariant)
        val size = dp(34)
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(size, size)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun onStop() {
        controller.stop()
        stopSelf()
    }

    /* ----------------------- helpers ----------------------- */

    private fun button(label: String, color: Int, textColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = roundedRect(color, dp(22))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun rowParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(8), 1)
    }

    private fun attachDrag(view: View, onClick: () -> Unit) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    rootView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick()
                    true
                }
                else -> false
            }
        }
    }

    /** Resolve a string in the user-selected app language (this floating UI isn't in Compose). */
    private fun str(@StringRes id: Int, vararg args: Any): String = LocaleHelper.string(this, id, *args)

    private fun statusLabel(status: TaskStatus): String = str(
        when (status) {
            TaskStatus.RUNNING -> R.string.status_running
            TaskStatus.OBSERVING -> R.string.status_observing
            TaskStatus.PAUSED -> R.string.status_paused
            TaskStatus.WAITING_CONFIRM -> R.string.status_waiting
            TaskStatus.SUCCEEDED -> R.string.status_succeeded
            TaskStatus.FAILED -> R.string.status_failed
            TaskStatus.CANCELLED -> R.string.status_cancelled
            TaskStatus.IDLE -> R.string.status_idle
        },
    )

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun roundedRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    /** The expanded panel background: a slightly translucent surface with a subtle hairline border. */
    private fun card() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        setColor(translucent(tokens.surface, 0xF2))
        setStroke(dp(1), translucent(tokens.outline, 0x40))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun statusColor(status: TaskStatus): Int = when (status) {
        TaskStatus.RUNNING, TaskStatus.OBSERVING, TaskStatus.SUCCEEDED -> tokens.primary
        TaskStatus.PAUSED, TaskStatus.WAITING_CONFIRM -> tokens.primary
        TaskStatus.FAILED, TaskStatus.CANCELLED -> tokens.error
        TaskStatus.IDLE -> tokens.onSurfaceVariant
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, FloatingControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }
}

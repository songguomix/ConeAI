package com.cone.agent.vision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * The agent's hands and structured eyes: dispatches gestures, reads the live UI tree, performs
 * global navigation, and detects when the *user* touches the screen so the agent can yield.
 */
@AndroidEntryPoint
class ConeAccessibilityService : AccessibilityService() {

    @Inject lateinit var bridge: AccessibilityBridge

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Monotonic counter bumped whenever a new window/activity/dialog/popup takes the screen. The
     * agent samples it at observe time; if it advances while a batch of actions is still running,
     * the screen has changed under the plan, so the agent re-observes instead of tapping a target
     * that's no longer there.
     */
    @Volatile private var screenChangeGeneration = 0L
    @Volatile private var lastScreenChangeAt = 0L

    fun screenChangeToken(): Long = screenChangeGeneration

    /** [android.os.SystemClock.uptimeMillis] of the last major screen transition (0 if none yet). */
    fun screenChangeAtMs(): Long = lastScreenChangeAt

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        bridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.detach(this)
        super.onDestroy()
    }

    override fun onInterrupt() { /* no-op */ }

    // The agent never auto-pauses on user touches: pause/take-over is driven solely by the floating
    // control's buttons — so we don't treat touches as a yield signal here. We do, however, note
    // major screen transitions (a new activity / dialog / popup / keyboard) so a running batch of
    // actions can bail out and re-observe when the screen changes under it. Content-changed events
    // are intentionally ignored (far too noisy — they'd break smooth same-screen multi-tap batches).
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            screenChangeGeneration++
            lastScreenChangeAt = android.os.SystemClock.uptimeMillis()
        }
    }

    /* ---------- gestures ---------- */

    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        return dispatch(gesture)
    }

    suspend fun doubleTap(x: Int, y: Int): Boolean {
        val first = tap(x, y)
        val second = tap(x, y)
        return first && second
    }

    suspend fun longPress(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 650L))
            .build()
        return dispatch(gesture)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val duration = durationMs.coerceIn(50L, 5000L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        return dispatch(gesture)
    }

    suspend fun inputText(text: String, x: Int?, y: Int?): Boolean {
        // Step 1: stage the text on the clipboard (for the PASTE path; SET_TEXT is the fallback).
        val onClipboard = copyToClipboard(text)
        delay(120) // let the clipboard propagate before we paste
        // Step 2: tap the field the model pointed at so it gains focus + the IME opens.
        if (x != null && y != null) {
            tap(x, y)
            delay(250)
        }
        // Step 3: locate the actual input node. Many chat apps use custom views that don't report
        // isEditable, so we use a broadened check, hit-test the tapped point, and search every window.
        val node = resolveEditTarget(x, y) ?: return false
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        // Step 4: fill — paste first, then fall back to ACTION_SET_TEXT for apps that ignore paste.
        if (onClipboard && pasteInto(node)) return true
        return setTextOn(node, text)
    }

    /**
     * Resolves the text field to fill, most-specific first:
     *  1. the input that currently holds focus (broadened editable check);
     *  2. the editable node directly under the tapped point;
     *  3. the first editable-looking node in the active window;
     *  4. the same search across every interactive window (some apps host the box in its own window).
     */
    private fun resolveEditTarget(x: Int?, y: Int?): AccessibilityNodeInfo? {
        findFocusedEditable()?.let { return it }
        if (x != null && y != null) editableAt(rootInActiveWindow, x, y)?.let { return it }
        firstEditable(rootInActiveWindow)?.let { return it }
        for (window in windows) {
            val root = window.root ?: continue
            if (x != null && y != null) editableAt(root, x, y)?.let { return it }
            firstEditable(root)?.let { return it }
        }
        return null
    }

    /**
     * Whether a node accepts typed text. `isEditable` covers standard EditTexts; the rest catch the
     * custom input views common in messaging apps (which often leave isEditable false but still expose
     * an EditText-ish class name or the SET_TEXT action).
     */
    private fun AccessibilityNodeInfo.looksEditable(): Boolean {
        if (isEditable) return true
        val cn = className?.toString().orEmpty()
        if (cn.contains("EditText", ignoreCase = true) ||
            cn.contains("AutoComplete", ignoreCase = true) ||
            cn.contains("SearchView", ignoreCase = true) ||
            cn.contains("TextField", ignoreCase = true)
        ) {
            return true
        }
        return actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id }
    }

    /** Smallest editable-looking node whose on-screen bounds contain (x, y). */
    private fun editableAt(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        root ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.contains(x, y) && node.looksEditable()) {
                val area = b.width() * b.height()
                if (area in 1 until bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return best
    }

    private fun firstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.looksEditable()) return node
        for (i in 0 until node.childCount) {
            firstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** Direct text set for fields that don't honor PASTE. */
    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    private fun copyToClipboard(text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("cone-agent", text))
            true
        }.getOrDefault(false)
    }

    /** Pastes whatever is on the clipboard into [node], selecting any existing text first. */
    private fun pasteInto(node: AccessibilityNodeInfo): Boolean {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val existing = node.text?.length ?: 0
        if (existing > 0) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existing)
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel) }
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, mainHandler)
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    /* ---------- global navigation ---------- */

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /* ---------- structured reading ---------- */

    fun currentPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    fun dumpUiTree(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiElement>(64)
        collect(root, out, depth = 0)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<UiElement>, depth: Int) {
        node ?: return
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString()
        // Use the broadened check so custom chat input boxes (which often report isEditable=false)
        // are still surfaced to the model as 可输入, matching what inputText can actually fill.
        val editable = node.looksEditable()
        val interesting = text.isNotBlank() || !desc.isNullOrBlank() ||
            node.isClickable || editable || node.isScrollable

        if (interesting) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() > 0 && bounds.height() > 0) {
                out.add(
                    UiElement(
                        text = text,
                        contentDescription = desc,
                        className = node.className?.toString(),
                        resourceId = node.viewIdResourceName,
                        packageName = node.packageName?.toString(),
                        bounds = bounds,
                        clickable = node.isClickable,
                        editable = editable,
                        scrollable = node.isScrollable,
                    ),
                )
            }
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.looksEditable()) focused else null
    }

    private companion object {
        const val MAX_NODES = 200
        const val MAX_DEPTH = 60
    }
}

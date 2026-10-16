package com.cone.agent.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide bridge to the running [ConeAccessibilityService]. The service registers itself on
 * connect; every other component (agent engine, UI) talks to the hands of the agent through here.
 */
@Singleton
class AccessibilityBridge @Inject constructor() {

    @Volatile
    private var service: ConeAccessibilityService? = null

    /** Uptime millis of the last attach; lets readers tell "restarted" apart from "never bound". */
    @Volatile
    private var lastAttachAtMs = 0L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun attach(service: ConeAccessibilityService) {
        this.service = service
        lastAttachAtMs = android.os.SystemClock.uptimeMillis()
        _connected.value = true
    }

    internal fun detach(service: ConeAccessibilityService) {
        if (this.service === service) {
            this.service = null
            _connected.value = false
        }
    }

    val isReady: Boolean get() = service != null

    /**
     * Every call below is binder I/O against a service the system can kill at any moment. A dead
     * binder throws (DeadObjectException and friends) — that means "unreadable right now", not a
     * crash, so each entry point degrades to its empty/false/zero value instead of propagating.
     */
    suspend fun click(x: Int, y: Int): Boolean =
        runCatching { service?.tap(x, y) ?: false }.getOrDefault(false)
    suspend fun doubleClick(x: Int, y: Int): Boolean =
        runCatching { service?.doubleTap(x, y) ?: false }.getOrDefault(false)
    suspend fun longClick(x: Int, y: Int): Boolean =
        runCatching { service?.longPress(x, y) ?: false }.getOrDefault(false)
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        runCatching { service?.swipe(x1, y1, x2, y2, durationMs) ?: false }.getOrDefault(false)

    suspend fun inputText(text: String, x: Int?, y: Int?): Boolean =
        runCatching { service?.inputText(text, x, y) ?: false }.getOrDefault(false)

    fun back(): Boolean = runCatching { service?.globalBack() ?: false }.getOrDefault(false)
    fun home(): Boolean = runCatching { service?.globalHome() ?: false }.getOrDefault(false)
    fun recents(): Boolean = runCatching { service?.globalRecents() ?: false }.getOrDefault(false)

    fun currentPackage(): String? = runCatching { service?.currentPackageName() }.getOrNull()
    fun dumpUiTree(): List<UiElement> =
        runCatching { service?.dumpUiTree() ?: emptyList() }.getOrDefault(emptyList())

    /** A counter that advances whenever the foreground window/screen changes (see the service). */
    fun screenChangeToken(): Long =
        runCatching { service?.screenChangeToken() ?: 0L }.getOrDefault(0L)

    /**
     * Uptime millis of the last major screen transition (0 if none / no service). Falls back to
     * the last attach time so a freshly-restarted service still reports a sane baseline instead
     * of 0 (which the agent's settle check would otherwise read as "quiet forever").
     */
    fun screenChangeAtMs(): Long {
        val t = runCatching { service?.screenChangeAtMs() ?: 0L }.getOrDefault(0L)
        return if (t != 0L) t else lastAttachAtMs
    }
}

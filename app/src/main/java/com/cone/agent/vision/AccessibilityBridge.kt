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

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun attach(service: ConeAccessibilityService) {
        this.service = service
        _connected.value = true
    }

    internal fun detach(service: ConeAccessibilityService) {
        if (this.service === service) {
            this.service = null
            _connected.value = false
        }
    }

    val isReady: Boolean get() = service != null

    suspend fun click(x: Int, y: Int): Boolean = service?.tap(x, y) ?: false
    suspend fun doubleClick(x: Int, y: Int): Boolean = service?.doubleTap(x, y) ?: false
    suspend fun longClick(x: Int, y: Int): Boolean = service?.longPress(x, y) ?: false
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        service?.swipe(x1, y1, x2, y2, durationMs) ?: false

    suspend fun inputText(text: String, x: Int?, y: Int?): Boolean =
        service?.inputText(text, x, y) ?: false

    fun back(): Boolean = service?.globalBack() ?: false
    fun home(): Boolean = service?.globalHome() ?: false
    fun recents(): Boolean = service?.globalRecents() ?: false

    fun currentPackage(): String? = service?.currentPackageName()
    fun dumpUiTree(): List<UiElement> = service?.dumpUiTree() ?: emptyList()

    /** A counter that advances whenever the foreground window/screen changes (see the service). */
    fun screenChangeToken(): Long = service?.screenChangeToken() ?: 0L

    /** Uptime millis of the last major screen transition (0 if none / no service). */
    fun screenChangeAtMs(): Long = service?.screenChangeAtMs() ?: 0L
}

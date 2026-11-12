package com.cone.agent.ui.screens.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.core.Constants
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.ui.screens.chat.EnvironmentStatus
import com.cone.agent.vision.AccessibilityBridge
import com.cone.agent.vision.ScreenCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val bridge: AccessibilityBridge,
    private val captureManager: ScreenCaptureManager,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)
    private val a11yStuck = MutableStateFlow(false)
    private var lastRetryAt = 0L

    init {
        // 无障碍看门狗：系统里开着、但服务迟迟绑不回来（常见于崩溃循环或被停用后）时，
        // 一直显示"重启中"会让人以为在正常等待。超过 6 秒就升级为"未响应"，给出重开入口。
        viewModelScope.launch {
            var waitingSince = 0L
            while (true) {
                delay(1000)
                val connected = bridge.connected.value
                val enabled = com.cone.agent.vision.AccessibilityState.isEnabledInSettings(context)
                if (connected || !enabled) {
                    waitingSince = 0L
                    if (a11yStuck.value) a11yStuck.value = false
                } else {
                    if (waitingSince == 0L) waitingSince = System.currentTimeMillis()
                    val stuck = System.currentTimeMillis() - waitingSince > 6000
                    if (stuck) {
                        // 静默修复每 15 秒重试一次：部分 ROM 要等系统扫完已启用列表才肯重新绑定。
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastRetryAt > 15_000) {
                            lastRetryAt = nowMs
                            com.cone.agent.vision.AccessibilityState.tryReenable(context)
                        }
                        if (!a11yStuck.value) a11yStuck.value = true
                    }
                }
            }
        }
    }

    /** User pressed “重新开启”: try the silent fix first, then fall back to the system screen. */
    fun reenableAccessibility() {
        if (!com.cone.agent.vision.AccessibilityState.tryReenable(context)) {
            // Retail devices cannot write the secure setting; the caller opens system settings.
        }
        refresh()
    }

    val environment: StateFlow<EnvironmentStatus> = combine(
        bridge.connected,
        captureManager.running,
        settingsRepository.selectedModel,
        combine(refreshTick, a11yStuck) { tick, stuck -> tick to stuck },
    ) { accessibility, capture, model, tickAndStuck ->
        EnvironmentStatus(
            modelReady = model != null,
            accessibilityReady = accessibility,
            // 系统里开着但服务还没绑回来 = 重启中（见 AccessibilityState）
            accessibilityRestarting = !accessibility &&
                com.cone.agent.vision.AccessibilityState.isEnabledInSettings(context) &&
                !tickAndStuck.second,
            accessibilityStuck = !accessibility && tickAndStuck.second,
            overlayReady = canDrawOverlays(),
            captureReady = capture,
            notificationsReady = notificationsGranted(),
            microphoneReady = microphoneGranted(),
            assistantReady = isDefaultAssistant(),
            appListReady = appListGranted(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnvironmentStatus())

    fun refresh() {
        refreshTick.value += 1
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether we can read the installed-app list. On ROMs that define the runtime "获取应用列表"
     * permission we need it granted; where it's undefined (stock Android), QUERY_ALL_PACKAGES already
     * covers us, so we report ready.
     */
    private fun appListGranted(): Boolean {
        val perm = Constants.PERM_GET_INSTALLED_APPS
        val defined = runCatching { context.packageManager.getPermissionInfo(perm, 0); true }
            .getOrDefault(false)
        if (!defined) return true
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** True when Cone Agent is the system's selected voice-interaction (digital assistant) app. */
    private fun isDefaultAssistant(): Boolean = runCatching {
        val component = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        component != null && component.contains(context.packageName)
    }.getOrDefault(false)
}

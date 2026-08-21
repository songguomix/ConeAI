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
import com.cone.agent.service.WakeWordService
import com.cone.agent.ui.screens.chat.EnvironmentStatus
import com.cone.agent.vision.AccessibilityBridge
import com.cone.agent.vision.ScreenCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val bridge: AccessibilityBridge,
    private val captureManager: ScreenCaptureManager,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

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
            microphoneReady = microphoneGranted(),
            assistantReady = isDefaultAssistant(),
            appListReady = appListGranted(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnvironmentStatus())

    /** Whether the always-on wake-word listener is enabled (persisted). */
    val wakeWordEnabled: StateFlow<Boolean> = settingsRepository.wakeWordEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Wake-word sensitivity in [0f, 1f] (persisted); only surfaced while wake word is on. */
    val wakeWordSensitivity: StateFlow<Float> = settingsRepository.wakeWordSensitivity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Constants.DEFAULT_WAKE_SENSITIVITY)

    fun refresh() {
        refreshTick.value += 1
    }

    /** Toggle the wake-word listener: persist the preference and start/stop the foreground service. */
    fun setWakeWord(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWakeWordEnabled(enabled) }
        if (enabled) {
            // Only meaningful with the mic granted; otherwise the service self-stops until it is.
            if (microphoneGranted()) WakeWordService.start(context)
        } else {
            WakeWordService.stop(context)
        }
    }

    /** Adjust wake sensitivity: persist it and apply live to the running listener immediately. */
    fun setWakeSensitivity(value: Float) {
        WakeWordService.sensitivity = value
        viewModelScope.launch { settingsRepository.setWakeWordSensitivity(value) }
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

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
import com.cone.agent.vision.AccessibilityState
import com.cone.agent.vision.ScreenCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    accessibilityState: AccessibilityState,
    private val captureManager: ScreenCaptureManager,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    val environment: StateFlow<EnvironmentStatus> = combine(
        accessibilityState.status,
        captureManager.running,
        settingsRepository.selectedModel,
        refreshTick,
    ) { accessibility, capture, model, _ ->
        EnvironmentStatus(
            modelReady = model != null,
            accessibilityStatus = accessibility,
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

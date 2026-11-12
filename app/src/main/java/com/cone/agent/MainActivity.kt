package com.cone.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.ui.SystemActions
import kotlinx.coroutines.launch
import com.cone.agent.ui.navigation.ConeNavHost
import com.cone.agent.ui.theme.AppLocale
import com.cone.agent.ui.theme.ConeAgentTheme
import com.cone.agent.vision.ScreenCaptureService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var installedAppsProvider: com.cone.agent.vision.InstalledAppsProvider
    @Inject lateinit var accessibilityBridge: com.cone.agent.vision.AccessibilityBridge

    override fun onResume() {
        super.onResume()
        // 回到前台时静默自检一次：系统里开着却没连上，先尝试自己写回（ADB 授权设备立刻生效）。
        // 写不进去也没关系——权限页的看门狗会把状态升级为“未响应”，并给出「重新开启」。
        if (!accessibilityBridge.connected.value &&
            com.cone.agent.vision.AccessibilityState.isEnabledInSettings(this)
        ) {
            com.cone.agent.vision.AccessibilityState.tryReenable(this)
        }
    }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val microphoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val appListLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Once the ROM grants the app-list permission, re-read the (previously limited) list.
            if (granted) installedAppsProvider.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Fully immersive navigation bar: drop the system's translucent contrast scrim so our own
        // surface shows continuously behind the (transparent) gesture/navigation bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val systemActions = SystemActions(
            requestScreenCapture = ::requestScreenCapture,
            openAccessibilitySettings = ::openAccessibilitySettings,
            openOverlaySettings = ::openOverlaySettings,
            requestNotificationPermission = ::requestNotificationPermission,
            stopScreenCapture = { ScreenCaptureService.stop(this) },
            requestMicrophonePermission = ::requestMicrophonePermission,
            openAssistantSettings = ::openAssistantSettings,
            requestAppListPermission = ::requestAppListPermission,
        )

        setContent {
            val language by settingsRepository.appLanguage.collectAsState(initial = "zh")
            ConeAgentTheme {
                AppLocale(language) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val navController = rememberNavController()
                        ConeNavHost(navController = navController, systemActions = systemActions)
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestMicrophonePermission() {
        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Request the "read installed apps" runtime permission that Chinese ROMs require. On stock
     * Android the permission is undefined, so the launcher returns immediately (no dialog) and we
     * just fall through to QUERY_ALL_PACKAGES — harmless.
     */
    private fun requestAppListPermission() {
        runCatching { appListLauncher.launch(com.cone.agent.core.Constants.PERM_GET_INSTALLED_APPS) }
    }

    /** Open the system "Digital assistant app" picker so the user can set Cone Agent as default. */
    private fun openAssistantSettings() {
        val intents = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
    }
}

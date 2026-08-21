package com.cone.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.service.WakeWordService
import com.cone.agent.ui.SystemActions
import kotlinx.coroutines.flow.first
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

        // Resume the always-on wake-word listener if the user enabled it (and the mic is granted).
        lifecycleScope.launch {
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (settingsRepository.wakeWordEnabled.first() && micGranted) {
                WakeWordService.start(this@MainActivity)
            }
        }

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

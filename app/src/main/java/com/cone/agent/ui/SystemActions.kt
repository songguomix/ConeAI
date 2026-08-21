package com.cone.agent.ui

/**
 * Callbacks that require an Activity context (launchers / settings intents), provided by
 * [com.cone.agent.MainActivity] and threaded down to the screens.
 */
data class SystemActions(
    val requestScreenCapture: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val openOverlaySettings: () -> Unit,
    val requestNotificationPermission: () -> Unit,
    val stopScreenCapture: () -> Unit,
    val requestMicrophonePermission: () -> Unit,
    val openAssistantSettings: () -> Unit,
    val requestAppListPermission: () -> Unit,
)

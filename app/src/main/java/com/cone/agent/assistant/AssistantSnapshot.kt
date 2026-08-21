package com.cone.agent.assistant

import android.graphics.Bitmap

/**
 * Process-wide holder for the screenshot of the app that was in the foreground when the assistant was
 * summoned. The [ConeVoiceInteractionSession] receives it via `onHandleScreenshot` (a privileged
 * assist feature — no MediaProjection consent needed) and stashes it here; [AssistantActivity] reads
 * it for the "圈选屏幕" (region-crop → ask) flow. Null when the device/app didn't provide one.
 */
object AssistantSnapshot {
    @Volatile
    var screen: Bitmap? = null
}

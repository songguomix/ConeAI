package com.cone.agent.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession

/**
 * Thin voice-interaction session: it exists only so Cone can be the system's digital assistant and be
 * summoned by the assist gesture / long-press power. The interactive UI (pill, voice, answers) lives
 * in [AssistantActivity] — a normal foreground Activity, because [android.speech.SpeechRecognizer]
 * recording is unreliable from within a voice session on many devices. On show we just launch that
 * Activity and dismiss ourselves.
 */
class ConeVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val ctx: Context = context
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Reset any stale screenshot; onHandleScreenshot will deliver the fresh one shortly after.
        AssistantSnapshot.screen = null
        val intent = Intent(ctx, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Launch through a session-privileged start so it's exempt from background-activity-launch
        // limits. This matters most right after a reboot, when the app process is cold: a plain
        // startActivity from this (background) session is silently blocked, so the assistant never
        // appears. Prefer startAssistantActivity (API 30+); on older OS use startVoiceActivity, which
        // is also session-blessed; only then fall back to a plain (restricted) startActivity.
        val launched = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                runCatching { startAssistantActivity(intent); true }.getOrDefault(false)
            else ->
                runCatching { startVoiceActivity(intent); true }.getOrDefault(false)
        }
        if (!launched) runCatching { ctx.startActivity(intent) }

        // Defer dismissing the session: on a cold cold-start the activity hasn't actually started yet,
        // and tearing the session down immediately can revoke the launch token so nothing opens. Give
        // the privileged start a moment to commit first.
        mainHandler.postDelayed({ runCatching { hide() } }, 350)
    }

    /** Privileged assist screenshot of the app the user was in — stashed for the 圈选 feature. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) AssistantSnapshot.screen = screenshot
    }
}

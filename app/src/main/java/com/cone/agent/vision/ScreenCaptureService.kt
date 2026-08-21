package com.cone.agent.vision

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.cone.agent.R
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.core.Notifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service of type mediaProjection. It must be running before the projection session is
 * created (Android 14 requirement), so it claims foreground state then hands the grant to the
 * [ScreenCaptureManager].
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var captureManager: ScreenCaptureManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val notification = Notifications.build(
            this,
            Constants.CHANNEL_CAPTURE,
            "ConeAI",
            LocaleHelper.string(this, R.string.notif_capture_ready),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.NOTIF_CAPTURE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(Constants.NOTIF_CAPTURE_ID, notification)
        }

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode != 0 && data != null) {
                    captureManager.start(resultCode, data)
                }
            }
            ACTION_STOP -> {
                captureManager.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureManager.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.cone.agent.capture.START"
        const val ACTION_STOP = "com.cone.agent.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

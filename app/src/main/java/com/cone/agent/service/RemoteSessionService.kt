package com.cone.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cone.agent.MainActivity
import com.cone.agent.R
import com.cone.agent.core.Constants
import com.cone.agent.remote.RemoteSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the desktop-remote session alive while the app is in the background.
 *
 * The socket itself lives in [com.cone.agent.remote.RemoteSession]; this foreground service only
 * exists so Android does not freeze or kill the process (and therefore the stream) once the user
 * leaves the app, and so the ongoing connection is visible in the notification shade.
 */
@AndroidEntryPoint
class RemoteSessionService : Service() {

    @Inject lateinit var session: RemoteSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "断开" from the notification: tear the session down and leave.
            session.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        startForegroundIfNeeded(label)
        // START_STICKY restart (null intent): rebuild the connection to the remembered desktop,
        // otherwise the notification would outlive a dead session.
        if (intent == null) session.restore()
        return START_STICKY
    }

    private fun startForegroundIfNeeded(label: String) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, Constants.CHANNEL_REMOTE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.remote_service_title))
            .setContentText(label.ifBlank { getString(R.string.remote_service_text) })
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.remote_disconnect), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(Constants.CHANNEL_REMOTE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    Constants.CHANNEL_REMOTE,
                    getString(R.string.channel_remote_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val NOTIF_ID = 1004
        private const val ACTION_STOP = "com.cone.agent.remote.STOP"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String?) {
            val intent = Intent(context, RemoteSessionService::class.java)
                .putExtra(EXTRA_LABEL, label.orEmpty())
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemoteSessionService::class.java)) }
        }
    }
}

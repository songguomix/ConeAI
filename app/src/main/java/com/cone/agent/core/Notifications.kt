package com.cone.agent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cone.agent.MainActivity
import com.cone.agent.R

/** Centralised notification channel + builder helpers for the foreground services. */
object Notifications {

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val agent = NotificationChannel(
            Constants.CHANNEL_AGENT,
            context.getString(R.string.channel_agent_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val capture = NotificationChannel(
            Constants.CHANNEL_CAPTURE,
            context.getString(R.string.channel_capture_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        val wake = NotificationChannel(
            Constants.CHANNEL_WAKE,
            context.getString(R.string.channel_wake_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(agent)
        manager.createNotificationChannel(capture)
        manager.createNotificationChannel(wake)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun build(
        context: Context,
        channelId: String,
        title: String,
        text: String,
    ): Notification = Notification.Builder(context, channelId)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentIntent(contentIntent(context))
        .setOngoing(true)
        .build()
}

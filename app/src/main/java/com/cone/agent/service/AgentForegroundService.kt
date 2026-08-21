package com.cone.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.cone.agent.R
import com.cone.agent.agent.AgentController
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.core.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while a task runs and surfaces a persistent notification reflecting the
 * agent's current status. The actual loop lives in [AgentController].
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject lateinit var controller: AgentController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForegroundCompat(LocaleHelper.string(this, R.string.notif_agent_ready), "")
        scope.launch {
            controller.state.collectLatest { state ->
                startForegroundCompat(
                    title = "ConeAI · ${statusLabel(state.status.name)}",
                    text = state.currentAction.ifBlank { state.instruction },
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundCompat(title: String, text: String) {
        val notification = Notifications.build(this, Constants.CHANNEL_AGENT, title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIF_AGENT_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Constants.NOTIF_AGENT_ID, notification)
        }
    }

    private fun statusLabel(status: String): String = LocaleHelper.string(
        this,
        when (status) {
            "RUNNING" -> R.string.status_running
            "OBSERVING" -> R.string.status_observing
            "PAUSED" -> R.string.status_paused
            "WAITING_CONFIRM" -> R.string.status_waiting
            "SUCCEEDED" -> R.string.status_succeeded
            "FAILED" -> R.string.status_failed
            "CANCELLED" -> R.string.status_cancelled
            else -> R.string.status_idle
        },
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.cone.agent.agent.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentForegroundService::class.java))
        }
    }
}

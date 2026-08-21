package com.cone.agent

import android.app.Application
import com.cone.agent.core.Notifications
import com.cone.agent.data.repository.ChatRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ConeApplication : Application() {

    @Inject lateinit var chatRepository: dagger.Lazy<ChatRepository>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. Ensure notification channels are created (safe for API 26+)
        runCatching { Notifications.ensureChannels(this) }

        // 2. Start a fresh conversation on background thread to avoid blocking startup.
        // Using lazy injection to defer database initialization until this point.
        appScope.launch {
            runCatching {
                chatRepository.get().startFreshConversation()
            }
        }
    }
}

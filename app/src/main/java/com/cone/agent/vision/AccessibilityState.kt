package com.cone.agent.vision

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class AccessibilityStatus { Disabled, Connecting, Unbound, Connected }

@Singleton
class AccessibilityState @Inject constructor(
    @ApplicationContext context: Context,
    bridge: AccessibilityBridge,
) {
    private val resolver = context.contentResolver
    private val component = ComponentName(context, ConeAccessibilityService::class.java)
    private val settingsRevision = MutableStateFlow(0L)

    init {
        // One observer for the application process. A foreground refresh also covers OEMs
        // that restrict registration or omit notifications when returning from Settings.
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        try {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer,
            )
        } catch (e: RuntimeException) {
            Log.w("AccessibilityState", "Cannot observe accessibility settings", e)
        }
    }

    fun refresh() = settingsRevision.update { it + 1 }

    private val enabled = settingsRevision.map {
        val services = try {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: RuntimeException) {
            Log.w("AccessibilityState", "Cannot read enabled accessibility services", e)
            null
        }
        services.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    val status: Flow<AccessibilityStatus> = accessibilityStatus(enabled, bridge.connected)
        .shareIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Lazily, replay = 1)
}

/** The settings entry can precede the framework's onServiceConnected callback on a cold start. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun accessibilityStatus(enabled: Flow<Boolean>, connected: Flow<Boolean>): Flow<AccessibilityStatus> =
    combine(enabled, connected) { isEnabled, isConnected ->
        when {
            isConnected -> AccessibilityStatus.Connected
            isEnabled -> AccessibilityStatus.Connecting
            else -> AccessibilityStatus.Disabled
        }
    }.distinctUntilChanged().transformLatest { status ->
        emit(status)
        if (status == AccessibilityStatus.Connecting) {
            delay(6_000)
            emit(AccessibilityStatus.Unbound)
        }
    }

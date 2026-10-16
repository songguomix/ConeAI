package com.cone.agent.vision

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Whether the system currently lists Cone Agent's accessibility service as enabled.
 *
 * The bridge only knows about *bound* services (our own process attached to the running instance),
 * so it reports "not connected" in the window between the system restarting the service and the new
 * instance binding — and forever, if the system disabled it. Comparing against this setting is what
 * tells those two apart: "重启中" (wait for the bind) vs "没开" (go turn it on).
 */
object AccessibilityState {

    fun isEnabledInSettings(context: Context): Boolean {
        val me = ComponentName(context, ConeAccessibilityService::class.java).flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

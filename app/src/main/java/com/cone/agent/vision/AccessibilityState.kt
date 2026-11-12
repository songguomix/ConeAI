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

    /**
     * Best-effort silent re-enable.
     *
     * The system keeps the service enabled across reboots, but after a crash loop or a force-stop
     * some ROMs leave the setting on while never rebinding. Writing ENABLED_ACCESSIBILITY_SERVICES
     * needs WRITE_SECURE_SETTINGS (only ADB-provisioned devices have it), so this is a no-op on
     * retail phones — callers fall back to opening the system accessibility screen.
     *
     * @return true when the write went through (the service should bind within a second or two).
     */
    fun tryReenable(context: Context): Boolean {
        val granted = runCatching {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        }.getOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        val me = ComponentName(context, ConeAccessibilityService::class.java).flattenToString()
        return runCatching {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val services = current.split(':').filter { it.isNotBlank() }.toMutableSet()
            if (services.none { it.equals(me, ignoreCase = true) }) services.add(me)
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":"),
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            true
        }.getOrDefault(false)
    }

    fun isEnabledInSettings(context: Context): Boolean {
        val me = ComponentName(context, ConeAccessibilityService::class.java).flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

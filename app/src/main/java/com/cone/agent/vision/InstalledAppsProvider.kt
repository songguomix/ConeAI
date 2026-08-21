package com.cone.agent.vision

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A launchable app on the device. */
data class AppEntry(val label: String, val packageName: String)

/**
 * Reads the phone's launchable apps so the agent knows what it can jump to. The list is cached after
 * the first read (it changes rarely) and refreshed on demand; the model receives it each step so
 * `open_app` targets a real installed app instead of guessing a name.
 */
@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var cache: List<AppEntry>? = null

    /** All launchable apps (label + package), de-duplicated and sorted by label. Cached. */
    fun apps(forceRefresh: Boolean = false): List<AppEntry> {
        if (!forceRefresh) cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val list = resolved
            .mapNotNull { ri ->
                val info = ri.activityInfo ?: return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("").trim()
                if (label.isBlank()) null else AppEntry(label, info.packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }

    /** Refresh the cache (e.g. after an install/uninstall). */
    fun refresh() {
        apps(forceRefresh = true)
    }
}

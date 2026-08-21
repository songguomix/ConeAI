package com.cone.agent.code

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where 写代码 keeps its projects.
 *
 * The default is a plain `ConeAI` folder at the root of shared storage, so the code the model writes
 * is code the user actually owns: visible in any file manager, copyable to a computer, and loadable
 * by the preview WebView over `file://` with relative `styles.css` / `app.js` still resolving. That
 * requires all-files access, which the user grants in system settings; until they do — or if they
 * decline — everything falls back to app-private storage and the feature keeps working, just
 * invisibly.
 *
 * The chosen path lives in a small [android.content.SharedPreferences] rather than the DataStore the
 * rest of the app uses, for the same reason [com.cone.agent.core.LocaleHelper] does: it has to be
 * readable synchronously, from inside file operations that are already on a worker thread.
 */
@Singleton
class CodeStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _root = MutableStateFlow(resolveRoot())

    /** The folder projects live in. Changes when the user picks another one or grants access. */
    val root: StateFlow<File> = _root.asStateFlow()

    /** True once the app may write outside its own sandbox. */
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True while projects are stuck in app-private storage because access has not been granted. */
    fun isFallback(): Boolean = _root.value.path.startsWith(context.filesDir.path)

    /** Re-checks the permission and the chosen path; call when returning to the screen. */
    fun refresh() {
        _root.value = resolveRoot()
    }

    /**
     * Settings screens that grant all-files access, best first — the caller tries them in order.
     * A handful of ROMs ship without the per-app all-files page, so the app details page is kept as
     * a fallback rather than leaving the user with a button that does nothing.
     *
     * Empty below Android 11, where the plain storage runtime permission is requested instead.
     */
    fun accessSettingsIntents(): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val target = Uri.parse("package:${context.packageName}")
        return listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, target),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, target),
        )
    }

    /** Points the workspace at [dir]; pass null to go back to the default location. */
    fun setRoot(dir: File?) {
        prefs.edit().apply {
            if (dir == null) remove(KEY_ROOT) else putString(KEY_ROOT, dir.absolutePath)
        }.apply()
        refresh()
    }

    /** The default location, shown in the picker as "恢复默认" even when another one is in use. */
    fun defaultRoot(): File = File(Environment.getExternalStorageDirectory(), DEFAULT_DIR)

    /** Where a folder browser should start: the storage root when reachable, else the current root. */
    fun browseStart(): File =
        if (hasAccess()) Environment.getExternalStorageDirectory() else _root.value

    /** Sub-directories of [dir], for the folder picker. Unreadable directories list as empty. */
    fun subDirectories(dir: File): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.isHidden }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    /** True when [dir] can actually hold projects — checked by writing, not by asking. */
    fun isUsable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        val probe = File(dir, ".cone_write_probe")
        probe.writeText("")
        probe.delete()
        true
    }.getOrDefault(false)

    private fun resolveRoot(): File {
        val custom = prefs.getString(KEY_ROOT, null)?.let(::File)
        if (custom != null && hasAccess() && isUsable(custom)) return custom
        if (hasAccess()) {
            val preferred = defaultRoot()
            if (isUsable(preferred)) return preferred
        }
        return File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }
    }

    private companion object {
        const val PREFS = "cone_code_storage"
        const val KEY_ROOT = "root"
        const val DEFAULT_DIR = "ConeAI"
        const val PRIVATE_DIR = "code_projects"
    }
}

package com.cone.agent.core

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Applies the user-selected UI language outside of Compose — for the View-based assistant popup and
 * for background/runtime strings (e.g. the agent's chat-log lines).
 *
 * The chosen language lives in DataStore, but those reads are async. We mirror it into a tiny
 * synchronous [android.content.SharedPreferences] so it can be read from `attachBaseContext` and from
 * any non-suspending context. The Compose UI (MainActivity) localizes itself via
 * [com.cone.agent.ui.theme.AppLocale]; this helper covers everything else.
 */
object LocaleHelper {
    private const val PREFS = "cone_locale"
    private const val KEY = "lang"
    private const val DEFAULT = "zh"

    /** The saved language tag ("zh" / "en" / "ja"), read synchronously. */
    fun savedLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT) ?: DEFAULT

    /** Mirror the chosen language for synchronous reads. Call alongside persisting to DataStore. */
    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** A context whose resources resolve against the saved app language. */
    fun localized(context: Context): Context {
        val locale = Locale.forLanguageTag(savedLanguage(context))
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }

    /**
     * Wrap a base context for an Activity's `attachBaseContext`, so the whole (View-based) Activity is
     * localized. Also syncs the JVM default locale (affects TTS / the speech recognizer).
     */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(savedLanguage(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** Resolve a string resource in the saved app language, regardless of the system locale. */
    fun string(context: Context, @StringRes id: Int, vararg args: Any): String =
        localized(context).resources.getString(id, *args)
}

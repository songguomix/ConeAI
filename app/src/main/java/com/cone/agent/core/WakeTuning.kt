package com.cone.agent.core

import android.content.Context

/**
 * Synchronous mirror of the wake-word sensitivity so [com.cone.agent.service.WakeWordService] can
 * read it without a suspending DataStore call (same pattern as [LocaleHelper]). DataStore stays the
 * source of truth for the UI; this is written alongside it.
 */
object WakeTuning {
    private const val PREFS = "cone_wake"
    private const val KEY_SENSITIVITY = "sensitivity"

    /** The saved wake sensitivity in [0f, 1f], read synchronously. */
    fun sensitivity(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SENSITIVITY, Constants.DEFAULT_WAKE_SENSITIVITY)

    /** Mirror the chosen sensitivity for synchronous reads. Call alongside persisting to DataStore. */
    fun setSensitivity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SENSITIVITY, value.coerceIn(0f, 1f)).apply()
    }
}

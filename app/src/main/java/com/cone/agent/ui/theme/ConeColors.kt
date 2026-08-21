package com.cone.agent.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb

/** Material 3 color tokens as plain ARGB ints, for use from custom Views (not Compose). */
data class ConeColorTokens(
    val primary: Int,
    val onPrimary: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val error: Int,
    val onError: Int,
)

/**
 * Resolves the same color scheme the Compose UI uses so the View-based assistant/floating UIs match —
 * including Android 12+ wallpaper-based **dynamic color** ("取色"). Falls back to the app's static
 * palette on older devices, and follows light/dark from the current configuration.
 */
object ConeColors {
    fun tokens(context: Context): ConeColorTokens {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) DarkColors else LightColors
        }
        return ConeColorTokens(
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            surface = scheme.surface.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            surfaceVariant = scheme.surfaceVariant.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            outline = scheme.outline.toArgb(),
            error = scheme.error.toArgb(),
            onError = scheme.onError.toArgb(),
        )
    }
}

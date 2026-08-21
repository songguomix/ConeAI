package com.cone.agent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Material You inspired blue palette.
val ConePrimary = Color(0xFF0B57D0)
val ConePrimaryDark = Color(0xFFA8C7FA)

val LightColors = lightColorScheme(
    primary = ConePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF565F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

val DarkColors = darkColorScheme(
    primary = ConePrimaryDark,
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

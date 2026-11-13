package com.cone.agent.remote

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * ConeCode 的排版刻度，直接照搬桌面端 `src/index.css`：
 * `body { letter-spacing: -0.011em }`、Tailwind 的 1.5 倍行高，以及 9–20px 的字号档。
 *
 * 两端源码均未包含字体文件。无衬线、等宽与衬线沿用各平台系统回退字体；
 * 这里只统一排版参数，不能保证 Android 与 macOS 的系统字形完全相同。
 */
internal object RemoteType {
    private val track = (-0.011).em

    private fun scale(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        family: FontFamily = FontFamily.Default,
    ) = TextStyle(
        fontSize = size.sp,
        lineHeight = (size * 1.5f).sp,
        letterSpacing = track,
        fontWeight = weight,
        fontFamily = family,
    )

    /** 9px：极小徽标。 */
    val micro = scale(9)

    /** 10px：计数、状态角标。 */
    val tiny = scale(10)

    /** 11px：次要说明与标签。 */
    val caption = scale(11)

    /** 12px：小号正文（Tailwind text-xs）。 */
    val bodySmall = scale(12)

    /** 13px：主正文。 */
    val body = scale(13)

    /** 14px：强调正文（Tailwind text-sm）。 */
    val bodyLarge = scale(14)

    /** 15px：消息正文与面板标题，字重由调用处决定（ConeCode 正文不默认加粗）。 */
    val title = scale(15)

    /** 16px：页面标题（Tailwind text-base）。 */
    val heading = scale(16)

    /** 20px：连接页大标题。 */
    val display = scale(20)

    /** Compact mobile composer: the text and its placeholder share one 14sp / 20sp baseline. */
    val input = scale(14).copy(lineHeight = 20.sp, platformStyle = PlatformTextStyle(includeFontPadding = false))

    /** 等宽小号：路径与状态。 */
    val monoSmall = scale(11, family = FontFamily.Monospace)

    /** 等宽正文：代码、命令、路径。 */
    val mono = scale(12, family = FontFamily.Monospace)

    /** 等宽大号：终端输出。 */
    val monoBody = scale(13, family = FontFamily.Monospace)

    /** 消息标题、引用与卡片标题：桌面端 `font-serif`。 */
    val serifTitle = scale(15, FontWeight.Bold, FontFamily.Serif)

    val serifBody = scale(16, FontWeight.Bold, FontFamily.Serif)

    val serifQuote = scale(14, FontWeight.Normal, FontFamily.Serif).copy(letterSpacing = (-0.011).em)

    /** 侧栏品牌：ConeCode 用衬线 + 紧字距。 */
    val brand = scale(15, FontWeight.SemiBold, FontFamily.Serif).copy(letterSpacing = (-0.02).em)

    /** 对应 ConeCode 的 `tracking-tight` 标题。 */
    val trackingTight = (-0.02).em
}

/** Scope every Material control to the remote palette and type scale, including popup windows. */
@Composable
internal fun ConeCodeRemoteTheme(palette: RemotePalette, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val colors = base.copy(
        primary = palette.accent, onPrimary = Color.White,
        primaryContainer = palette.accentSoft, onPrimaryContainer = palette.accent,
        secondary = palette.muted, onSecondary = palette.bg2,
        secondaryContainer = palette.bg3, onSecondaryContainer = palette.text,
        tertiary = palette.accent, onTertiary = Color.White,
        background = palette.bg0, onBackground = palette.text,
        surface = palette.bg2, onSurface = palette.text,
        surfaceVariant = palette.bg1, onSurfaceVariant = palette.muted,
        surfaceTint = Color.Transparent,
        surfaceDim = palette.bg1, surfaceBright = palette.bg2,
        surfaceContainerLowest = palette.bg0, surfaceContainerLow = palette.bg1,
        surfaceContainer = palette.bg2, surfaceContainerHigh = palette.bg3,
        surfaceContainerHighest = palette.bg4,
        outline = palette.border, outlineVariant = palette.border,
        error = palette.danger, onError = Color.White,
    )
    val typography = Typography(
        displayLarge = RemoteType.display, displayMedium = RemoteType.display, displaySmall = RemoteType.display,
        headlineLarge = RemoteType.heading, headlineMedium = RemoteType.heading, headlineSmall = RemoteType.heading,
        titleLarge = RemoteType.title, titleMedium = RemoteType.bodyLarge, titleSmall = RemoteType.body,
        bodyLarge = RemoteType.bodyLarge, bodyMedium = RemoteType.body, bodySmall = RemoteType.bodySmall,
        labelLarge = RemoteType.body, labelMedium = RemoteType.bodySmall, labelSmall = RemoteType.caption,
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(
            LocalRemotePalette provides palette,
            LocalTextSelectionColors provides
                TextSelectionColors(palette.accent, palette.accentSoft),
        ) {
            ProvideTextStyle(RemoteType.body, content)
        }
    }
}

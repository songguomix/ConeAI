package com.cone.agent.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.flowOf

/**
 * 侧栏呈现方式。两者都渲染同一个 ConeCode 工作台外壳（相同的侧栏内容、标题栏、
 * 标签条和内容区），只是窄屏把侧栏收成紧凑图标栏，宽屏常驻展开。
 */
internal enum class RemoteLayout { PHONE, PAD }
internal const val REMOTE_PAD_MIN_WIDTH_DP = 600
internal const val REMOTE_TWO_PANE_WIDTH_DP = 840

private fun Context.remoteActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.remoteActivity()
    else -> null
}

/** WindowManager reports the actual hinge, including posture changes while connected. */
@Composable
internal fun rememberRemoteFoldingFeature(): FoldingFeature? {
    val context = LocalContext.current
    val activity = remember(context) { context.remoteActivity() }
    val flow = remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(context).windowLayoutInfo(it) }
            ?: flowOf(WindowLayoutInfo(emptyList()))
    }
    val info by flow.collectAsStateWithLifecycle(initialValue = WindowLayoutInfo(emptyList()))
    return info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
}

/** Keep the same Pad navigation on both displays; ConeCodeShell collapses its sidebar on narrow windows. */
@Composable
internal fun rememberRemoteLayout(): RemoteLayout {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val hardwareFoldable = remember(context) {
        context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle") ||
            context.packageManager.hasSystemFeature("com.samsung.android.feature.FOLDABLE") ||
            context.packageManager.hasSystemFeature("com.samsung.feature.device_category_foldable")
    }
    val foldingFeature = rememberRemoteFoldingFeature()
    var knownFoldable by rememberSaveable { mutableStateOf(hardwareFoldable) }
    LaunchedEffect(foldingFeature) { if (foldingFeature != null) knownFoldable = true }
    return remoteLayoutFor(configuration.screenWidthDp, configuration.smallestScreenWidthDp,
        hardwareFoldable || knownFoldable || foldingFeature != null)
}

internal fun remoteLayoutFor(width: Int, smallestWidth: Int, foldable: Boolean): RemoteLayout = when {
    foldable -> RemoteLayout.PAD
    smallestWidth >= REMOTE_PAD_MIN_WIDTH_DP -> RemoteLayout.PAD
    width >= REMOTE_TWO_PANE_WIDTH_DP -> RemoteLayout.PAD
    else -> RemoteLayout.PHONE
}

@Composable
internal fun rememberRemoteTwoPane(): Boolean =
    LocalConfiguration.current.screenWidthDp >= REMOTE_TWO_PANE_WIDTH_DP

/** Last two directory levels keep Windows and POSIX paths readable on narrow screens. */
internal fun shortRemotePath(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    val parts = normalized.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) normalized.ifEmpty { "/" } else "…/" + parts.takeLast(2).joinToString("/")
}

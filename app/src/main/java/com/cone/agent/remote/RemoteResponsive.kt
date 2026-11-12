package com.cone.agent.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 远程镜像的两种版式。
 *
 * - [PHONE]：单栏 + 顶部栏 + 底部标签栏，对话列表放在抽屉里。
 * - [PAD]：左侧常驻导航栏；宽屏（>=840dp）再展开第二栏——对话列表 / 文件树常驻，
 *   聊天与编辑器占右侧主区域。
 */
internal enum class RemoteLayout { PHONE, PAD }

/** 双栏（列表 + 主区域）的最小宽度；低于它的 PAD 版式只保留导航栏 + 单栏。 */
internal const val REMOTE_TWO_PANE_WIDTH_DP = 840

/** 进入 PAD 版式的最小宽度（平板 / 展开的折叠屏）。 */
internal const val REMOTE_PAD_MIN_WIDTH_DP = 600

/**
 * 折叠屏判定：铰链传感器是折叠屏独有的特性。
 *
 * 版本选择上，折叠屏展开后由宽度自然命中 PAD；折起来只有手机宽度，继续用手机版式，
 * 避免在 320dp 的外屏里硬塞 76dp 导航栏。这里仅用于把折叠屏和其它设备区分开，
 * 便于将来按铰链状态做分屏。
 */
@Composable
internal fun rememberFoldableDevice(): Boolean {
    val packageManager = LocalContext.current.packageManager
    return remember(packageManager) {
        packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle") ||
            packageManager.hasSystemFeature("android.hardware.sensor.hinge")
    }
}

/**
 * 版式判定：
 * 1. 折叠屏按当前宽度取 [RemoteLayout.PAD] / [RemoteLayout.PHONE]；
 * 2. 最短边 >= 600dp（平板 / 展开的折叠屏）或当前宽度 >= 840dp（横屏手机）→ [RemoteLayout.PAD]；
 * 3. 其余 → [RemoteLayout.PHONE]。
 */
@Composable
internal fun rememberRemoteLayout(): RemoteLayout {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    val foldable = rememberFoldableDevice()
    return when {
        // 折叠屏以当前展开宽度为准：外屏窄，按手机版式；展开后按 Pad。
        foldable -> if (width >= REMOTE_PAD_MIN_WIDTH_DP) RemoteLayout.PAD else RemoteLayout.PHONE
        configuration.smallestScreenWidthDp >= REMOTE_PAD_MIN_WIDTH_DP -> RemoteLayout.PAD
        width >= REMOTE_TWO_PANE_WIDTH_DP -> RemoteLayout.PAD
        else -> RemoteLayout.PHONE
    }
}

/** 是否可以把列表与主区域并排放：宽度够（平板横屏 / 展开的折叠屏）才双栏。 */
@Composable
internal fun rememberRemoteTwoPane(): Boolean =
    LocalConfiguration.current.screenWidthDp >= REMOTE_TWO_PANE_WIDTH_DP

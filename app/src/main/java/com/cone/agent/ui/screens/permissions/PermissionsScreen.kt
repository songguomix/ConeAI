package com.cone.agent.ui.screens.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.ui.SystemActions
import com.cone.agent.ui.theme.ConeShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    systemActions: SystemActions,
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val env by viewModel.environment.collectAsStateWithLifecycle()

    // 从系统设置页返回时重新读取权限状态，否则授予后这一页仍显示「未授权」。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // 9 项检查排成一列时，用户看不出「还差几项」；顶部给一个进度总览。
            // 唤醒词是可选功能，不进这份清单，所以分母就是清单本身的条数。
            val readinessChecks = listOf(
                env.modelReady,
                env.accessibilityReady,
                env.overlayReady,
                env.captureReady,
                env.notificationsReady,
                env.microphoneReady,
                env.appListReady,
                env.assistantReady,
            )
            ReadinessCard(
                readyCount = readinessChecks.count { it },
                total = readinessChecks.size,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            PermissionItem(
                title = stringResource(R.string.perm_model_title),
                description = stringResource(R.string.perm_model_desc),
                granted = env.modelReady,
                actionLabel = null,
                onAction = {},
            )
            PermissionItem(
                title = stringResource(R.string.perm_a11y_title),
                description = stringResource(
                    when {
                        env.accessibilityStuck -> R.string.perm_a11y_stuck
                        env.accessibilityRestarting -> R.string.perm_a11y_restarting
                        else -> R.string.perm_a11y_desc
                    },
                ),
                granted = env.accessibilityReady,
                actionLabel = stringResource(
                    if (env.accessibilityStuck) R.string.perm_reenable else R.string.perm_go_enable,
                ),
                onAction = {
                    // 先试静默修复（ADB 授权设备直接生效）；零售机打不开设置项，就带用户去系统页。
                    viewModel.reenableAccessibility()
                    systemActions.openAccessibilitySettings()
                },
            )
            PermissionItem(
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                granted = env.overlayReady,
                actionLabel = stringResource(R.string.perm_go_authorize),
                onAction = systemActions.openOverlaySettings,
            )
            PermissionItem(
                title = stringResource(R.string.perm_capture_title),
                description = stringResource(R.string.perm_capture_desc),
                granted = env.captureReady,
                actionLabel = stringResource(if (env.captureReady) R.string.perm_reauthorize else R.string.perm_start_authorize),
                onAction = systemActions.requestScreenCapture,
            )
            PermissionItem(
                title = stringResource(R.string.perm_notif_title),
                description = stringResource(R.string.perm_notif_desc),
                granted = env.notificationsReady,
                actionLabel = stringResource(R.string.perm_authorize),
                onAction = systemActions.requestNotificationPermission,
            )
            PermissionItem(
                title = stringResource(R.string.perm_mic_title),
                description = stringResource(R.string.perm_mic_desc),
                granted = env.microphoneReady,
                actionLabel = stringResource(R.string.perm_authorize),
                onAction = systemActions.requestMicrophonePermission,
            )
            PermissionItem(
                title = stringResource(R.string.perm_applist_title),
                description = stringResource(R.string.perm_applist_desc),
                granted = env.appListReady,
                actionLabel = stringResource(R.string.perm_authorize),
                onAction = systemActions.requestAppListPermission,
            )
            PermissionItem(
                title = stringResource(R.string.perm_assistant_title),
                description = stringResource(R.string.perm_assistant_desc),
                granted = env.assistantReady,
                actionLabel = stringResource(R.string.perm_go_settings),
                onAction = systemActions.openAssistantSettings,
            )
            if (env.captureReady) {
                OutlinedButton(
                    onClick = systemActions.stopScreenCapture,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text(stringResource(R.string.perm_stop_capture)) }
            }
        }
    }
}

/**
 * 就绪进度总览：标题右边是 `已就绪 N / 8`，下面一条线性进度条。
 *
 * 用 Material 3 的 LinearProgressIndicator，而不是自己画一条：深浅色、无障碍播报都跟着主题走。
 */
@Composable
private fun ReadinessCard(
    readyCount: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Card(shape = ConeShapes.Card, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.perm_ready_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.perm_ready_count, readyCount, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else readyCount.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/**
 * 一项系统能力：左侧状态图标（已授权 = 主题色对勾，未授权 = 灰色空心圈），
 * 右侧标题与说明；未授权时把按钮放在说明下面整行可点的位置。
 *
 * 原来是「图标 + 文字 + 按钮」挤在一行，中文说明稍长就被按钮压成窄窄一条；
 * 现在按钮换到文字下方（FilledTonalButton），说明拿满剩余宽度。
 */
@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(shape = ConeShapes.Card, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = stringResource(
                        if (granted) R.string.perm_status_granted else R.string.perm_status_missing,
                    ),
                    // 之前这里是硬编码的 0xFF2E7D32 绿色，和动态取色 / 深色模式都对不上；
                    // 改成主题的 primary，跟随壁纸与明暗切换。
                    tint = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!granted && actionLabel != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.padding(start = 36.dp, top = 12.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}

package com.cone.agent.remote

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal enum class RemoteView(val wireValue: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "Chat", Icons.Filled.ChatBubbleOutline),
    WORK("work", "Work", Icons.Filled.WorkOutline),
    RSI("rsi", "RSI", Icons.Filled.Bolt);

    companion object {
        fun from(snapshot: RemoteSnapshot): RemoteView = if (snapshot.agentMode == "rsi") RSI
            else entries.firstOrNull { it.wireValue == snapshot.viewMode } ?: WORK
    }
}

/** One primary navigator; workbench tabs belong only to Work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteViewNavigation(view: RemoteView, compact: Boolean, onSelect: (RemoteView) -> Unit) {
    val cx = LocalRemotePalette.current
    if (compact) {
        NavigationRail(containerColor = cx.bg1, modifier = Modifier.fillMaxWidth().height(204.dp), windowInsets = WindowInsets(0)) {
            RemoteView.entries.forEach { entry ->
                NavigationRailItem(
                    selected = entry == view, onClick = { onSelect(entry) },
                    icon = { Icon(entry.icon, contentDescription = null, modifier = Modifier.size(19.dp)) },
                    label = { Text(entry.label, style = RemoteType.tiny) },
                    colors = NavigationRailItemDefaults.colors(selectedIconColor = cx.accent,
                        selectedTextColor = cx.text, indicatorColor = cx.accentSoft,
                        unselectedIconColor = cx.muted, unselectedTextColor = cx.muted),
                )
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RemoteView.entries.forEachIndexed { index, entry ->
                SegmentedButton(selected = entry == view, onClick = { onSelect(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, RemoteView.entries.size),
                    icon = {}, colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = cx.accentSoft, activeContentColor = cx.accent,
                        inactiveContainerColor = cx.bg1, inactiveContentColor = cx.muted,
                        activeBorderColor = cx.border, inactiveBorderColor = cx.border)) {
                    Text(entry.label, style = RemoteType.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun RemoteChatScreen(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    RemoteConversationPane(snapshot.copy(rootPath = null), viewModel, modifier, showWorkControls = false, showPanels = false)
}

@Composable
internal fun RemoteWorkScreen(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    RemoteConversationPane(snapshot, viewModel, modifier)
}

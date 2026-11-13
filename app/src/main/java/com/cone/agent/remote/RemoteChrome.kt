package com.cone.agent.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cone.agent.R

/** Shared chrome; narrow panes stack the title instead of squeezing action buttons. */
@Composable
internal fun MirrorHeader(
    title: String,
    modelName: String?,
    connected: Boolean,
    insecure: Boolean,
    tab: RemoteTab,
    changeCount: Int,
    onTab: (RemoteTab) -> Unit,
    onMenu: (() -> Unit)?,
    onPickModel: () -> Unit,
    showWorkbenchTabs: Boolean,
    onScreen: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    Column(Modifier.fillMaxWidth().background(cx.bg1).statusBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 420.dp
            val modelMax = if (narrow) (maxWidth - if (insecure) 112.dp else 88.dp).coerceIn(64.dp, 180.dp) else 180.dp
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (onMenu != null) IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Menu, stringResource(R.string.remote_toggle_sidebar), tint = cx.muted, modifier = Modifier.size(18.dp))
                    }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) cx.success else cx.amber))
                    Spacer(Modifier.width(8.dp))
                    if (!narrow) {
                        Text(title, color = cx.text, style = RemoteType.body, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                    }
                    if (insecure) {
                        Icon(Icons.Filled.LockOpen, stringResource(R.string.remote_insecure_chip), tint = cx.amber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    ModelSelector(modelName, onPickModel, Modifier.widthIn(max = modelMax))
                    if (narrow) Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onScreen, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Computer, stringResource(R.string.remote_desktop),
                            tint = if (tab == RemoteTab.SCREEN) cx.accent else cx.muted, modifier = Modifier.size(18.dp))
                    }
                }
                if (narrow) Text(title, color = cx.muted, style = RemoteType.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 6.dp))
            }
        }
        if (showWorkbenchTabs) WorkbenchTabStrip(tab, changeCount, onTab)
        else HorizontalDivider(color = cx.border)
    }
}

@Composable
private fun ModelSelector(name: String?, onClick: () -> Unit, modifier: Modifier) {
    val cx = LocalRemotePalette.current
    val description = stringResource(R.string.remote_pick_model)
    Row(modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(9.dp)).background(cx.bg3)
        .border(1.dp, cx.border, RoundedCornerShape(9.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = description }.padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name ?: stringResource(R.string.remote_no_models), color = cx.text, style = RemoteType.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ArrowDropDown, null, tint = cx.muted, modifier = Modifier.size(14.dp))
    }
}

/** All four Work destinations remain visible even on a narrow outer display. */
@Composable
private fun WorkbenchTabStrip(tab: RemoteTab, changeCount: Int, onTab: (RemoteTab) -> Unit) {
    val cx = LocalRemotePalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val equalWidth = maxWidth < 420.dp
        val showIcons = maxWidth >= 320.dp
        Row(Modifier.fillMaxWidth()) {
            RemoteTab.entries.filter { it != RemoteTab.SCREEN }.forEach { entry ->
                val selected = tab == entry
                Column((if (equalWidth) Modifier.weight(1f) else Modifier.width(88.dp))
                    .height(42.dp).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(if (selected) cx.bg2 else Color.Transparent)
                    .selectable(selected, role = Role.Tab, onClick = { onTab(entry) })) {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 3.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (showIcons) {
                            Icon(entry.icon(), null, tint = if (selected) cx.accent else cx.muted, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(stringResource(entry.labelRes()), color = if (selected) cx.text else cx.muted,
                            style = RemoteType.bodySmall, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1, softWrap = false)
                        if (entry == RemoteTab.CHANGES && changeCount > 0) {
                            Spacer(Modifier.width(3.dp))
                            Text(changeCount.toString(), color = cx.text, style = RemoteType.micro, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(cx.accentSoft).padding(horizontal = 3.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) cx.accent else Color.Transparent))
                }
            }
        }
    }
    HorizontalDivider(color = cx.border)
}

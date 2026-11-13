package com.cone.agent.remote

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A new Android window otherwise resolves strings from the device locale instead of AppLocale. */
@Composable
internal fun RemoteDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
        containerColor = containerColor, modifier = modifier) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) { content() }
    }
}

@Composable
internal fun RemoteDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = content)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteModalBottomSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, containerColor = containerColor) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) { content() }
    }
}

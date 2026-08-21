package com.cone.agent.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.cone.agent.R

/**
 * The floating input island every conversational screen types into.
 *
 * One component so 问答, 智能体 and 写代码 cannot drift apart: same radius, same elevation, same
 * field behaviour. Screens differ only in what they hang in the [leading] and [trailing] slots —
 * chat puts attachments and 联网搜索 there, 写代码 puts 新建项目 and 历史项目.
 */
@Composable
fun ConeInputPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendEnabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    busyIcon: ImageVector = Icons.Filled.Pause,
    leading: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            // Backed by TextFieldValue so programmatic updates — voice-to-text filling the field, or
            // a tapped suggestion — land the caret at the END of the inserted text instead of leaving
            // it stuck at position 0. Typing still flows out through the hoisted String [value].
            var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            LaunchedEffect(value) {
                if (value != fieldValue.text) {
                    fieldValue = TextFieldValue(value, TextRange(value.length))
                }
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    if (it.text != value) onValueChange(it.text)
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 5,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            trailing()
            // While work is in flight the send button becomes the way to interrupt it.
            FilledIconButton(
                onClick = { if (busy) onStop() else onSend() },
                enabled = busy || sendEnabled,
            ) {
                if (busy) {
                    Icon(busyIcon, contentDescription = stringResource(R.string.cd_stop))
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.cd_send),
                    )
                }
            }
        }
    }
}

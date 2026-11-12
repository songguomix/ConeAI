package com.cone.agent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 标题 + 说明 + 右侧开关的一行，Material 3 语义（整行 `toggleable(Role.Switch)`）。
 *
 * 设置页、权限页和 Provider 编辑页原本各写了一套「Row + weight + Switch」，
 * 三套的间距、说明配色和点击区域都不一样（其中两套只能精确点到那个小开关）。
 * 统一到这里之后：整行可点、TalkBack 读作开关、标题与说明的 4dp 节奏一致。
 *
 * 用 Row 而不是 `ListItem`：material3 1.3 的 ListItem 没有 `contentPadding` 参数，
 * 默认 16dp 起始内边距会与设置卡片的 16dp 叠加成 32dp，正文与卡片标题对不齐。
 *
 * @param descriptionColor 需要强调风险的行（例如自动确认高风险操作）可传 `colorScheme.error`。
 * @param titleStyle 次级开关（例如「不限步数」）可传 `typography.bodyLarge`，避免与小节标题同字号。
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = titleStyle)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = descriptionColor,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

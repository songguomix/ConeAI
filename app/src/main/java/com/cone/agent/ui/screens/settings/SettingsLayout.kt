package com.cone.agent.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cone.agent.ui.theme.ConeShapes

/**
 * 二级选项的左缩进 = 单选圆钮宽度（40dp）+ 圆钮与文字的间距（8dp）。
 *
 * 这样二级选项的圆钮正好落在父选项文字的左边缘下，层级一眼可见；
 * 之前树的缩进是 32dp、警告是 40dp，而父选项文字在 48dp 处，三列互不对齐。
 * `SettingWarning` 也用同一个值，警告才会和子选项同列。
 */
internal val SettingsIndent = 48.dp

/**
 * 设置页的分组标题。
 *
 * 设置页有 12 个小节，以前只靠小节的 `HorizontalDivider` 分隔，整页是一条没有层级的文字墙；
 * 现在按「扩展连接 / 模型与识别 / 智能体行为 / 记忆与语音 / 关于与协议」分成 5 组（每组 ≤4 节），
 * 组与组之间由这个标题分隔，组内小节之间才保留分割线。
 */
@Composable
internal fun SettingsGroupLabel(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/**
 * 小节标题 + 说明的唯一排版定义。
 *
 * 这段「titleMedium 标题 + bodyMedium 灰度说明」在设置页里原本手抄了 14 遍，其中大半连外边距都各写各的，
 * 漂成 `top=4/bottom=8`、只 `top=4`、只 `bottom=8` 几种变体。统一到这里之后，字号、说明色和
 * 标题与说明之间的 4dp 节奏只在一处决定，调用方只需处理小节外侧的间距。
 *
 * @param descriptionColor 需要强调风险的小节（例如自动确认高风险操作）可以传 `colorScheme.error`。
 */
@Composable
internal fun SettingsSectionHeader(
    title: String,
    description: String? = null,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
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
}

/**
 * 嵌在父小节里的子节标题（例如「屏幕识别方式」里的画面理解模型）。
 *
 * 子节不是顶层小节：字号降一级、说明用更小的正文、节奏收紧到 2dp，
 * 这样设置页只有三种层级语言——分组标签 / 顶层小节 / 子节，不会再出现
 * “子节看起来和顶层小节一样”的歧义。
 */
@Composable
internal fun SettingsSubSectionHeader(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 可折叠小节的整行标题（自定义 ASR / TTS）：左侧标题与当前状态摘要，右侧展开箭头。
 *
 * 两个语音小节原本是逐字复制的同一个 Row，连箭头都各写了一遍。
 */
@Composable
internal fun SettingsExpandableHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SettingsSectionHeader(title = title, description = summary)
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 单选一行：整行可点，[description] 为空时就是一行纯文字（例如搜索引擎选项）。
 *
 * 屏幕识别的两级选项和搜索引擎原本是两套几乎一样的实现（一套带说明、一套不带），
 * 现在共用这一个；[indent] 用来表达“这是上一项的子选项”。
 */
@Composable
internal fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null,
    indent: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = indent, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一个小节一块卡片。
 *
 * 之前整页是一条没有边界的文字流，靠 16dp 分割线断句，滚动时所有内容连成一片。
 * 现在每块都有明确的四边和 20dp 圆角（与聊天卡片、Provider 列表同一套 ConeShapes.Card）：
 * 小节标题在卡内、组标题在卡外，层级一眼能分开。
 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 用官方 Card（M3 容器色 + 形状 + 内容色角色），不再手搓 clip/background。
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = ConeShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

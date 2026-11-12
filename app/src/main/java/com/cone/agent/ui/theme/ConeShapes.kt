package com.cone.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌。Gemini 的卡片是"大圆角 + 纯白底"，Muse 更接近纸张的柔和边缘，
 * 这里统一到 28 / 24 / 18 / 12 四档，弹层与卡片不再各写各的。
 */
object ConeShapes {
    /** 弹出层 / 上下文菜单（模型切换、语言、附件、更多）。 */
    val Menu = RoundedCornerShape(24.dp)

    /** 列表与聊天里的内容卡。 */
    val Card = RoundedCornerShape(24.dp)

    /** 主界面的大卡（空态、建议卡、Agent 状态卡）。 */
    val CardLarge = RoundedCornerShape(28.dp)

    /** 输入框 / 搜索框。 */
    val Field = RoundedCornerShape(26.dp)

    /** 底部弹层。 */
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** 胶囊按钮。 */
    val Pill = RoundedCornerShape(percent = 50)
}

/** Material3 的全局 Shapes，让没显式指定形状的组件也落到同一套圆角上。 */
val ConeMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

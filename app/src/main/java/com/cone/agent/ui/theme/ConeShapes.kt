package com.cone.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Pixel-style Material 3 corner radii for popup surfaces. Material's own dialogs and bottom sheets
 * already ship the large radii (28dp), but [androidx.compose.material3.DropdownMenu] defaults to a
 * near-square 4dp container; these tokens round the menus to match the Pixel system look and the
 * rest of the app's popups. Applied via `Modifier.clip(...)` on the menu.
 */
object ConeShapes {
    /** Dropdown / context menus (model switcher, language picker, attachment menu, overflow). */
    val Menu = RoundedCornerShape(20.dp)

    /** Inline conversation cards (接口执行/工具结果), matching the Pixel large-corner card look. */
    val Card = RoundedCornerShape(20.dp)
}

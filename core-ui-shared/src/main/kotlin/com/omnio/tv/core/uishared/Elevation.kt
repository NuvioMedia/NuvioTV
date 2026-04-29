package com.omnio.tv.core.uishared

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Elevation token scale aligned to Material3 default elevations.
 * level0 = surface flat, level5 = highest dialogs/menus. Used by both phone
 * (Material3 cards/sheets) and TV (focus-state lift).
 */
data object Elevation {
    val level0: Dp = 0.dp
    val level1: Dp = 1.dp
    val level2: Dp = 3.dp
    val level3: Dp = 6.dp
    val level4: Dp = 8.dp
    val level5: Dp = 12.dp
}

package com.omnio.tv.core.uishared

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing token scale shared by phone + TV layouts.
 * 4dp grid: xs=4, s=8, m=16, l=24, xl=32. Anything outside this scale should
 * be a one-off literal at the call site, not a new token.
 */
data object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
}

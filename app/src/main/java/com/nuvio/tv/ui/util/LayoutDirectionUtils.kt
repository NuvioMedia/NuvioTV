package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** True when the ambient layout direction is right-to-left (e.g. Hebrew or Arabic UI). */
@Composable
@ReadOnlyComposable
fun isRtlLayout(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/**
 * `slideInHorizontally` / `slideOutHorizontally` offsets are absolute screen coordinates (positive = right)
 * and are NOT mirrored in RTL. This converts an offset written as "toward the END edge" (right in LTR,
 * left in RTL) so a panel anchored to `Alignment.CenterEnd` always slides in from its own edge.
 */
fun Int.towardEnd(isRtl: Boolean): Int = if (isRtl) -this else this

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth

/**
 * Widens this element by [inset] toward the start edge, so it continues under the legacy
 * sidebar that pushes root content toward the end edge by that much. The element keeps its
 * size in the parent's layout; only what it draws reaches into the sidebar area. Callers add
 * [inset] to their start padding to keep their content where it was. A zero [inset] (modern
 * sidebar, collapsed sidebar, non-root screens) leaves the element unchanged.
 */
internal fun Modifier.bleedUnderStartInset(inset: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = inset.roundToPx().coerceAtLeast(0)
    if (extraPx == 0 || !constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    val width = constraints.maxWidth + extraPx
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.constrainWidth(placeable.width - extraPx), placeable.height) {
        placeable.placeRelative(-extraPx, 0)
    }
}

/**
 * Fade from the screen's start edge to where the first card starts, standing in for the legacy
 * sidebar's solid background over an edge-to-edge backdrop. Opaque across the sidebar's icon
 * column so the icons read over any image, then transparent by the end of [modifier]'s width.
 */
@Composable
internal fun LegacySidebarBackdropFade(color: Color, modifier: Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            // Solid across the icon column, then a short fade over the last third.
            val stops = arrayOf(
                0.0f to color,
                0.58f to color,
                0.74f to color.copy(alpha = 0.82f),
                0.88f to color.copy(alpha = 0.38f),
                1.0f to Color.Transparent
            )
            val brush = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                Brush.horizontalGradient(colorStops = stops, startX = size.width, endX = 0f)
            } else {
                Brush.horizontalGradient(colorStops = stops, startX = 0f, endX = size.width)
            }
            onDrawBehind { drawRect(brush) }
        }
    )
}

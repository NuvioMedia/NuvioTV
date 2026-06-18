package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

// Each preview scrolls by a whole number of card periods per cycle so the RepeatMode.Restart loop
// lands on a pixel-identical frame and the snap back is invisible.

/** Animated preview of the classic horizontal row layout: 3 rows, the middle one scrolling. */
@Composable
fun ClassicLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "classicPreview")
    val scrollOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "classicScroll"
    )

    val bgColor = NuvioTheme.colors.Background
    val cardColor = accentColor.copy(alpha = 0.6f)
    val cardColorDim = accentColor.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val rowCount = 3
            val rowSpacing = h * 0.04f
            val rowHeight = (h - rowSpacing * (rowCount + 1)) / rowCount
            val cardWidth = w / 5.5f
            val cardHeight = rowHeight * 0.85f
            val gap = w / 40f
            val step = cardWidth + gap
            val cornerRadius = CornerRadius(h * 0.02f)
            val shift = scrollOffset * step * 2f
            val cardsToFill = (w / step).toInt() + 4

            for (rowIndex in 0 until rowCount) {
                val rowY = rowSpacing + rowIndex * (rowHeight + rowSpacing)
                val cardTop = rowY + (rowHeight - cardHeight) / 2f

                if (rowIndex == 1) {
                    for (i in 0..cardsToFill) {
                        val cardX = gap * 2 + i * step - shift
                        if (cardX + cardWidth > 0f && cardX < w) {
                            drawRoundRect(
                                color = cardColor,
                                topLeft = Offset(cardX, cardTop),
                                size = Size(cardWidth, cardHeight),
                                cornerRadius = cornerRadius
                            )
                        }
                    }
                } else {
                    for (i in 0 until 7) {
                        val cardX = gap * 2 + i * step
                        if (cardX < w) {
                            drawRoundRect(
                                color = cardColorDim,
                                topLeft = Offset(cardX, cardTop),
                                size = Size(cardWidth, cardHeight),
                                cornerRadius = cornerRadius
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Animated preview of the grid layout: a 5-column grid scrolling upward. */
@Composable
fun GridLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gridPreview")
    val scrollOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // 3-row cycle, so the duration is ~3x to keep the original per-pixel speed.
            animation = tween(8800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gridScroll"
    )

    val bgColor = NuvioTheme.colors.Background
    val cardColor = accentColor.copy(alpha = 0.5f)
    val cardColorAlt = accentColor.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val cols = 5
            val cardGap = w * 0.025f
            val cardW = (w - cardGap * (cols + 1)) / cols
            val cardH = cardW * 1.4f
            val rowStep = cardH + cardGap
            val cornerRadius = CornerRadius(h * 0.015f)
            val scrollY = scrollOffset * rowStep * 3f
            val rowsToFill = (h / rowStep).toInt() + 5

            for (row in 0..rowsToFill) {
                val cardY = cardGap + row * rowStep - scrollY
                if (cardY + cardH > 0f && cardY < h) {
                    val color = if (row % 3 < 2) cardColor else cardColorAlt
                    for (col in 0 until cols) {
                        val cardX = cardGap + col * (cardW + cardGap)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(cardX, cardY),
                            size = Size(cardW, cardH),
                            cornerRadius = cornerRadius
                        )
                    }
                }
            }
        }
    }
}

/** Animated preview of the modern layout: a static hero with a scrolling card row beneath it. */
@Composable
fun ModernLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "modernPreview")
    val scrollOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // 3-card cycle, so the duration keeps the original per-pixel speed.
            animation = tween(5700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "modernScroll"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.Background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val horizontalPadding = w * 0.05f
            val topPadding = h * 0.06f
            val heroHeight = h * 0.62f
            val rowTop = topPadding + heroHeight + (h * 0.05f)
            val cardHeight = h * 0.24f
            val cardWidth = cardHeight * 1.45f
            val gap = w * 0.03f

            drawRoundRect(
                color = accentColor.copy(alpha = 0.38f),
                topLeft = Offset(horizontalPadding, topPadding),
                size = Size(w - (horizontalPadding * 2f), heroHeight),
                cornerRadius = CornerRadius(h * 0.05f)
            )

            val step = cardWidth + gap
            val cornerRadius = CornerRadius(h * 0.03f)
            val shift = scrollOffset * step * 3f
            val cardsToFill = (w / step).toInt() + 6

            for (i in 0..cardsToFill) {
                val x = horizontalPadding + (i * step) - shift
                if (x + cardWidth > 0f && x < w) {
                    drawRoundRect(
                        color = if (i % 3 == 1) {
                            accentColor.copy(alpha = 0.46f)
                        } else {
                            accentColor.copy(alpha = 0.28f)
                        },
                        topLeft = Offset(x, rowTop),
                        size = Size(cardWidth, cardHeight),
                        cornerRadius = cornerRadius
                    )
                }
            }
        }
    }
}

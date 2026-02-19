package com.nuvio.tv.ui.components

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
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * Animated preview of the classic horizontal row layout.
 * Shows 3 rows with colored placeholder rectangles scrolling horizontally.
 */
@Composable
fun ClassicLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioColors.Primary
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

    val bgColor = NuvioColors.Background
    val cardColor = accentColor.copy(alpha = 0.6f)
    val cardColorDim = accentColor.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
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

            for (rowIndex in 0 until rowCount) {
                val rowY = rowSpacing + rowIndex * (rowHeight + rowSpacing)

                // Cards - middle row scrolls
                val numCards = 7
                val baseOffset = if (rowIndex == 1) {
                    -scrollOffset * cardWidth * 2
                } else {
                    0f
                }

                for (i in 0 until numCards) {
                    val cardX = gap * 2 + i * (cardWidth + gap) + baseOffset
                    if (cardX + cardWidth > -cardWidth && cardX < w + cardWidth) {
                        drawRoundRect(
                            color = if (rowIndex == 1) cardColor else cardColorDim,
                            topLeft = Offset(cardX, rowY + (rowHeight - cardHeight) / 2f),
                            size = Size(cardWidth, cardHeight),
                            cornerRadius = CornerRadius(h * 0.02f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated preview of the grid layout.
 * Shows a 5-column grid of cards scrolling upward.
 */
@Composable
fun GridLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioColors.Primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gridPreview")
    val scrollOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gridScroll"
    )

    val bgColor = NuvioColors.Background
    val cardColor = accentColor.copy(alpha = 0.5f)
    val cardColorAlt = accentColor.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val cols = 5
            val cardGap = w * 0.025f
            val cardW = (w - cardGap * (cols + 1)) / cols
            val cardH = cardW * 1.4f
            val totalScrollY = scrollOffset * cardH * 1.5f

            for (row in 0..6) {
                for (col in 0 until cols) {
                    val cardX = cardGap + col * (cardW + cardGap)
                    val cardY = cardGap + row * (cardH + cardGap) - totalScrollY

                    if (cardY + cardH > 0f && cardY < h) {
                        val color = if (row % 3 < 2) cardColor else cardColorAlt
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(cardX, cardY),
                            size = Size(cardW, cardH),
                            cornerRadius = CornerRadius(h * 0.015f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated preview of the modern layout.
 * Shows a large hero area with a moving row of cards beneath it.
 */
@Composable
fun ModernLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioColors.Primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "modernPreview")
    val scrollOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "modernScroll"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NuvioColors.Background)
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

            val shift = scrollOffset * (cardWidth + gap) * 2.2f
            for (i in 0..8) {
                val x = horizontalPadding + (i * (cardWidth + gap)) - shift
                if (x + cardWidth > -cardWidth && x < w + cardWidth) {
                    drawRoundRect(
                        color = if (i % 3 == 1) {
                            accentColor.copy(alpha = 0.46f)
                        } else {
                            accentColor.copy(alpha = 0.28f)
                        },
                        topLeft = Offset(x, rowTop),
                        size = Size(cardWidth, cardHeight),
                        cornerRadius = CornerRadius(h * 0.03f)
                    )
                }
            }
        }
    }
}

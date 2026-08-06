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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath

// Each preview scrolls by a whole number of card periods per cycle so the RepeatMode.Restart loop
// lands on a pixel-identical frame and the snap back is invisible.

/** Preview of the classic horizontal row layout: 3 rows, the middle one scrolling when animated. */
@Composable
fun ClassicLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary,
    animated: Boolean = true
) {
    if (animated) {
        AnimatedClassicLayoutPreview(modifier = modifier, accentColor = accentColor)
    } else {
        StaticClassicLayoutPreview(modifier = modifier, accentColor = accentColor)
    }
}

@Composable
private fun AnimatedClassicLayoutPreview(
    modifier: Modifier,
    accentColor: Color
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
    ClassicLayoutPreviewFrame(
        modifier = modifier,
        accentColor = accentColor,
        scrollOffset = scrollOffset
    )
}

@Composable
private fun StaticClassicLayoutPreview(
    modifier: Modifier,
    accentColor: Color
) {
    ClassicLayoutPreviewFrame(
        modifier = modifier,
        accentColor = accentColor,
        scrollOffset = 0f
    )
}

@Composable
private fun ClassicLayoutPreviewFrame(
    modifier: Modifier,
    accentColor: Color,
    scrollOffset: Float
) {
    val bgColor = NuvioTheme.colors.Background
    val cardColor = accentColor.copy(alpha = 0.6f)
    val cardColorDim = accentColor.copy(alpha = 0.3f)

    LayoutPreviewFrame(modifier = modifier, background = bgColor) {
        drawClassicLayoutPreview(scrollOffset, cardColor, cardColorDim)
    }
}

private fun DrawScope.drawClassicLayoutPreview(
    scrollOffset: Float,
    cardColor: Color,
    cardColorDim: Color
) {
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

/** Preview of the grid layout: a 5-column grid scrolling upward when animated. */
@Composable
fun GridLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary,
    animated: Boolean = true
) {
    if (animated) {
        AnimatedGridLayoutPreview(modifier = modifier, accentColor = accentColor)
    } else {
        StaticGridLayoutPreview(modifier = modifier, accentColor = accentColor)
    }
}

@Composable
private fun AnimatedGridLayoutPreview(
    modifier: Modifier,
    accentColor: Color
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
    GridLayoutPreviewFrame(
        modifier = modifier,
        accentColor = accentColor,
        scrollOffset = scrollOffset
    )
}

@Composable
private fun StaticGridLayoutPreview(
    modifier: Modifier,
    accentColor: Color
) {
    GridLayoutPreviewFrame(
        modifier = modifier,
        accentColor = accentColor,
        scrollOffset = 0f
    )
}

@Composable
private fun GridLayoutPreviewFrame(
    modifier: Modifier,
    accentColor: Color,
    scrollOffset: Float
) {
    val bgColor = NuvioTheme.colors.Background
    val cardColor = accentColor.copy(alpha = 0.5f)
    val cardColorAlt = accentColor.copy(alpha = 0.3f)

    LayoutPreviewFrame(modifier = modifier, background = bgColor) {
        drawGridLayoutPreview(scrollOffset, cardColor, cardColorAlt)
    }
}

private fun DrawScope.drawGridLayoutPreview(
    scrollOffset: Float,
    cardColor: Color,
    cardColorAlt: Color
) {
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

/** Preview of the modern layout: a static hero with a scrolling card row when animated. */
@Composable
fun ModernLayoutPreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary,
    animated: Boolean = true
) {
    if (animated) {
        AnimatedModernLayoutPreview(modifier = modifier, accentColor = accentColor)
    } else {
        StaticModernLayoutPreview(modifier = modifier, accentColor = accentColor)
    }
}

@Composable
private fun AnimatedModernLayoutPreview(
    modifier: Modifier,
    accentColor: Color
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
    ModernLayoutPreviewFrame(
        modifier = modifier,
        accentColor = accentColor,
        scrollOffset = scrollOffset
    )
}

@Composable
private fun StaticModernLayoutPreview(
    modifier: Modifier,
    accentColor: Color
) {
    ModernLayoutPreviewFrame(
        modifier = modifier,
        accentColor = accentColor,
        scrollOffset = 0f
    )
}

@Composable
private fun ModernLayoutPreviewFrame(
    modifier: Modifier,
    accentColor: Color,
    scrollOffset: Float
) {
    LayoutPreviewFrame(modifier = modifier, background = NuvioTheme.colors.Background) {
        drawModernLayoutPreview(scrollOffset, accentColor)
    }
}

private fun DrawScope.drawModernLayoutPreview(
    scrollOffset: Float,
    accentColor: Color
) {
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

/** Static landscape Continue Watching style placeholder (single card, text on art). */
@Composable
fun CardCwStylePreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary
) {
    val bgColor = NuvioTheme.colors.Background

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Match real CARD: width = portraitBaseWidth * 1.24, height = width / 1.77.
            val cardW = minOf(w * 0.9f, h * 0.68f * 1.77f)
            val cardH = cardW / 1.77f
            val padX = (w - cardW) * 0.5f
            val padY = (h - cardH) * 0.5f
            val cornerRadius = CornerRadius(h * 0.04f)
            val inset = cardW * 0.05f
            val cardPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(padX, padY), Size(cardW, cardH)), cornerRadius))
            }

            clipPath(cardPath) {
                drawRect(color = accentColor.copy(alpha = 0.42f))
                // Dark bottom wash so overlay text reads, like the gradient on the real CARD.
                drawRect(
                    color = bgColor.copy(alpha = 0.68f),
                    topLeft = Offset(padX, padY + cardH * 0.48f),
                    size = Size(cardW, cardH * 0.52f)
                )
                // Title + episode overlay at bottom-left.
                val titleH = cardH * 0.1f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(padX + inset, padY + cardH * 0.56f),
                    size = Size(cardW * 0.52f, titleH),
                    cornerRadius = CornerRadius(titleH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(padX + inset, padY + cardH * 0.71f),
                    size = Size(cardW * 0.34f, titleH * 0.7f),
                    cornerRadius = CornerRadius(titleH)
                )
                // Top-right remaining-time badge.
                val badgeW = cardW * 0.26f
                val badgeH = cardH * 0.14f
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.78f),
                    topLeft = Offset(padX + cardW - badgeW - inset * 0.8f, padY + inset * 0.8f),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(badgeH)
                )
                // Thin progress track + fill along the bottom edge.
                val trackH = (cardH * 0.035f).coerceAtLeast(2f)
                val trackY = padY + cardH - inset * 0.9f - trackH
                val trackW = cardW - (inset * 2f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(padX + inset, trackY),
                    size = Size(trackW, trackH),
                    cornerRadius = CornerRadius(trackH)
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.95f),
                    topLeft = Offset(padX + inset, trackY),
                    size = Size(trackW * 0.55f, trackH),
                    cornerRadius = CornerRadius(trackH)
                )
            }
        }
    }
}

/** Static wide Continue Watching style placeholder (single card, poster strip + info). */
@Composable
fun WideCwStylePreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.Background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Match real WIDE: height = width * 0.4, so the card is 2.5:1 and must fit the panel.
            val cardW = minOf(w * 0.92f, h * 0.8f * 2.5f)
            val cardH = cardW * 0.4f
            val padX = (w - cardW) * 0.5f
            val padY = (h - cardH) * 0.5f
            val cornerRadius = CornerRadius(h * 0.05f)
            val posterW = cardH * (2f / 3f)
            val infoW = cardW - posterW
            val cardPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(padX, padY), Size(cardW, cardH)), cornerRadius))
            }

            clipPath(cardPath) {
                drawRect(color = accentColor.copy(alpha = 0.2f))
                // Poster strip on the left.
                drawRect(
                    color = accentColor.copy(alpha = 0.5f),
                    topLeft = Offset(padX, padY),
                    size = Size(posterW, cardH)
                )

                val padI = infoW * 0.1f
                val infoX = padX + posterW + padI
                val infoMaxW = infoW - (padI * 2f)
                val lineH = cardH * 0.11f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.82f),
                    topLeft = Offset(infoX, padY + cardH * 0.18f),
                    size = Size(infoMaxW * 0.82f, lineH),
                    cornerRadius = CornerRadius(lineH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.4f),
                    topLeft = Offset(infoX, padY + cardH * 0.34f),
                    size = Size(infoMaxW * 0.5f, lineH * 0.75f),
                    cornerRadius = CornerRadius(lineH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(infoX, padY + cardH * 0.47f),
                    size = Size(infoMaxW * 0.68f, lineH * 0.7f),
                    cornerRadius = CornerRadius(lineH)
                )
                // Bottom progress bar + remaining label.
                val barH = (cardH * 0.04f).coerceAtLeast(2f)
                val barY = padY + cardH * 0.72f
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.4f),
                    topLeft = Offset(infoX, barY),
                    size = Size(infoMaxW, barH),
                    cornerRadius = CornerRadius(barH)
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.95f),
                    topLeft = Offset(infoX, barY),
                    size = Size(infoMaxW * 0.48f, barH),
                    cornerRadius = CornerRadius(barH)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(infoX, barY + barH + cardH * 0.06f),
                    size = Size(infoMaxW * 0.4f, lineH * 0.55f),
                    cornerRadius = CornerRadius(lineH)
                )
            }
        }
    }
}

/** Static portrait Continue Watching style placeholder (single card, title under art). */
@Composable
fun PosterCwStylePreview(
    modifier: Modifier = Modifier,
    accentColor: Color = NuvioTheme.colors.Primary
) {
    val bgColor = NuvioTheme.colors.Background

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(bgColor)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Match real POSTER: 2:3 catalog card with a compact title block under the art.
            val titleH = h * 0.14f
            val titleGap = h * 0.03f
            val artW = minOf(h * 0.74f * (2f / 3f), w * 0.55f)
            val artH = artW * (3f / 2f)
            val padX = (w - artW) * 0.5f
            val padY = ((h - titleGap - titleH) - artH) * 0.5f
            val cornerRadius = CornerRadius(h * 0.03f)
            val inset = artW * 0.08f
            val artPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(padX, padY), Size(artW, artH)), cornerRadius))
            }

            clipPath(artPath) {
                drawRect(color = accentColor.copy(alpha = 0.45f))
                // Top-right badge.
                val badgeW = artW * 0.38f
                val badgeH = artH * 0.1f
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.78f),
                    topLeft = Offset(padX + artW - badgeW - inset * 0.6f, padY + inset * 0.6f),
                    size = Size(badgeW, badgeH),
                    cornerRadius = CornerRadius(badgeH)
                )
                // Pill progress near the bottom of the art.
                val pillPad = inset * 0.5f
                val trackH = (artH * 0.03f).coerceAtLeast(2f)
                val pillH = trackH + pillPad * 2f
                val pillY = padY + artH - pillH - inset * 0.6f
                val pillW = artW - (inset * 2f)
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.7f),
                    topLeft = Offset(padX + inset, pillY),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(pillH)
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.95f),
                    topLeft = Offset(padX + inset + pillPad, pillY + pillPad),
                    size = Size((pillW - pillPad * 2f) * 0.5f, trackH),
                    cornerRadius = CornerRadius(trackH)
                )
            }
            // Title under the artwork, not overlaid.
            val titleY = padY + artH + titleGap
            val titleLineH = titleH * 0.42f
            drawRoundRect(
                color = accentColor.copy(alpha = 0.55f),
                topLeft = Offset(padX, titleY),
                size = Size(artW * 0.88f, titleLineH),
                cornerRadius = CornerRadius(titleLineH)
            )
            drawRoundRect(
                color = accentColor.copy(alpha = 0.3f),
                topLeft = Offset(padX, titleY + titleLineH + titleGap * 0.5f),
                size = Size(artW * 0.5f, titleLineH * 0.7f),
                cornerRadius = CornerRadius(titleLineH)
            )
        }
    }
}

@Composable
private fun LayoutPreviewFrame(
    modifier: Modifier,
    background: Color,
    onDraw: DrawScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize(), onDraw = onDraw)
    }
}

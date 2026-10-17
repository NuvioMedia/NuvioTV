package com.nuvio.tv.ui.components

import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.contentColorFor
import androidx.tv.material3.surfaceColorAtElevation
import kotlinx.coroutines.launch

/**
 * Drop-in replacement for tv-material `Card` as the home rows configure it: one [shape] and
 * [containerColor] for every state, no scale, [focusedBorder] while focused or pressed and
 * [focusedGlow] while focused (`CardDefaults.border(focusedBorder = ...)`,
 * `CardDefaults.glow(focusedGlow = ...)`, `CardDefaults.scale(focusedScale = 1f)`).
 *
 * It draws the same layers in the same order as tv-material's `Surface` (glow, z-index, border,
 * background, clip) and handles D-pad select, focus and semantics the same way. The difference is
 * where the focus animation is read: tv-material reads its animated z-index during composition,
 * so every focus change recomposes the old and the new card on each frame of that animation.
 * Here the same spring is only read while placing the card, so a focus change recomposes this
 * composable once.
 */
@Composable
fun LightweightCard(
    onClick: () -> Unit,
    shape: Shape,
    containerColor: Color,
    modifier: Modifier = Modifier,
    focusedBorder: Border = Border.None,
    focusedGlow: Glow = Glow.None,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val zIndex = animateFloatAsState(
        targetValue = if (focused) FOCUSED_Z_INDEX else NON_FOCUSED_Z_INDEX,
        label = "zIndex"
    )
    val border = if (focused || pressed) focusedBorder else Border.None
    val glow = if (focused && !pressed) focusedGlow else Glow.None
    val glowColor = if (glow.elevationColor == MaterialTheme.colorScheme.surface) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(glow.elevation)
    } else {
        glow.elevationColor
    }
    val glowBlurRadiusPx = with(LocalDensity.current) { glow.elevation.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val pressInteraction = remember { PressInteraction.Press(Offset.Zero) }

    CompositionLocalProvider(LocalContentColor provides contentColorFor(containerColor)) {
        Box(
            modifier = modifier
                .onFocusChanged {
                    if (!it.isFocused && pressed) {
                        coroutineScope.launch {
                            interactionSource.emit(PressInteraction.Release(pressInteraction))
                        }
                    }
                }
                .onKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.keyCode !in SELECT_KEY_CODES) return@onKeyEvent false
                    when (native.action) {
                        AndroidKeyEvent.ACTION_DOWN -> if (native.repeatCount == 0) {
                            coroutineScope.launch { interactionSource.emit(pressInteraction) }
                        }
                        AndroidKeyEvent.ACTION_UP -> {
                            coroutineScope.launch {
                                interactionSource.emit(PressInteraction.Release(pressInteraction))
                            }
                            onClick()
                        }
                    }
                    true
                }
                .focusable(interactionSource = interactionSource)
                .semantics(mergeDescendants = true) {
                    onClick {
                        onClick()
                        true
                    }
                    onLongClick { false }
                }
                .graphicsLayer(scaleX = 1f, scaleY = 1f)
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        CardGlowElement(shape, glowBlurRadiusPx, glowColor)
                    } else {
                        Modifier
                    }
                )
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0, zIndex = zIndex.value)
                    }
                }
                .then(if (border != Border.None) CardBorderElement(shape, border) else Modifier)
                .background(containerColor, shape)
                .graphicsLayer {
                    this.shape = shape
                    clip = true
                },
            propagateMinConstraints = true
        ) {
            Box(modifier = Modifier.graphicsLayer { alpha = 1f }) {
                Column(content = content)
            }
        }
    }
}

private const val FOCUSED_Z_INDEX = 0.5f
private const val NON_FOCUSED_Z_INDEX = 0f

private val SELECT_KEY_CODES = intArrayOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
)

// The shape a Border gets when none is passed; it means "use the card's shape".
private val DefaultBorderShape = Border(Border.None.border).shape

private class OutlineCache {
    private var outline: Outline? = null
    private var shape: Shape? = null
    private var size = Size.Unspecified
    private var layoutDirection: LayoutDirection? = null
    private var density: Density? = null

    fun get(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cached = outline
        if (cached != null && shape == this.shape && size == this.size &&
            layoutDirection == this.layoutDirection && density == this.density
        ) {
            return cached
        }
        this.shape = shape
        this.size = size
        this.layoutDirection = layoutDirection
        this.density = density
        return shape.createOutline(size, layoutDirection, density).also { outline = it }
    }
}

private data class CardGlowElement(
    val shape: Shape,
    val glowBlurRadiusPx: Float,
    val color: Color
) : ModifierNodeElement<CardGlowNode>() {
    override fun create() = CardGlowNode(shape, glowBlurRadiusPx, color)

    override fun update(node: CardGlowNode) {
        node.update(shape, glowBlurRadiusPx, color)
    }
}

private class CardGlowNode(
    private var shape: Shape,
    private var glowBlurRadiusPx: Float,
    private var color: Color
) : DrawModifierNode, Modifier.Node() {
    private val paint = Paint()
    private val outlineCache = OutlineCache()

    init {
        applyShadowLayer()
    }

    fun update(shape: Shape, glowBlurRadiusPx: Float, color: Color) {
        this.shape = shape
        this.glowBlurRadiusPx = glowBlurRadiusPx
        this.color = color
        applyShadowLayer()
    }

    private fun applyShadowLayer() {
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = color.copy(alpha = 0f).toArgb()
        frameworkPaint.setShadowLayer(glowBlurRadiusPx, 0f, 0f, color.toArgb())
    }

    override fun ContentDrawScope.draw() {
        drawIntoCanvas { canvas ->
            when (val outline = outlineCache.get(shape, size, layoutDirection, this)) {
                is Outline.Rectangle -> canvas.drawRect(outline.rect, paint)
                is Outline.Rounded -> canvas.drawRoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = outline.roundRect.topLeftCornerRadius.x,
                    radiusY = outline.roundRect.topLeftCornerRadius.y,
                    paint = paint
                )
                is Outline.Generic -> canvas.drawPath(outline.path, paint)
            }
        }
        drawContent()
    }
}

private data class CardBorderElement(
    val shape: Shape,
    val border: Border
) : ModifierNodeElement<CardBorderNode>() {
    override fun create() = CardBorderNode(shape, border)

    override fun update(node: CardBorderNode) {
        node.shape = shape
        node.border = border
    }
}

private class CardBorderNode(
    var shape: Shape,
    var border: Border
) : DrawModifierNode, Modifier.Node() {
    private val outlineCache = OutlineCache()
    private var stroke: Stroke? = null

    override fun ContentDrawScope.draw() {
        drawContent()
        val borderShape = if (border.shape == DefaultBorderShape) shape else border.shape
        val strokeWidthPx = border.border.width.toPx()
        val outlineStroke = stroke?.takeIf { it.width == strokeWidthPx }
            ?: Stroke(width = strokeWidthPx, cap = StrokeCap.Round).also { stroke = it }
        inset(inset = -border.inset.toPx()) {
            drawOutline(
                outline = outlineCache.get(borderShape, size, layoutDirection, this),
                brush = border.border.brush,
                alpha = 1f,
                style = outlineStroke
            )
        }
    }
}

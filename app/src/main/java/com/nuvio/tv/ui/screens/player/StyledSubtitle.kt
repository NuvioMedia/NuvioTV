package com.nuvio.tv.ui.screens.player

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import com.nuvio.tv.core.player.SubtitleFontStore
import com.nuvio.tv.core.player.SubtitleFonts
import com.nuvio.tv.data.local.SubtitleStyleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SUBTITLE_BASE_TEXT_SP = 24f
private const val STYLED_SUBTITLE_PADDING_X = 10f
private const val STYLED_SUBTITLE_PADDING_Y = 5f

internal val SubtitleStyleSettings.usesStyledRenderer: Boolean
    get() = fontId != SubtitleFonts.DEFAULT || (backgroundColor ushr 24) > 0 || shadowEnabled

internal fun styledSubtitlePaddingXPx(textSizePx: Float): Float =
    STYLED_SUBTITLE_PADDING_X * textSizePx / SUBTITLE_BASE_TEXT_SP

@Composable
internal fun rememberSubtitleTypeface(fontId: String): Typeface? {
    val context = LocalContext.current
    val store = remember(context) { SubtitleFontStore.get(context) }
    return key(fontId) {
        produceState(store.cachedTypeface(fontId), store) {
            value = withContext(Dispatchers.IO) { store.typeface(fontId) }
        }.value
    }
}

@Composable
internal fun rememberSubtitleFontFamily(fontId: String): FontFamily {
    val context = LocalContext.current
    val store = remember(context) { SubtitleFontStore.get(context) }
    val typeface = rememberSubtitleTypeface(fontId)
    return remember(fontId, typeface) {
        typeface?.let { store.fontFile(fontId) }?.let { FontFamily(Font(it)) } ?: builtInSubtitleFontFamily(fontId)
    }
}

private fun builtInSubtitleFontFamily(fontId: String): FontFamily = when (fontId) {
    SubtitleFonts.SERIF -> FontFamily.Serif
    SubtitleFonts.MONO -> FontFamily.Monospace
    else -> FontFamily.Default
}

@Composable
internal fun StyledSubtitleText(
    text: AnnotatedString,
    style: SubtitleStyleSettings,
    textSizePx: Float,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val unit = textSizePx / SUBTITLE_BASE_TEXT_SP
        val paddingX = STYLED_SUBTITLE_PADDING_X * unit
        val paddingY = STYLED_SUBTITLE_PADDING_Y * unit
        val maxTextWidth = (constraints.maxWidth - paddingX * 2).toInt().coerceAtLeast(1)
        val layout = measurer.measure(
            text = text,
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = with(density) { textSizePx.toSp() },
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                textDirection = TextDirection.ContentOrLtr
            ),
            constraints = Constraints(maxWidth = maxTextWidth)
        )
        val lineWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
        val blockHeight = layout.size.height + paddingY * 2
        val textColor = Color(style.textColor)
        val outlineColor = Color(style.outlineColor)
        val outline = style.outlineWidth * unit
        val shadowSize = style.shadowSize * unit
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { blockHeight.toDp() })
                .semantics { this.text = text }
        ) {
            val blockWidth = lineWidth + paddingX * 2
            drawRoundRect(
                color = Color(style.backgroundColor),
                topLeft = Offset((size.width - blockWidth) / 2f, 0f),
                size = Size(blockWidth, size.height),
                cornerRadius = CornerRadius(style.backgroundRadius * unit)
            )
            val origin = Offset((size.width - layout.size.width) / 2f, paddingY)
            if (style.outlineEnabled && outline > 0f) {
                drawText(
                    layout,
                    color = outlineColor.copy(alpha = outlineColor.alpha * textColor.alpha),
                    topLeft = origin,
                    drawStyle = Stroke(width = outline * 2, join = StrokeJoin.Round),
                    shadow = Shadow.None
                )
            }
            drawText(
                layout,
                color = textColor,
                topLeft = origin,
                drawStyle = Fill,
                shadow = if (style.shadowEnabled) {
                    Shadow(
                        color = Color.Black.copy(alpha = style.shadowOpacity / 100f * textColor.alpha),
                        offset = Offset(shadowSize, shadowSize),
                        blurRadius = shadowSize * 2
                    )
                } else {
                    Shadow.None
                }
            )
        }
    }
}

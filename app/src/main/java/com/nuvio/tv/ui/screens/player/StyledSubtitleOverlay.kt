package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.tv.data.local.SubtitleStyleSettings

private const val TOP_SUBTITLE_INSET_FRACTION = 0.08f
private const val SIDE_SUBTITLE_INSET_FRACTION = 0.05f

internal data class SubtitleBottomPlacement(val paddingPx: Int, val fraction: Float) {
    fun insetPx(heightPx: Int): Float = paddingPx + (heightPx - paddingPx) * fraction
}

internal fun subtitleBottomPlacement(heightPx: Int, verticalOffset: Int, liftPx: Float): SubtitleBottomPlacement {
    val paddingPx = (heightPx * (verticalOffset / 400f)).toInt().coerceAtLeast(0)
    val baseFraction = (0.06f + verticalOffset / 250f).coerceIn(0f, 0.4f)
    val availableHeight = heightPx - paddingPx
    val fraction = if (liftPx > 0f && availableHeight > 0) {
        maxOf(baseFraction, (liftPx - paddingPx) / availableHeight).coerceIn(0f, 0.5f)
    } else {
        baseFraction
    }
    return SubtitleBottomPlacement(paddingPx, fraction)
}

@Composable
internal fun StyledSubtitleOverlay(
    cues: StyledSubtitleCues,
    style: SubtitleStyleSettings,
    textSizePx: Float,
    liftPx: Float,
    modifier: Modifier = Modifier
) {
    val fontFamily = rememberSubtitleFontFamily(style.fontId)
    if (cues.isEmpty) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight
        val sideInset = with(density) { (constraints.maxWidth * SIDE_SUBTITLE_INSET_FRACTION).toDp() }
        val bottomInset = with(density) {
            subtitleBottomPlacement(heightPx, style.verticalOffset, liftPx).insetPx(heightPx).toDp()
        }
        val topInset = with(density) { (heightPx * TOP_SUBTITLE_INSET_FRACTION).toDp() }
        cues.blocks.forEach { block ->
            val sidePadding = if (block.horizontal == SubtitleHorizontalPosition.CENTER) 0.dp else sideInset
            StyledSubtitleText(
                text = block.text,
                style = style,
                textSizePx = textSizePx,
                fontFamily = fontFamily,
                horizontal = block.horizontal,
                modifier = Modifier
                    .align(block.alignment())
                    .padding(
                        start = sidePadding,
                        end = sidePadding,
                        top = if (block.vertical == SubtitleVerticalPosition.TOP) topInset else 0.dp,
                        bottom = if (block.vertical == SubtitleVerticalPosition.BOTTOM) bottomInset else 0.dp
                    )
            )
        }
    }
}

private fun StyledSubtitleBlock.alignment(): Alignment = when (vertical) {
    SubtitleVerticalPosition.TOP -> when (horizontal) {
        SubtitleHorizontalPosition.START -> Alignment.TopStart
        SubtitleHorizontalPosition.CENTER -> Alignment.TopCenter
        SubtitleHorizontalPosition.END -> Alignment.TopEnd
    }
    SubtitleVerticalPosition.MIDDLE -> when (horizontal) {
        SubtitleHorizontalPosition.START -> Alignment.CenterStart
        SubtitleHorizontalPosition.CENTER -> Alignment.Center
        SubtitleHorizontalPosition.END -> Alignment.CenterEnd
    }
    SubtitleVerticalPosition.BOTTOM -> when (horizontal) {
        SubtitleHorizontalPosition.START -> Alignment.BottomStart
        SubtitleHorizontalPosition.CENTER -> Alignment.BottomCenter
        SubtitleHorizontalPosition.END -> Alignment.BottomEnd
    }
}

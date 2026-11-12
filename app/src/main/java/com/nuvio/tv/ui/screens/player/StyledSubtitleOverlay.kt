package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.data.local.SubtitleStyleSettings

private const val TOP_SUBTITLE_INSET_FRACTION = 0.08f

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
        if (cues.top.isNotEmpty()) {
            StyledSubtitleText(
                text = cues.top,
                style = style,
                textSizePx = textSizePx,
                fontFamily = fontFamily,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { (heightPx * TOP_SUBTITLE_INSET_FRACTION).toDp() })
            )
        }
        if (cues.bottom.isNotEmpty()) {
            val insetPx = subtitleBottomPlacement(heightPx, style.verticalOffset, liftPx).insetPx(heightPx)
            StyledSubtitleText(
                text = cues.bottom,
                style = style,
                textSizePx = textSizePx,
                fontFamily = fontFamily,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { insetPx.toDp() })
            )
        }
    }
}

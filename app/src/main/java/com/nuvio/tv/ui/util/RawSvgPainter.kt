package com.nuvio.tv.ui.util

import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Loads a raw resource (typically an SVG) as a [Painter], rasterised at [targetSize].
 * The default of 24.dp equals `NuvioTheme.spacing.xl`.
 */
@Composable
internal fun rememberRawSvgPainter(
    @RawRes rawIconRes: Int,
    targetSize: Dp = 24.dp
): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { targetSize.roundToPx() }
    val request = remember(rawIconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

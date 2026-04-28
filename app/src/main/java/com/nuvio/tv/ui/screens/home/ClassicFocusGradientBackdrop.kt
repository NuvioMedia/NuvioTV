package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import kotlin.math.max

private const val CLASSIC_FOCUS_GRADIENT_DEBOUNCE_MS = 140L
private const val CLASSIC_FOCUS_GRADIENT_CACHE_RETRY_MS = 360L
private const val CLASSIC_FOCUS_GRADIENT_COLOR_CACHE_SIZE = 256

internal data class ClassicFocusArtwork(
    val imageUrl: String?,
    val seed: String
)

@Composable
internal fun ClassicFocusGradientBackdrop(
    artwork: ClassicFocusArtwork?,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fallbackColor = NuvioColors.FocusBackground
    val colorCache = remember(fallbackColor) { classicFocusGradientColorCache() }
    var targetColor by remember { mutableStateOf(Color.Transparent) }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 700),
        label = "classicFocusGradientColor"
    )

    LaunchedEffect(context, artwork, enabled, fallbackColor) {
        if (!enabled || artwork == null) {
            targetColor = Color.Transparent
            return@LaunchedEffect
        }

        colorCache[artwork]?.let {
            targetColor = it
            return@LaunchedEffect
        }

        delay(CLASSIC_FOCUS_GRADIENT_DEBOUNCE_MS)
        var resolvedColor = resolveArtworkColor(context, artwork, fallbackColor)
        targetColor = resolvedColor.color
        if (!resolvedColor.cacheable) {
            delay(CLASSIC_FOCUS_GRADIENT_CACHE_RETRY_MS)
            resolvedColor = resolveArtworkColor(context, artwork, fallbackColor)
            targetColor = resolvedColor.color
        }
        if (resolvedColor.cacheable) {
            colorCache[artwork] = resolvedColor.color
        }
    }

    Box(
        modifier = modifier.drawBehind {
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.66f to animatedColor.copy(alpha = 0.16f),
                        0.84f to animatedColor.copy(alpha = 0.30f),
                        1f to animatedColor.copy(alpha = 0.44f)
                    ),
                    start = Offset(size.width * 0.12f, 0f),
                    end = Offset(size.width, size.height * 0.82f)
                )
            )
        }
    )
}

private data class ResolvedArtworkColor(
    val color: Color,
    val cacheable: Boolean
)

private fun classicFocusGradientColorCache(): MutableMap<ClassicFocusArtwork, Color> {
    return object : LinkedHashMap<ClassicFocusArtwork, Color>(
        CLASSIC_FOCUS_GRADIENT_COLOR_CACHE_SIZE,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClassicFocusArtwork, Color>?): Boolean {
            return size > CLASSIC_FOCUS_GRADIENT_COLOR_CACHE_SIZE
        }
    }
}

private suspend fun resolveArtworkColor(
    context: Context,
    artwork: ClassicFocusArtwork,
    fallbackColor: Color
): ResolvedArtworkColor {
    val fallback = deriveSeedColor(artwork.seed, fallbackColor)
    val imageUrl = artwork.imageUrl?.takeIf { it.isNotBlank() }
        ?: return ResolvedArtworkColor(fallback, cacheable = true)
    return withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .size(Size(72, 72))
            .build()
        val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
        val image = (result as? SuccessResult)?.image
            ?: return@withContext ResolvedArtworkColor(fallback, cacheable = false)
        val bitmap = (image as? BitmapImage)?.bitmap
            ?: return@withContext ResolvedArtworkColor(fallback, cacheable = false)
        ResolvedArtworkColor(
            color = sampledProminentColor(bitmap) ?: fallback,
            cacheable = true
        )
    }
}

private fun sampledProminentColor(bitmap: Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    val stepX = max(1, bitmap.width / 14)
    val stepY = max(1, bitmap.height / 14)
    val hsv = FloatArray(3)
    var weightedRed = 0f
    var weightedGreen = 0f
    var weightedBlue = 0f
    var totalWeight = 0f

    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel) / 255f
            if (alpha < 0.35f) continue
            android.graphics.Color.colorToHSV(pixel, hsv)
            if (hsv[2] < 0.08f) continue
            val saturation = hsv[1].coerceIn(0f, 1f)
            val value = hsv[2].coerceIn(0f, 1f)
            val weight = alpha * (0.35f + saturation * 1.65f) * (0.50f + value)
            weightedRed += android.graphics.Color.red(pixel) * weight
            weightedGreen += android.graphics.Color.green(pixel) * weight
            weightedBlue += android.graphics.Color.blue(pixel) * weight
            totalWeight += weight
        }
    }

    if (totalWeight <= 0f) return null

    return stabilizeBackdropColor(
        Color(
            red = (weightedRed / totalWeight) / 255f,
            green = (weightedGreen / totalWeight) / 255f,
            blue = (weightedBlue / totalWeight) / 255f,
            alpha = 1f
        )
    )
}

private fun deriveSeedColor(seed: String, fallbackColor: Color): Color {
    if (seed.isBlank()) return stabilizeBackdropColor(fallbackColor)
    val hue = ((seed.hashCode().toLong() and 0xffffffffL) % 360L).toFloat()
    return stabilizeBackdropColor(
        lerp(
            fallbackColor,
            Color.hsv(hue = hue, saturation = 0.58f, value = 0.82f),
            0.58f
        )
    )
}

private fun stabilizeBackdropColor(color: Color): Color {
    val opaque = color.copy(alpha = 1f)
    val balanced = when {
        opaque.luminance() < 0.16f -> lerp(opaque, Color.White, 0.34f)
        opaque.luminance() > 0.72f -> lerp(opaque, Color.Black, 0.32f)
        else -> opaque
    }
    return balanced.copy(alpha = 1f)
}

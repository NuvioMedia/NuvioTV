package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import coil3.BitmapImage
import coil3.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun rememberArtworkBackedCardGlow(
    cover: Image?,
    isFocused: Boolean,
    fallbackSeed: String,
    enabled: Boolean,
    fallbackColor: Color = NuvioTheme.colors.FocusBackground
): CardGlow {
    val focusedGlow = rememberArtworkBackedFocusGlow(cover, isFocused, fallbackSeed, enabled, fallbackColor)
    return remember(focusedGlow) { CardDefaults.glow(focusedGlow = focusedGlow) }
}

/** The focused [Glow] of [rememberArtworkBackedCardGlow], for cards that take a plain [Glow]. */
@Composable
fun rememberArtworkBackedFocusGlow(
    cover: Image?,
    isFocused: Boolean,
    fallbackSeed: String,
    enabled: Boolean,
    fallbackColor: Color = NuvioTheme.colors.FocusBackground
): Glow {
    if (!enabled) return Glow.None

    val fallback = remember(fallbackSeed, fallbackColor) {
        deriveFallbackGlowColor(fallbackSeed, fallbackColor)
    }
    var artworkColor by remember(cover, fallback) { mutableStateOf<Color?>(null) }
    LaunchedEffect(cover, isFocused, fallback) {
        if (isFocused && cover is BitmapImage && artworkColor == null) {
            // Use the cover that's already on screen. A separate small Coil
            // request can still fetch the entire image on a cold cache.
            artworkColor = withContext(Dispatchers.Default) {
                sampleCoverGlow(cover.bitmap) ?: fallback
            }
        }
    }

    val glowColor = artworkColor ?: fallback
    return remember(glowColor) {
        Glow(
            elevationColor = glowColor,
            elevation = 28.dp
        )
    }
}

private fun sampleCoverGlow(bitmap: Bitmap): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || bitmap.config != Bitmap.Config.HARDWARE) {
        return sampledGlowColor(bitmap)
    }
    val readable = try {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } catch (_: IllegalStateException) {
        null
    } ?: return null
    return try {
        sampledGlowColor(readable)
    } finally {
        readable.recycle()
    }
}

private fun sampledGlowColor(bitmap: Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    val stepX = max(1, bitmap.width / 12)
    val stepY = max(1, bitmap.height / 12)
    var weightedRed = 0f
    var weightedGreen = 0f
    var weightedBlue = 0f
    var totalWeight = 0f

    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel) / 255f
            if (alpha < 0.35f) continue

            weightedRed += android.graphics.Color.red(pixel) * alpha
            weightedGreen += android.graphics.Color.green(pixel) * alpha
            weightedBlue += android.graphics.Color.blue(pixel) * alpha
            totalWeight += alpha
        }
    }

    if (totalWeight <= 0f) return null

    return stabilizeGlowColor(
        Color(
            red = (weightedRed / totalWeight) / 255f,
            green = (weightedGreen / totalWeight) / 255f,
            blue = (weightedBlue / totalWeight) / 255f,
            alpha = 0.92f
        )
    )
}

private fun deriveFallbackGlowColor(seed: String, fallbackColor: Color): Color {
    if (seed.isBlank()) return stabilizeGlowColor(fallbackColor)
    val hue = ((seed.hashCode().toLong() and 0xffffffffL) % 360L).toFloat()
    val seededAccent = Color.hsv(hue = hue, saturation = 0.48f, value = 0.82f)
    return stabilizeGlowColor(lerp(fallbackColor, seededAccent, 0.55f))
}

private fun stabilizeGlowColor(color: Color): Color {
    val opaque = color.copy(alpha = 1f)
    val balanced = when {
        opaque.luminance() < 0.18f -> lerp(opaque, Color.White, 0.30f)
        opaque.luminance() > 0.84f -> lerp(opaque, Color.Black, 0.18f)
        else -> opaque
    }
    return balanced.copy(alpha = 0.92f)
}

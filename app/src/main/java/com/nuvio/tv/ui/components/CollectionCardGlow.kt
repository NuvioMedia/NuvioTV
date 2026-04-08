package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun rememberArtworkBackedCardGlow(
    imageUrl: String?,
    fallbackSeed: String,
    enabled: Boolean,
    fallbackColor: Color = NuvioColors.FocusBackground
): CardGlow {
    val noGlow = remember { CardDefaults.glow(focusedGlow = Glow.None) }
    if (!enabled) return noGlow

    val context = LocalContext.current
    var glowColor by remember(imageUrl, fallbackSeed, fallbackColor) {
        mutableStateOf(deriveFallbackGlowColor(fallbackSeed, fallbackColor))
    }

    LaunchedEffect(context, imageUrl, fallbackSeed, fallbackColor, enabled) {
        if (!enabled) {
            glowColor = deriveFallbackGlowColor(fallbackSeed, fallbackColor)
            return@LaunchedEffect
        }

        val fallback = deriveFallbackGlowColor(fallbackSeed, fallbackColor)
        if (imageUrl.isNullOrBlank()) {
            glowColor = fallback
            return@LaunchedEffect
        }

        glowColor = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .size(96, 96)
                .build()
            val result = context.imageLoader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable ?: return@withContext fallback
            sampledGlowColor(drawable.toBitmap(width = 96, height = 96, config = Bitmap.Config.ARGB_8888))
                ?: fallback
        }
    }

    return remember(glowColor) {
        CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = glowColor,
                elevation = 28.dp
            )
        )
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
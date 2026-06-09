package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioLayoutTokens(
    val tvSafeHorizontal: Dp,
    val tvSafeVertical: Dp,
    val compactSafeHorizontal: Dp,
    val compactSafeVertical: Dp,
    val sidebarContentOffset: Dp,
    val rowAnchor: Float,
    val detailsHeroWidthFraction: Float,
    val detailsHeroHeightFraction: Float
)

@Immutable
data class NuvioMediaTokens(
    val posterAspectRatio: Float,
    val backdropAspectRatio: Float,
    val heroAspectRatio: Float,
    val logoAspectRatio: Float,
    val thumbnailAspectRatio: Float,
    val posterFallbackIconFraction: Float,
    val backdropGradientStops: List<Float>,
    val playerOverlayGradientStops: List<Float>
)

object NuvioLayout {
    val tokens = NuvioLayoutTokens(
        tvSafeHorizontal = 48.dp,
        tvSafeVertical = 24.dp,
        compactSafeHorizontal = 32.dp,
        compactSafeVertical = 16.dp,
        sidebarContentOffset = 72.dp,
        rowAnchor = 0.42f,
        detailsHeroWidthFraction = 0.62f,
        detailsHeroHeightFraction = 0.72f
    )
}

object NuvioMedia {
    val tokens = NuvioMediaTokens(
        posterAspectRatio = 2f / 3f,
        backdropAspectRatio = 16f / 9f,
        heroAspectRatio = 16f / 9f,
        logoAspectRatio = 190f / 44f,
        thumbnailAspectRatio = 16f / 9f,
        posterFallbackIconFraction = 0.28f,
        backdropGradientStops = listOf(0f, 0.55f, 1f),
        playerOverlayGradientStops = listOf(0f, 0.65f, 1f)
    )
}

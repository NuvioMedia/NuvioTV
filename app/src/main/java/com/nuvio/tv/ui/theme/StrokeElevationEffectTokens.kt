package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioStrokeTokens(
    val none: Dp,
    val hairline: Dp,
    val thin: Dp,
    val medium: Dp,
    val focus: Dp,
    val heavy: Dp,
    val progress: Dp,
    val divider: Dp
)

@Immutable
data class NuvioElevationTokens(
    val none: Dp,
    val card: Dp,
    val focused: Dp,
    val menu: Dp,
    val dialog: Dp,
    val overlay: Dp
)

@Immutable
data class NuvioEffectTokens(
    val blurSoft: Dp,
    val blurPanel: Dp,
    val blurStrong: Dp,
    val scrimLightAlpha: Float,
    val scrimMediumAlpha: Float,
    val scrimStrongAlpha: Float,
    val glowSoftAlpha: Float,
    val glowStrongAlpha: Float,
    val imageOverlayAlpha: Float,
    val disabledAlpha: Float,
    val shimmerLowAlpha: Float,
    val shimmerHighAlpha: Float
)

object NuvioStrokes {
    val tokens = NuvioStrokeTokens(
        none = 0.dp,
        hairline = 1.dp,
        thin = 1.5.dp,
        medium = 2.dp,
        focus = 2.dp,
        heavy = 3.dp,
        progress = 4.dp,
        divider = 1.dp
    )
}

object NuvioElevations {
    val tokens = NuvioElevationTokens(
        none = 0.dp,
        card = 2.dp,
        focused = 8.dp,
        menu = 8.dp,
        dialog = 12.dp,
        overlay = 16.dp
    )
}

object NuvioEffects {
    val tokens = NuvioEffectTokens(
        blurSoft = 12.dp,
        blurPanel = 26.dp,
        blurStrong = 40.dp,
        scrimLightAlpha = 0.28f,
        scrimMediumAlpha = 0.52f,
        scrimStrongAlpha = 0.78f,
        glowSoftAlpha = 0.18f,
        glowStrongAlpha = 0.38f,
        imageOverlayAlpha = 0.62f,
        disabledAlpha = 0.42f,
        shimmerLowAlpha = 0.08f,
        shimmerHighAlpha = 0.18f
    )
}

package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color

private val goldGradient = listOf(
    Color(0xFF8A5700),
    Color(0xFFE8A91C),
    Color(0xFFFFF1A8),
    Color(0xFFFFD45C),
    Color(0xFF9A6200)
)

private val jadeGradient = listOf(
    Color(0xFF7BF08D),
    Color(0xFF22D37C),
    Color(0xFF0BBF9A)
)

private val roseGoldGradient = listOf(
    Color(0xFFB75AFF),
    Color(0xFFEC70A9),
    Color(0xFFFFB37A)
)

private val arcticBlueGradient = listOf(
    Color(0xFF4DE3FF),
    Color(0xFF3185F5),
    Color(0xFF4D55E8)
)

private val graphiteGradient = listOf(
    Color(0xFFF3F5F7),
    Color(0xFFAAB2BE),
    Color(0xFF687381)
)

object SupporterThemeColors {
    val Gold = ThemeColorPalette(
        secondary = Color(0xFFE8A91C),
        secondaryVariant = Color(0xFF9A6200),
        onSecondary = NuvioPrimitives.neutral925,
        onSecondaryVariant = NuvioPrimitives.white,
        accentGradient = goldGradient,
        focusRing = Color(0xFFFFD45C),
        focusRingGradient = goldGradient,
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0E0B),
        backgroundElevated = Color(0xFF1D1A14),
        backgroundCard = Color(0xFF262116),
        surface = Color(0xFF211D16),
        surfaceVariant = Color(0xFF302A1D),
        panel = Color(0xFF1D1A14),
        field = Color(0xFF292318),
        menu = Color(0xFF211D16),
        modal = Color(0xFF1D1A14)
    )

    val Jade = ThemeColorPalette(
        secondary = Color(0xFF22D37C),
        secondaryVariant = Color(0xFF0BBF9A),
        onSecondary = NuvioPrimitives.neutral925,
        onSecondaryVariant = NuvioPrimitives.neutral925,
        accentGradient = jadeGradient,
        focusRing = Color(0xFF7BF08D),
        focusRingGradient = jadeGradient,
        focusBackground = Color(0xFF153A2C),
        background = Color(0xFF0B0F0D),
        backgroundElevated = Color(0xFF141D18),
        backgroundCard = Color(0xFF16251D),
        surface = Color(0xFF17221C),
        surfaceVariant = Color(0xFF203128),
        panel = Color(0xFF141D18),
        field = Color(0xFF1B2A22),
        menu = Color(0xFF17221C),
        modal = Color(0xFF141D18)
    )

    val RoseGold = ThemeColorPalette(
        secondary = Color(0xFFEC70A9),
        secondaryVariant = Color(0xFFB75AFF),
        onSecondary = NuvioPrimitives.neutral925,
        accentGradient = roseGoldGradient,
        focusRing = Color(0xFFFFB37A),
        focusRingGradient = roseGoldGradient,
        focusBackground = Color(0xFF442037),
        background = Color(0xFF100C0F),
        backgroundElevated = Color(0xFF1F161D),
        backgroundCard = Color(0xFF281A24),
        surface = Color(0xFF241921),
        surfaceVariant = Color(0xFF34242F),
        panel = Color(0xFF1F161D),
        field = Color(0xFF2C1E28),
        menu = Color(0xFF241921),
        modal = Color(0xFF1F161D)
    )

    val ArcticBlue = ThemeColorPalette(
        secondary = Color(0xFF3185F5),
        secondaryVariant = Color(0xFF4D55E8),
        accentGradient = arcticBlueGradient,
        focusRing = Color(0xFF4DE3FF),
        focusRingGradient = arcticBlueGradient,
        focusBackground = Color(0xFF172844),
        background = Color(0xFF0B0E14),
        backgroundElevated = Color(0xFF141A24),
        backgroundCard = Color(0xFF161E2A),
        surface = Color(0xFF171F2C),
        surfaceVariant = Color(0xFF202B3B),
        panel = Color(0xFF141A24),
        field = Color(0xFF1B2533),
        menu = Color(0xFF171F2C),
        modal = Color(0xFF141A24)
    )

    val Graphite = ThemeColorPalette(
        secondary = Color(0xFFAAB2BE),
        secondaryVariant = Color(0xFF687381),
        onSecondary = NuvioPrimitives.neutral925,
        accentGradient = graphiteGradient,
        focusRing = Color(0xFFF3F5F7),
        focusRingGradient = graphiteGradient,
        focusBackground = Color(0xFF30343A),
        background = Color(0xFF0C0D0F),
        backgroundElevated = Color(0xFF17191D),
        backgroundCard = Color(0xFF20242A),
        surface = Color(0xFF1C1F23),
        surfaceVariant = Color(0xFF292E35),
        panel = Color(0xFF17191D),
        field = Color(0xFF24282E),
        menu = Color(0xFF1C1F23),
        modal = Color(0xFF17191D)
    )
}

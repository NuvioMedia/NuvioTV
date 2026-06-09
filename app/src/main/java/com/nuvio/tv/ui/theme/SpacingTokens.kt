package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioScreenSpacing(
    val horizontal: Dp,
    val vertical: Dp,
    val compactHorizontal: Dp,
    val compactVertical: Dp,
    val overscanHorizontal: Dp,
    val overscanVertical: Dp
)

@Immutable
data class NuvioRailSpacing(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val rowGap: Dp,
    val itemGap: Dp,
    val headerBottom: Dp,
    val tailPadding: Dp
)

@Immutable
data class NuvioPanelSpacing(
    val outer: Dp,
    val inner: Dp,
    val gap: Dp,
    val compactGap: Dp
)

@Immutable
data class NuvioSpacingTokens(
    val none: Dp,
    val hairline: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val xxxl: Dp,
    val huge: Dp,
    val screen: NuvioScreenSpacing,
    val rail: NuvioRailSpacing,
    val card: NuvioPanelSpacing,
    val dialog: NuvioPanelSpacing,
    val sidePanel: NuvioPanelSpacing,
    val player: NuvioPanelSpacing,
    val settings: NuvioPanelSpacing
)

object NuvioSpacing {
    val tokens = NuvioSpacingTokens(
        none = 0.dp,
        hairline = 1.dp,
        xxs = 2.dp,
        xs = 4.dp,
        sm = 8.dp,
        md = 12.dp,
        lg = 16.dp,
        xl = 24.dp,
        xxl = 32.dp,
        xxxl = 48.dp,
        huge = 56.dp,
        screen = NuvioScreenSpacing(
            horizontal = 48.dp,
            vertical = 24.dp,
            compactHorizontal = 32.dp,
            compactVertical = 16.dp,
            overscanHorizontal = 56.dp,
            overscanVertical = 36.dp
        ),
        rail = NuvioRailSpacing(
            horizontalPadding = 48.dp,
            verticalPadding = 6.dp,
            rowGap = 24.dp,
            itemGap = 12.dp,
            headerBottom = 14.dp,
            tailPadding = 200.dp
        ),
        card = NuvioPanelSpacing(
            outer = 12.dp,
            inner = 16.dp,
            gap = 12.dp,
            compactGap = 8.dp
        ),
        dialog = NuvioPanelSpacing(
            outer = 32.dp,
            inner = 24.dp,
            gap = 16.dp,
            compactGap = 12.dp
        ),
        sidePanel = NuvioPanelSpacing(
            outer = 36.dp,
            inner = 20.dp,
            gap = 16.dp,
            compactGap = 10.dp
        ),
        player = NuvioPanelSpacing(
            outer = 52.dp,
            inner = 16.dp,
            gap = 14.dp,
            compactGap = 8.dp
        ),
        settings = NuvioPanelSpacing(
            outer = 32.dp,
            inner = 20.dp,
            gap = 16.dp,
            compactGap = 12.dp
        )
    )
}

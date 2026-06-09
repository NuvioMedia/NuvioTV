package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioCardComponentTokens(
    val width: Dp,
    val height: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val focusedBorderWidth: Dp,
    val focusedScale: Float
)

@Immutable
data class NuvioRowComponentTokens(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val itemSpacing: Dp,
    val titleBottomSpacing: Dp
)

@Immutable
data class NuvioSidebarComponentTokens(
    val legacyCollapsedWidth: Dp,
    val legacyExpandedWidth: Dp,
    val collapsedWidth: Dp,
    val expandedWidth: Dp,
    val itemHeight: Dp,
    val itemWidth: Dp,
    val iconSize: Dp,
    val leadingVisual: Dp,
    val panelRadius: Dp,
    val contentGap: Dp
)

@Immutable
data class NuvioDialogComponentTokens(
    val maxWidth: Dp,
    val contentPadding: Dp,
    val cornerRadius: Dp,
    val actionSpacing: Dp
)

@Immutable
data class NuvioPlayerComponentTokens(
    val overlayHorizontalPadding: Dp,
    val overlayVerticalPadding: Dp,
    val controlSize: Dp,
    val sidePanelWidth: Dp,
    val railWidth: Dp,
    val progressHeight: Dp
)

@Immutable
data class NuvioSettingsComponentTokens(
    val containerRadius: Dp,
    val secondaryCardRadius: Dp,
    val railItemHeight: Dp,
    val workspacePadding: Dp,
    val rowGap: Dp
)

@Immutable
data class NuvioComponentTokens(
    val posterCard: NuvioCardComponentTokens,
    val backdropCard: NuvioCardComponentTokens,
    val collectionCard: NuvioCardComponentTokens,
    val continueWatchingCard: NuvioCardComponentTokens,
    val episodeCard: NuvioCardComponentTokens,
    val row: NuvioRowComponentTokens,
    val sidebar: NuvioSidebarComponentTokens,
    val dialog: NuvioDialogComponentTokens,
    val sidePanel: NuvioDialogComponentTokens,
    val settings: NuvioSettingsComponentTokens,
    val player: NuvioPlayerComponentTokens,
    val buttonHeight: Dp,
    val chipHeight: Dp,
    val badgeHeight: Dp,
    val skeletonCornerRadius: Dp
)

object NuvioComponents {
    val tokens = NuvioComponentTokens(
        posterCard = NuvioCardComponentTokens(
            width = 126.dp,
            height = 189.dp,
            cornerRadius = 12.dp,
            contentPadding = 8.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        ),
        backdropCard = NuvioCardComponentTokens(
            width = 320.dp,
            height = 180.dp,
            cornerRadius = 16.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        ),
        collectionCard = NuvioCardComponentTokens(
            width = 320.dp,
            height = 180.dp,
            cornerRadius = 16.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        ),
        continueWatchingCard = NuvioCardComponentTokens(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = 12.dp,
            contentPadding = 12.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        ),
        episodeCard = NuvioCardComponentTokens(
            width = 320.dp,
            height = 207.dp,
            cornerRadius = 16.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        ),
        row = NuvioRowComponentTokens(
            horizontalPadding = 48.dp,
            verticalPadding = 6.dp,
            itemSpacing = 12.dp,
            titleBottomSpacing = 14.dp
        ),
        sidebar = NuvioSidebarComponentTokens(
            legacyCollapsedWidth = 72.dp,
            legacyExpandedWidth = 196.dp,
            collapsedWidth = 184.dp,
            expandedWidth = 262.dp,
            itemHeight = 52.dp,
            itemWidth = 148.dp,
            iconSize = 22.dp,
            leadingVisual = 34.dp,
            panelRadius = 30.dp,
            contentGap = 14.dp
        ),
        dialog = NuvioDialogComponentTokens(
            maxWidth = 720.dp,
            contentPadding = 24.dp,
            cornerRadius = 16.dp,
            actionSpacing = 12.dp
        ),
        sidePanel = NuvioDialogComponentTokens(
            maxWidth = 420.dp,
            contentPadding = 20.dp,
            cornerRadius = 20.dp,
            actionSpacing = 12.dp
        ),
        settings = NuvioSettingsComponentTokens(
            containerRadius = 28.dp,
            secondaryCardRadius = 18.dp,
            railItemHeight = 56.dp,
            workspacePadding = 20.dp,
            rowGap = 16.dp
        ),
        player = NuvioPlayerComponentTokens(
            overlayHorizontalPadding = 52.dp,
            overlayVerticalPadding = 36.dp,
            controlSize = 44.dp,
            sidePanelWidth = 360.dp,
            railWidth = 280.dp,
            progressHeight = 4.dp
        ),
        buttonHeight = 52.dp,
        chipHeight = 32.dp,
        badgeHeight = 20.dp,
        skeletonCornerRadius = 10.dp
    )
}

package com.nuvio.tv.ui.screens.anime

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem

@Immutable
data class AnimeHomeUiState(
    val rows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val upcomingItems: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val heroEnabled: Boolean = true,
    val heroItem: MetaPreview? = null,
    val heroItems: List<MetaPreview> = emptyList(),
    val heroAddonBaseUrl: String? = null,
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val catalogTypeSuffixEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val modernLandscapePostersEnabled: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = false,
    val classicFocusGradientEnabled: Boolean = false,
    val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val blurContinueWatchingNextUp: Boolean = false,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val posterLabelsEnabled: Boolean = true,
    val focusedPosterBackdropExpandEnabled: Boolean = true,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val showFullReleaseDate: Boolean = true
)

sealed class AnimeHomeEvent {
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String, val type: String) : AnimeHomeEvent()
    data object OnRetry : AnimeHomeEvent()
    data class OnItemFocus(val item: MetaPreview) : AnimeHomeEvent()
}

package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress

@Immutable
data class HomeUiState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedItemId: String? = null,
    val installedAddonsCount: Int = 0,
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val modernLandscapePostersEnabled: Boolean = false,
    val modernNextRowPreviewEnabled: Boolean = true,
    val heroItems: List<MetaPreview> = emptyList(),
    val heroCatalogKeys: List<String> = emptyList(),
    val heroSectionEnabled: Boolean = true,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val gridItems: List<GridItem> = emptyList()
)

@Immutable
sealed class ContinueWatchingItem {
    @Immutable
    data class InProgress(
        val progress: WatchProgress,
        val episodeDescription: String? = null,
        val episodeThumbnail: String? = null
    ) : ContinueWatchingItem()

    @Immutable
    data class NextUp(val info: NextUpInfo) : ContinueWatchingItem()
}

@Immutable
data class NextUpInfo(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long
)

@Immutable
sealed class GridItem {
    @Immutable
    data class Hero(val items: List<MetaPreview>) : GridItem()
    @Immutable
    data class SectionDivider(
        val catalogName: String,
        val catalogId: String,
        val addonBaseUrl: String,
        val addonId: String,
        val type: String
    ) : GridItem()
    @Immutable
    data class Content(
        val item: MetaPreview,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String
    ) : GridItem()
    @Immutable
    data class SeeAll(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : GridItem()
}

sealed class HomeEvent {
    data class OnItemClick(val itemId: String, val itemType: String) : HomeEvent()
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String, val type: String) : HomeEvent()
    data class OnRemoveContinueWatching(
        val contentId: String,
        val season: Int? = null,
        val episode: Int? = null,
        val isNextUp: Boolean = false
    ) : HomeEvent()
    data object OnRetry : HomeEvent()
}

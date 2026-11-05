package com.nuvio.tv.ui.screens.extra

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicFocusArtwork
import com.nuvio.tv.ui.screens.home.ClassicFocusGradientBackdrop
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.firstNonBlank
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.rowsListUpRecovery
import com.nuvio.tv.ui.util.rowsListDownRecovery
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.focusRestorer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExtraHomeScreen(
    viewModel: ExtraHomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = uiState.rows

    // Sfondo opaco: copre la schermata uscente finché questa non è pronta,
    // altrimenti la barra sezioni già aggiornata si sovrappone a quella vecchia (issue #11).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        when {
            uiState.isLoading && rows.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }

            uiState.installedAddonsCount == 0 -> {
                val tabName = uiState.extraTabName.ifBlank { stringResource(R.string.nav_extra) }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.extra_home_empty_title, tabName),
                        subtitle = stringResource(R.string.extra_home_empty_subtitle),
                        icon = Icons.Default.Extension
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.extra_home_empty_action))
                    }
                }
            }

            uiState.error != null && rows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(ExtraHomeEvent.OnRetry) }
                )
            }

            rows.isEmpty() -> {
                val tabName = uiState.extraTabName.ifBlank { stringResource(R.string.nav_extra) }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.extra_home_empty_title, tabName),
                        subtitle = stringResource(R.string.extra_home_empty_subtitle),
                        icon = Icons.Default.Extension
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.extra_home_empty_action))
                    }
                }
            }

            else -> {
                val onRemoveContinueWatching: (ContinueWatchingItem) -> Unit = viewModel::removeContinueWatching
                when (uiState.homeLayout) {
                    HomeLayout.MODERN -> ExtraModernContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning
                    )
                    HomeLayout.CLASSIC -> ExtraClassicContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning
                    )
                    HomeLayout.GRID -> ExtraGridContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning
                    )
                }
            }
        }
    }
}

private fun extraPosterCardStyle(uiState: ExtraHomeUiState): PosterCardStyle {
    return PosterCardStyle(
        width = uiState.posterCardWidthDp.dp,
        height = uiState.posterCardHeightDp.dp,
        cornerRadius = uiState.posterCardCornerRadiusDp.dp
    )
}

private fun extraCwCardSize(uiState: ExtraHomeUiState): Pair<Dp, Dp> {
    val baseWidth = uiState.posterCardWidthDp.dp
    val baseHeight = uiState.posterCardHeightDp.dp
    return when (uiState.continueWatchingCardStyle) {
        ContinueWatchingCardStyle.POSTER -> baseWidth to baseHeight
        ContinueWatchingCardStyle.WIDE -> baseWidth * 2.5f to baseWidth * 2.5f * 0.4f
        ContinueWatchingCardStyle.CARD -> baseWidth * (16f / 9f) to baseWidth
    }
}

private fun MetaPreview.toExtraClassicFocusArtwork(useBackdrop: Boolean): ClassicFocusArtwork =
    ClassicFocusArtwork(
        imageUrl = if (useBackdrop) {
            firstNonBlank(background, landscapePoster, poster)
        } else {
            firstNonBlank(poster, landscapePoster, background)
        },
        seed = "$id|$name|$apiType"
    )

private fun ContinueWatchingItem.toExtraClassicFocusArtwork(useBackdrop: Boolean): ClassicFocusArtwork =
    when (this) {
        is ContinueWatchingItem.InProgress -> ClassicFocusArtwork(
            imageUrl = if (useBackdrop) {
                firstNonBlank(episodeThumbnail, progress.backdrop, progress.poster)
            } else {
                firstNonBlank(progress.poster, episodeThumbnail, progress.backdrop)
            },
            seed = "${progress.contentId}|${progress.name}|${progress.contentType}"
        )
        is ContinueWatchingItem.NextUp -> ClassicFocusArtwork(
            imageUrl = if (useBackdrop) {
                firstNonBlank(info.backdrop, info.thumbnail, info.poster)
            } else {
                firstNonBlank(info.poster, info.thumbnail, info.backdrop)
            },
            seed = "${info.contentId}|${info.name}|${info.contentType}"
        )
    }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraClassicContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit
) {
    var focusedArtwork by remember { mutableStateOf<ClassicFocusArtwork?>(null) }
    LaunchedEffect(uiState.classicFocusGradientEnabled) {
        if (!uiState.classicFocusGradientEnabled) focusedArtwork = null
    }
    val handleMetaFocus: (MetaPreview) -> Unit = { item ->
        if (uiState.classicFocusGradientEnabled) {
            focusedArtwork = item.toExtraClassicFocusArtwork(uiState.focusedPosterBackdropExpandEnabled)
        }
    }
    val posterCardStyle = extraPosterCardStyle(uiState)
    val (cwCardWidth, cwCardHeight) = extraCwCardSize(uiState)

    Box(modifier = Modifier.fillMaxSize()) {
        ClassicFocusGradientBackdrop(
            artworkProvider = { focusedArtwork },
            enabled = uiState.classicFocusGradientEnabled,
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
        )
        val rowsListState = rememberLazyListState()
        LazyColumn(
            state = rowsListState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(LocalContentFocusRequester.current)
                .focusRestorer()
                .dpadRepeatThrottle(scrollableState = rowsListState)
                .rowsListUpRecovery(rowsListState)
                .rowsListDownRecovery(rowsListState),
            contentPadding = PaddingValues(top = 72.dp, bottom = NuvioTheme.spacing.xxl)
        ) {
            if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
                item(key = "extra_hero") {
                    HeroCarousel(
                        items = uiState.heroItems.asStable(),
                        onItemClick = { item ->
                            onNavigateToDetail(item.id, item.rawType, uiState.heroAddonBaseUrl.orEmpty())
                        },
                        // Same as main/anime classic heroes: the hero owns its backdrop,
                        // clearing keeps stale catalog art from bleeding into the gradient.
                        onItemFocus = { focusedArtwork = null },
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                    )
                }
            }

            if (uiState.continueWatchingItems.isNotEmpty()) {
                item(key = "extra_continue_watching") {
                    ContinueWatchingSection(
                        items = uiState.continueWatchingItems,
                        title = stringResource(R.string.continue_watching),
                        onItemClick = onContinueWatchingClick,
                        onRemoveItem = onRemoveContinueWatching,
                        onStartFromBeginning = onContinueWatchingStartFromBeginning,
                        cardWidth = cwCardWidth,
                        imageHeight = cwCardHeight,
                        cardStyle = uiState.continueWatchingCardStyle,
                        cornerRadius = posterCardStyle.cornerRadius,
                        blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                        onItemFocused = { itemIndex ->
                            if (uiState.classicFocusGradientEnabled) {
                                focusedArtwork = uiState.continueWatchingItems
                                    .getOrNull(itemIndex)
                                    ?.toExtraClassicFocusArtwork(uiState.focusedPosterBackdropExpandEnabled)
                            }
                        },
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
                    )
                }
            }

            if (uiState.upcomingItems.isNotEmpty()) {
                item(key = "extra_upcoming") {
                    ContinueWatchingSection(
                        items = uiState.upcomingItems,
                        title = stringResource(R.string.cw_upcoming),
                        onItemClick = onContinueWatchingClick,
                        onRemoveItem = onRemoveContinueWatching,
                        onStartFromBeginning = onContinueWatchingStartFromBeginning,
                        cardWidth = cwCardWidth,
                        imageHeight = cwCardHeight,
                        cardStyle = uiState.continueWatchingCardStyle,
                        cornerRadius = posterCardStyle.cornerRadius,
                        blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
                    )
                }
            }

            items(
                items = uiState.rows,
                key = { row -> row.legacyKey() }
            ) { row ->
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = onNavigateToDetail,
                    onSeeAll = {
                        onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                    },
                    showSeeAll = row.hasMore || row.items.size >= 15,
                    posterCardStyle = posterCardStyle,
                    showPosterLabels = uiState.posterLabelsEnabled,
                    showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                    focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                    focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                    onItemFocus = handleMetaFocus
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraModernContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit
) {
    val posterCardStyle = extraPosterCardStyle(uiState)
    val (cwCardWidth, cwCardHeight) = extraCwCardSize(uiState)

    val rowsListState = rememberLazyListState()
    LazyColumn(
        state = rowsListState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(LocalContentFocusRequester.current)
            .focusRestorer()
            .dpadRepeatThrottle(scrollableState = rowsListState)
            .rowsListUpRecovery(rowsListState)
            .rowsListDownRecovery(rowsListState),
        contentPadding = PaddingValues(top = 72.dp, bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
            item(key = "extra_hero") {
                HeroCarousel(
                    items = uiState.heroItems.asStable(),
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.rawType, uiState.heroAddonBaseUrl.orEmpty())
                    },
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty()) {
            item(key = "extra_continue_watching") {
                ContinueWatchingSection(
                    items = uiState.continueWatchingItems,
                    title = stringResource(R.string.continue_watching),
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    cardWidth = cwCardWidth,
                    imageHeight = cwCardHeight,
                    cardStyle = uiState.continueWatchingCardStyle,
                    cornerRadius = posterCardStyle.cornerRadius,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
                )
            }
        }

        if (uiState.upcomingItems.isNotEmpty()) {
            item(key = "extra_upcoming") {
                ContinueWatchingSection(
                    items = uiState.upcomingItems,
                    title = stringResource(R.string.cw_upcoming),
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    cardWidth = cwCardWidth,
                    imageHeight = cwCardHeight,
                    cardStyle = uiState.continueWatchingCardStyle,
                    cornerRadius = posterCardStyle.cornerRadius,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
                )
            }
        }

        items(
            items = uiState.rows,
            key = { row -> row.legacyKey() }
        ) { row ->
            CatalogRowSection(
                catalogRow = row,
                onItemClick = onNavigateToDetail,
                onSeeAll = {
                    onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                },
                showSeeAll = row.hasMore || row.items.size >= 15,
                posterCardStyle = posterCardStyle,
                showPosterLabels = uiState.posterLabelsEnabled,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraGridContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit
) {
    val posterCardStyle = extraPosterCardStyle(uiState)

    val rowsListState = rememberLazyListState()
    LazyColumn(
        state = rowsListState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(LocalContentFocusRequester.current)
            .focusRestorer()
            .dpadRepeatThrottle(scrollableState = rowsListState)
            .rowsListUpRecovery(rowsListState)
            .rowsListDownRecovery(rowsListState),
        contentPadding = PaddingValues(top = 72.dp, bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
            item(key = "extra_hero") {
                HeroCarousel(
                    items = uiState.heroItems.asStable(),
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.rawType, uiState.heroAddonBaseUrl.orEmpty())
                    },
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty()) {
            item(key = "extra_continue_watching") {
                GridContinueWatchingSection(
                    items = uiState.continueWatchingItems,
                    title = stringResource(R.string.continue_watching),
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    cardStyle = uiState.continueWatchingCardStyle,
                    cornerRadius = posterCardStyle.cornerRadius,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw
                )
            }
        }

        if (uiState.upcomingItems.isNotEmpty()) {
            item(key = "extra_upcoming") {
                GridContinueWatchingSection(
                    items = uiState.upcomingItems,
                    title = stringResource(R.string.cw_upcoming),
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    cardStyle = uiState.continueWatchingCardStyle,
                    cornerRadius = posterCardStyle.cornerRadius,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw
                )
            }
        }

        items(
            items = uiState.rows,
            key = { row -> row.legacyKey() }
        ) { row ->
            CatalogRowSection(
                catalogRow = row,
                onItemClick = onNavigateToDetail,
                onSeeAll = {
                    onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                },
                showSeeAll = row.hasMore || row.items.size >= 15,
                posterCardStyle = posterCardStyle,
                showPosterLabels = uiState.posterLabelsEnabled,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
            )
        }
    }
}


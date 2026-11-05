package com.nuvio.tv.ui.screens.anime

import com.nuvio.tv.core.perf.LocalFluidMode
import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.focusRequester
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.util.rowsListUpRecovery
import com.nuvio.tv.ui.util.rowsListDownRecovery
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.TOP_BAR_BAND_HEIGHT
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.ui.screens.home.ClassicFocusArtwork
import com.nuvio.tv.ui.screens.home.ClassicFocusGradientBackdrop
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.HeroTitleBlock
import com.nuvio.tv.ui.screens.home.MODERN_HERO_MEDIA_WIDTH_FRACTION
import com.nuvio.tv.ui.screens.home.MODERN_HERO_TEXT_WIDTH_FRACTION
import com.nuvio.tv.ui.screens.home.MODERN_ROW_HEADER_FOCUS_INSET
import com.nuvio.tv.ui.screens.home.ModernHeroScene
import com.nuvio.tv.ui.screens.home.ModernHeroSceneState
import com.nuvio.tv.ui.screens.home.extractYearText
import com.nuvio.tv.ui.screens.home.firstNonBlank
import com.nuvio.tv.ui.screens.home.formatHeroRuntime
import com.nuvio.tv.ui.screens.home.isSeriesType
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedLanguageText
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.focus.focusRestorer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeHomeScreen(
    viewModel: AnimeHomeViewModel = hiltViewModel(),
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
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.anime_home_empty_title),
                        subtitle = stringResource(R.string.anime_home_empty_subtitle),
                        icon = Icons.Default.FilterDrama
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.anime_home_empty_action))
                    }
                }
            }

            uiState.error != null && rows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(AnimeHomeEvent.OnRetry) }
                )
            }

            rows.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.anime_home_no_catalogs_title),
                        subtitle = stringResource(R.string.anime_home_no_catalogs_subtitle),
                        icon = Icons.Default.FilterDrama
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.anime_home_empty_action))
                    }
                }
            }

            else -> {
                val onRemoveContinueWatching: (ContinueWatchingItem) -> Unit = viewModel::removeContinueWatching
                val enrichHeroItem: suspend (MetaPreview) -> MetaPreview? = viewModel::enrichAnimeHeroItem
                when (uiState.homeLayout) {
                    HomeLayout.MODERN -> AnimeModernContent(
                        uiState = uiState,
                        trailerPreviewUrls = viewModel.trailerPreviewUrls,
                        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
                        onRequestTrailerPreview = viewModel::requestTrailerPreview,
                        enrichHeroItem = enrichHeroItem,
                        onNavigateToDetail = onNavigateToDetail,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onLoadMoreCatalog = viewModel::loadMoreCatalogItems,
                        onItemFocus = { viewModel.onEvent(AnimeHomeEvent.OnItemFocus(it)) }
                    )
                    HomeLayout.CLASSIC -> AnimeClassicContent(
                        uiState = uiState,
                        trailerPreviewUrls = viewModel.trailerPreviewUrls,
                        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
                        onRequestTrailerPreview = viewModel::requestTrailerPreview,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onItemFocus = { viewModel.onEvent(AnimeHomeEvent.OnItemFocus(it)) }
                    )
                    HomeLayout.GRID -> AnimeGridContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll, onContinueWatchingClick = onContinueWatchingClick, onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning, onRemoveContinueWatching = onRemoveContinueWatching)
                }
            }
        }
    }
}

@Composable
private fun AnimeHeroItem(
    uiState: AnimeHomeUiState,
    onOpen: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {}
) {
    if (!uiState.heroEnabled || uiState.heroItems.isEmpty()) return
    HeroCarousel(
        items = uiState.heroItems.asStable(),
        onItemClick = { item ->
            onOpen(item, uiState.heroAddonBaseUrl.orEmpty())
        },
        onItemFocus = onItemFocus,
        modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AnimeModernContent(
    uiState: AnimeHomeUiState,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    enrichHeroItem: suspend (MetaPreview) -> MetaPreview?,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit
) {
    val defaultHeroItem = uiState.heroItem
    var focusedHeroItem by remember {
        mutableStateOf(defaultHeroItem)
    }
    val heroItem = focusedHeroItem ?: defaultHeroItem
    var enrichedHeroItem by remember(heroItem) { mutableStateOf(heroItem) }
    LaunchedEffect(heroItem, uiState.focusedPosterBackdropTrailerEnabled) {
        val current = heroItem ?: return@LaunchedEffect
        enrichedHeroItem = current
        val enriched = enrichHeroItem(current)
        if (enriched != null) enrichedHeroItem = enriched
        if (uiState.focusedPosterBackdropTrailerEnabled) {
            // Request AFTER enrichment so kitsu/mal/anilist items already carry
            // their localized YT ids / IMDb id â€” requesting the raw item first
            // would negative-cache them for the session.
            delay(150)
            onRequestTrailerPreview(enriched ?: current)
        }
    }
    val heroEnabled = uiState.heroEnabled && heroItem != null
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    // Mirror the main modern home: exactly one trailer surface is armed by the
    // "Playback target" setting â€” hero or expanded card, never both.
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val heroPlaysTrailer = uiState.focusedPosterBackdropTrailerEnabled &&
        trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    val cardPlaysTrailer =
        trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val rowsViewportHeight = screenHeight * if (useLandscapePosters) 0.49f else 0.52f
    val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
    val rowTitleHeight = with(density) {
        runCatching { rowTitleLineHeight.toDp() }
            .getOrDefault(NuvioTheme.spacing.xl)
    }
    val heroBackdropHeight =
        (screenHeight - rowsViewportHeight + rowTitleHeight + 14.dp).coerceAtMost(screenHeight)

    // Same card metrics as the modern home tab so the rows render identically.
    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    val portraitModernPosterScale = 1.08f
    val landscapeModernPosterScale = 1.34f
    val portraitCatalogCardWidth = portraitBaseWidth * 0.84f * portraitModernPosterScale
    val portraitCatalogCardHeight = portraitBaseHeight * 0.84f * portraitModernPosterScale
    val landscapeCatalogCardWidth = portraitBaseWidth * 1.24f * landscapeModernPosterScale
    val landscapeCatalogCardHeight = landscapeCatalogCardWidth / 1.77f

    // Same continue-watching card metrics as the modern home tab.
    val continueWatchingCardStyle = uiState.continueWatchingCardStyle
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = when (continueWatchingCardStyle) {
        ContinueWatchingCardStyle.POSTER -> portraitCatalogCardWidth
        ContinueWatchingCardStyle.WIDE -> portraitBaseWidth * 2.1f
        ContinueWatchingCardStyle.CARD -> portraitBaseWidth * 1.24f * continueWatchingScale
    }
    val continueWatchingCardHeight = when (continueWatchingCardStyle) {
        ContinueWatchingCardStyle.POSTER -> portraitCatalogCardHeight
        ContinueWatchingCardStyle.WIDE -> continueWatchingCardWidth * 0.4f
        ContinueWatchingCardStyle.CARD -> continueWatchingCardWidth / 1.77f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        heroItem?.let { currentHeroItem ->
            if (heroEnabled) {
                AnimeModernHero(
                    item = enrichedHeroItem ?: currentHeroItem,
                    fullScreenBackdrop = fullScreenBackdrop,
                    useLandscapePosters = useLandscapePosters,
                    showFullReleaseDate = uiState.showFullReleaseDate,
                    trailerPreviewUrl = if (heroPlaysTrailer && !LocalFluidMode.current) {
                        trailerPreviewUrls[currentHeroItem.id]
                    } else {
                        null
                    },
                    trailerPreviewAudioUrl = if (heroPlaysTrailer) {
                        trailerPreviewAudioUrls[currentHeroItem.id]
                    } else {
                        null
                    },
                    trailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    heroBackdropHeight = heroBackdropHeight,
                    rowsViewportHeight = rowsViewportHeight,
                    onOpen = {
                        onNavigateToDetail(
                            currentHeroItem.id,
                            currentHeroItem.rawType,
                            currentHeroItem.sourceAddonBaseUrl ?: uiState.heroAddonBaseUrl.orEmpty()
                        )
                    }
                )
            }
        }

        val rowsListState = rememberLazyListState()
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        // Home-style vertical bring-into-view: pin the focused row header at the
        // target inset so a poster never stops half-cut at the viewport edge.
        val verticalRowBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowsListState, heroEnabled) {
            val topInsetPx = with(density) {
                (MODERN_ROW_HEADER_FOCUS_INSET + (if (heroEnabled) NuvioTheme.spacing.none else 72.dp)).toPx()
            }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> = defaultBringIntoViewSpec.scrollAnimationSpec
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    // Relaxed vertical scroll: only scroll when the row header
                    // leading edge is not already at the target inset.
                    val currentLeadingEdge = offset
                    if (abs(currentLeadingEdge - topInsetPx) < 1f) return 0f
                    val distance = currentLeadingEdge - topInsetPx
                    if (distance < 0f && !rowsListState.canScrollBackward) return 0f
                    return distance
                }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
            LazyColumn(
                state = rowsListState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .then(if (heroEnabled) Modifier.height(rowsViewportHeight) else Modifier.fillMaxSize())
                    .clipToBounds()
                    .focusRequester(LocalContentFocusRequester.current)
                    .focusRestorer()
                    .dpadRepeatThrottle(scrollableState = rowsListState)
                    .rowsListUpRecovery(rowsListState)
                    .rowsListDownRecovery(rowsListState),
                contentPadding = PaddingValues(
                    // Con l'hero attivo le righe occupano solo la fascia bassa;
                    // senza hero partono dalla cima e devono scendere sotto la
                    // barra sezioni.
                    top = if (heroEnabled) NuvioTheme.spacing.none else 72.dp,
                    bottom = rowsViewportHeight
                ),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
            ) {
                if (uiState.continueWatchingItems.isNotEmpty()) {
                    item(key = "anime_continue_watching") {
                        AnimeModernContinueWatchingRow(
                            title = stringResource(R.string.continue_watching),
                            items = uiState.continueWatchingItems,
                            cardStyle = continueWatchingCardStyle,
                            cardWidth = continueWatchingCardWidth,
                            imageHeight = continueWatchingCardHeight,
                            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
                            blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                            onItemClick = onContinueWatchingClick,
                            onDetailsClick = { item -> handleAnimeCwClick(item, onNavigateToDetail) },
                            onStartFromBeginning = onContinueWatchingStartFromBeginning,
                            onRemoveItem = onRemoveContinueWatching,
                            onItemFocus = { focusedHeroItem = it }
                        )
                    }
                }
                if (uiState.upcomingItems.isNotEmpty()) {
                    item(key = "anime_upcoming") {
                        AnimeModernContinueWatchingRow(
                            title = stringResource(R.string.upcoming_section_title),
                            items = uiState.upcomingItems,
                            cardStyle = continueWatchingCardStyle,
                            cardWidth = continueWatchingCardWidth,
                            imageHeight = continueWatchingCardHeight,
                            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
                            blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                            onItemClick = onContinueWatchingClick,
                            onDetailsClick = { item -> handleAnimeCwClick(item, onNavigateToDetail) },
                            onStartFromBeginning = onContinueWatchingStartFromBeginning,
                            onRemoveItem = onRemoveContinueWatching,
                            onItemFocus = { focusedHeroItem = it }
                        )
                    }
                }
    
                items(
                    items = uiState.rows,
                    key = { row -> row.legacyKey() }
                ) { row ->
                    AnimeModernCatalogRow(
                        catalogRow = row,
                        useLandscapePosters = useLandscapePosters,
                        showLabels = uiState.posterLabelsEnabled,
                        posterCardCornerRadius = uiState.posterCardCornerRadiusDp.dp,
                        portraitCardWidth = portraitCatalogCardWidth,
                        portraitCardHeight = portraitCatalogCardHeight,
                        landscapeCardWidth = landscapeCatalogCardWidth,
                        landscapeCardHeight = landscapeCatalogCardHeight,
                        showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                        focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled &&
                            !heroPlaysTrailer,
                        focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                        trailerPreviewUrls = if (cardPlaysTrailer) trailerPreviewUrls else emptyMap(),
                        trailerPreviewAudioUrls = if (cardPlaysTrailer) {
                            trailerPreviewAudioUrls
                        } else {
                            emptyMap()
                        },
                        onItemClick = onNavigateToDetail,
                        onItemFocus = { item ->
                            focusedHeroItem = item
                            onItemFocus(item)
                        },
                        onLoadMoreCatalog = onLoadMoreCatalog
                    )
                }
            }
        }
    }
}

@OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun AnimeModernCatalogRow(
    catalogRow: CatalogRow,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    portraitCardWidth: Dp,
    portraitCardHeight: Dp,
    landscapeCardWidth: Dp,
    landscapeCardHeight: Dp,
    showCatalogTypeSuffix: Boolean,
    focusedPosterBackdropExpandEnabled: Boolean,
    focusedPosterBackdropExpandDelaySeconds: Int,
    focusedPosterBackdropTrailerEnabled: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onItemClick: (String, String, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit
) {
    val catalogContext = LocalContext.current
    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, catalogContext) {
        val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        localizedContentType(catalogContext, raw)
    }
    val rowTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (formattedName.isBlank()) ""
        else if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }
    val titleMediumStyle = MaterialTheme.typography.titleMedium
    val rowTitleStyle = remember(titleMediumStyle) {
        titleMediumStyle.copy(fontWeight = FontWeight.SemiBold)
    }
    val rowStartPadding = 52.dp
    val rowTitleBottom = 14.dp
    val posterCardStyle = remember(
        useLandscapePosters,
        posterCardCornerRadius,
        portraitCardWidth,
        portraitCardHeight,
        landscapeCardWidth,
        landscapeCardHeight
    ) {
        if (useLandscapePosters) {
            PosterCardStyle(
                width = landscapeCardWidth,
                height = landscapeCardHeight,
                cornerRadius = posterCardCornerRadius
            )
        } else {
            PosterCardStyle(
                width = portraitCardWidth,
                height = portraitCardHeight,
                cornerRadius = posterCardCornerRadius
            )
        }
    }

    val density = LocalDensity.current
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding, isRtl) {
        val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                defaultBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val childSize = abs(size)
                if (isRtl) {
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = containerSize - parentStartOffsetPx.toFloat()
                    val targetForTrailingEdge =
                        if (childSmallerThanParent && initialTarget < childSize) {
                            childSize
                        } else {
                            initialTarget
                        }
                    return (offset + size) - targetForTrailingEdge
                } else {
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = parentStartOffsetPx.toFloat()
                    val spaceAvailable = containerSize - initialTarget

                    val targetForLeadingEdge =
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
                        } else {
                            initialTarget
                        }

                    return offset - targetForLeadingEdge
                }
            }
        }
    }

    val rowListState = rememberLazyListState()
    val currentRow = rememberUpdatedState(catalogRow)
    val catalogId = catalogRow.catalogId
    val addonId = catalogRow.addonId
    val apiType = catalogRow.apiType
    if (catalogRow.supportsSkip && catalogRow.hasMore) {
        LaunchedEffect(catalogId, addonId, rowListState) {
            snapshotFlow {
                val layoutInfo = rowListState.layoutInfo
                val total = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible to total
            }
                .distinctUntilChanged()
                .collect { (lastVisible, total) ->
                    if (total <= 0) return@collect
                    val row = currentRow.value
                    val isNearEnd = lastVisible >= total - 4
                    if (row.hasMore && !row.isLoading && isNearEnd) {
                        onLoadMoreCatalog(catalogId, addonId, apiType)
                    }
                }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = rowTitle,
            style = rowTitleStyle,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = rowStartPadding, bottom = rowTitleBottom)
        )

        val usesPlaceholderShimmer = catalogRow.isLoading &&
            catalogRow.items.firstOrNull()?.poster == PLACEHOLDER_IMAGE_URL
        val placeholderShimmerOffsetState = if (usesPlaceholderShimmer) {
            rememberPlaceholderShimmerOffsetState(label = "animeModernPlaceholderShimmer")
        } else {
            null
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                state = rowListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                itemsIndexed(
                    items = catalogRow.items,
                    key = { index, _ -> catalogRow.stableItemKey(index) }
                ) { _, item ->
                    ContentCard(
                        item = item,
                        posterCardStyle = posterCardStyle,
                        showLabels = showLabels,
                        placeholderShimmerOffsetState = placeholderShimmerOffsetState,
                        focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = focusedPosterBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                        trailerPreviewUrl = trailerPreviewUrls[item.id],
                        trailerPreviewAudioUrl = trailerPreviewAudioUrls[item.id],
                        onFocus = { onItemFocus(it) },
                        onClick = { onItemClick(item.id, item.apiType, catalogRow.addonBaseUrl) }
                    )
                }
            }
        }
    }
}

@OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun AnimeModernContinueWatchingRow(
    title: String,
    items: List<ContinueWatchingItem>,
    cardStyle: ContinueWatchingCardStyle,
    cardWidth: Dp,
    imageHeight: Dp,
    cornerRadius: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onItemFocus: (MetaPreview) -> Unit
) {
    if (items.isEmpty()) return
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val titleMediumStyle = MaterialTheme.typography.titleMedium
    val rowTitleStyle = remember(titleMediumStyle) {
        titleMediumStyle.copy(fontWeight = FontWeight.SemiBold)
    }
    val rowStartPadding = 52.dp

    val density = LocalDensity.current
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding, isRtl) {
        val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                defaultBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val childSize = abs(size)
                if (isRtl) {
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = containerSize - parentStartOffsetPx.toFloat()
                    val targetForTrailingEdge =
                        if (childSmallerThanParent && initialTarget < childSize) {
                            childSize
                        } else {
                            initialTarget
                        }
                    return (offset + size) - targetForTrailingEdge
                } else {
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = parentStartOffsetPx.toFloat()
                    val spaceAvailable = containerSize - initialTarget

                    val targetForLeadingEdge =
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
                        } else {
                            initialTarget
                        }

                    return offset - targetForLeadingEdge
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = rowTitleStyle,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = rowStartPadding, bottom = 14.dp)
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> continueWatchingItemKey(item) }
                ) { _, item ->
                    var cardFocused by remember { mutableStateOf(false) }
                    ContinueWatchingCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = { optionsItem = item },
                        cardWidth = cardWidth,
                        imageHeight = imageHeight,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        useEpisodeThumbnails = useEpisodeThumbnails,
                        cardStyle = cardStyle,
                        cornerRadius = cornerRadius,
                        isFocused = cardFocused,
                        modifier = Modifier.onFocusChanged { state ->
                            cardFocused = state.isFocused
                            if (state.isFocused) {
                                continueWatchingItemToMetaPreview(item)?.let { onItemFocus(it) }
                            }
                        }
                    )
                }
            }
        }
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                onRemoveItem(selectedOptionsItem)
                optionsItem = null
            },
            onDetails = {
                onDetailsClick(selectedOptionsItem)
                optionsItem = null
            },
            onStartFromBeginning = {
                onStartFromBeginning(selectedOptionsItem)
                optionsItem = null
            }
        )
    }
}

private fun continueWatchingItemKey(item: ContinueWatchingItem): String = when (item) {
    is ContinueWatchingItem.InProgress -> "in_progress:${item.progress.contentId}:${item.progress.videoId}"
    is ContinueWatchingItem.NextUp -> "next_up:${item.info.contentId}:${item.info.videoId}"
}

private fun continueWatchingItemToMetaPreview(item: ContinueWatchingItem): MetaPreview? = when (item) {
    is ContinueWatchingItem.InProgress -> {
        val progress = item.progress
        MetaPreview(
            id = progress.contentId,
            type = ContentType.fromString(progress.contentType),
            name = progress.name,
            poster = progress.poster,
            posterShape = PosterShape.POSTER,
            background = progress.backdrop,
            logo = progress.logo,
            description = item.episodeDescription,
            releaseInfo = item.releaseInfo,
            imdbRating = item.episodeImdbRating,
            genres = item.genres,
            sourceAddonBaseUrl = progress.addonBaseUrl
        )
    }
    is ContinueWatchingItem.NextUp -> {
        val info = item.info
        MetaPreview(
            id = info.contentId,
            type = ContentType.fromString(info.contentType),
            name = info.name,
            poster = info.poster,
            posterShape = PosterShape.POSTER,
            background = info.backdrop,
            logo = info.logo,
            description = info.episodeDescription,
            releaseInfo = info.releaseInfo,
            imdbRating = info.imdbRating,
            genres = info.genres,
            landscapePoster = info.thumbnail,
            sourceAddonBaseUrl = info.addonBaseUrl
        )
    }
}

@Composable
private fun AnimeModernHero(
    item: MetaPreview,
    fullScreenBackdrop: Boolean,
    useLandscapePosters: Boolean,
    showFullReleaseDate: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    trailerMuted: Boolean,
    screenWidth: Dp,
    screenHeight: Dp,
    heroBackdropHeight: Dp,
    rowsViewportHeight: Dp,
    onOpen: () -> Unit
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val heroPreview = remember(item, showFullReleaseDate) {
        buildAnimeHeroPreview(context, item, showFullReleaseDate)
    }
    var trailerFirstFrameRendered by remember { mutableStateOf(false) }
    var trailerEnded by remember { mutableStateOf(false) }
    val trailerPreviewDelaySeconds = 4
    LaunchedEffect(trailerPreviewUrl) {
        trailerFirstFrameRendered = false
        trailerEnded = false
    }
    // Attesa di alcuni secondi prima di mostrare il trailer nella hero,
    // così l'utente ha modo di leggere la descrizione senza che il video
    // prenda il sopravvento immediatamente (come invece succedeva prima).
    LaunchedEffect(trailerPreviewUrl) {
        delay(trailerPreviewDelaySeconds * 1000L)
        // Dopo il delay, ricarichiamo lo stato per riflettere che il trailer
        // può ora partire. Il flag shouldPlayTrailer viene ricalcolato dal
        // compositore padre (ModernHomeHero/AnimeModernContent) quando il
        // valore cambia.
        trailerFirstFrameRendered = false
        trailerEnded = false
    }
    val shouldPlayTrailer = trailerPreviewUrl != null && !trailerEnded
    val liveHeroSceneState by rememberUpdatedState(
        ModernHeroSceneState(
            heroBackdrop = firstNonBlank(
                item.backdropUrl,
                item.background,
                item.landscapePoster,
                item.poster
            ),
            preview = heroPreview,
            enrichmentActive = false,
            shouldPlayTrailer = shouldPlayTrailer,
            trailerFirstFrameRendered = trailerFirstFrameRendered,
            trailerUrl = trailerPreviewUrl,
            trailerAudioUrl = trailerPreviewAudioUrl,
            trailerPlaybackKey = trailerPreviewUrl,
            trailerMuted = trailerMuted,
            fullScreenBackdrop = fullScreenBackdrop
        )
    )
    // Stable lambda reading snapshot state so ModernHeroMediaLayer's
    // derivedStateOf picks up backdrop changes as the focused item moves.
    val heroSceneState = remember { { liveHeroSceneState } }
    val heroMediaWidthPx = with(density) {
        (screenWidth * if (fullScreenBackdrop) 1f else MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx()
    }
    val heroMediaHeightPx = with(density) {
        (if (fullScreenBackdrop) screenHeight else heroBackdropHeight).roundToPx()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { canFocus = false }
            .clickable { onOpen() }) {
        val heroMediaModifier = if (fullScreenBackdrop) {
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(screenHeight)
        } else {
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = NuvioTheme.spacing.huge)
                .fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION)
                .height(heroBackdropHeight)
        }

        ModernHeroScene(
            state = heroSceneState,
            isFullScreen = { fullScreenBackdrop },
            bgColor = NuvioTheme.colors.Background,
            modifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx,
            onTrailerEnded = { trailerEnded = true },
            // Required: without the first-frame callback the media layer keeps
            // the trailer at alpha 0 (decoded but invisible).
            onFirstFrameRendered = { trailerFirstFrameRendered = true }
        )
        HeroTitleBlock(
            previewProvider = { heroPreview },
            metaPreviewProvider = { item },
            portraitMode = !useLandscapePosters,
            trailerPlaying = { shouldPlayTrailer && trailerFirstFrameRendered && !trailerEnded },
            modifier = Modifier
                .align(Alignment.BottomStart)
                // Tutto il blocco testo dell'hero parte sotto la banda nera
                // della barra: il logo non resta più mezzo coperto dalla striscia.
                .padding(
                    top = TOP_BAR_BAND_HEIGHT,
                    start = 52.dp,
                    end = NuvioTheme.spacing.xxxl,
                    bottom = rowsViewportHeight + NuvioTheme.spacing.lg
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )
    }
}

@Composable
private fun animePosterCardStyle(uiState: AnimeHomeUiState): PosterCardStyle {
    return remember(
        uiState.posterCardWidthDp,
        uiState.posterCardHeightDp,
        uiState.posterCardCornerRadiusDp
    ) {
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = uiState.posterCardHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp
        )
    }
}

@Composable
private fun AnimeClassicContent(
    uiState: AnimeHomeUiState,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onItemFocus: (MetaPreview) -> Unit
) {
    var focusedArtwork by remember { mutableStateOf<ClassicFocusArtwork?>(null) }
    var focusedCatalogItem by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(uiState.classicFocusGradientEnabled) {
        if (!uiState.classicFocusGradientEnabled) focusedArtwork = null
    }
    val handleMetaFocus: (MetaPreview) -> Unit = { item ->
        focusedCatalogItem = item
        onItemFocus(item)
        if (uiState.classicFocusGradientEnabled) {
            focusedArtwork = item.toAnimeClassicFocusArtwork(uiState.focusedPosterBackdropExpandEnabled)
        }
    }
    val latestOnRequestTrailerPreview by rememberUpdatedState(onRequestTrailerPreview)
    LaunchedEffect(focusedCatalogItem?.id, uiState.focusedPosterBackdropTrailerEnabled) {
        val item = focusedCatalogItem ?: return@LaunchedEffect
        if (!uiState.focusedPosterBackdropTrailerEnabled) return@LaunchedEffect
        if (trailerPreviewUrls.containsKey(item.id)) return@LaunchedEffect
        delay(150)
        if (focusedCatalogItem?.id != item.id) return@LaunchedEffect
        latestOnRequestTrailerPreview(item)
    }

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
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
        ) {
            if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
                item(key = "anime_hero") {
                    AnimeHeroItem(
                        uiState = uiState,
                        onOpen = { item, addonBaseUrl ->
                            onNavigateToDetail(item.id, item.rawType, addonBaseUrl)
                        },
                        onItemFocus = { focusedArtwork = null }
                    )
                }
            }

            if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
                item(key = "anime_continue_watching") {
                    AnimeContinueWatchingRow(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                        onRemoveItem = onRemoveContinueWatching,
                        onCwItemFocused = { itemIndex ->
                            focusedCatalogItem = null
                            if (uiState.classicFocusGradientEnabled) {
                                focusedArtwork = uiState.continueWatchingItems
                                    .getOrNull(itemIndex)
                                    ?.toAnimeClassicFocusArtwork(uiState.focusedPosterBackdropExpandEnabled)
                            }
                        }
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
                    posterCardStyle = animePosterCardStyle(uiState),
                    showPosterLabels = uiState.posterLabelsEnabled,
                    showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                    focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                    focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                    focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                    focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                    trailerPreviewUrls = trailerPreviewUrls,
                    trailerPreviewAudioUrls = trailerPreviewAudioUrls,
                    onRequestTrailerPreview = onRequestTrailerPreview,
                    onItemFocus = handleMetaFocus
                )
            }
        }
    }
}

@Composable
private fun AnimeGridContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
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
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
            item(key = "anime_hero") {
                AnimeHeroItem(
                    uiState = uiState,
                    onOpen = { item, addonBaseUrl ->
                        onNavigateToDetail(item.id, item.rawType, addonBaseUrl)
                    }
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            item(key = "anime_continue_watching") {
                AnimeGridContinueWatchingRow(
                    uiState = uiState,
                    onNavigateToDetail = onNavigateToDetail,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                    onRemoveItem = onRemoveContinueWatching
                )
            }
        }

        items(
            items = uiState.rows,
            key = { row -> row.legacyKey() }
        ) { row ->
            AnimeGridCatalogSection(
                catalogRow = row,
                columns = animeGridColumnCount(uiState.posterCardWidthDp),
                posterCardStyle = animePosterCardStyle(uiState),
                showLabels = uiState.posterLabelsEnabled,
                onItemClick = onNavigateToDetail,
                onSeeAll = {
                    onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                },
                showSeeAll = row.hasMore || row.items.size >= 15,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
            )
        }
    }
}

@Composable
private fun animeGridColumnCount(posterCardWidthDp: Int): Int {
    val configuration = LocalConfiguration.current
    val cardWidth = posterCardWidthDp.dp
    val horizontalPadding = NuvioTheme.spacing.xxxl * 2
    val spacing = NuvioTheme.spacing.md
    val available = (configuration.screenWidthDp - horizontalPadding.value).coerceAtLeast(0f)
    val cols = ((available + spacing.value) / (cardWidth.value + spacing.value)).toInt()
    return cols.coerceAtLeast(1)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeGridCatalogSection(
    catalogRow: CatalogRow,
    columns: Int,
    posterCardStyle: PosterCardStyle,
    showLabels: Boolean,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: () -> Unit,
    showSeeAll: Boolean,
    showCatalogTypeSuffix: Boolean,
    modifier: Modifier = Modifier
) {
    val catalogContext = LocalContext.current
    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, catalogContext) {
        val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        localizedContentType(catalogContext, raw)
    }
    val catalogTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (formattedName.isBlank()) ""
        else if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = catalogTitle.ifBlank { " " },
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Clip
                )
            }
        }

        val chunks = remember(catalogRow.items, columns) {
            catalogRow.items.chunked(columns.coerceAtLeast(1))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            chunks.forEachIndexed { chunkIndex, chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    chunk.forEach { item ->
                        ContentCard(
                            item = item,
                            posterCardStyle = posterCardStyle,
                            showLabels = showLabels,
                            onClick = {
                                onItemClick(item.id, item.apiType, catalogRow.addonBaseUrl)
                            }
                        )
                    }
                    if (showSeeAll && chunkIndex == chunks.lastIndex && chunk.size < columns) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (showSeeAll) {
                Box(modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)) {
                    androidx.tv.material3.Button(onClick = onSeeAll) {
                        Text(stringResource(R.string.action_see_all))
                    }
                }
            }
        }
    }
}

private fun buildAnimeHeroPreview(
    context: Context,
    item: MetaPreview,
    showFullReleaseDate: Boolean = true
): HeroPreview {
    val isSeries = isSeriesType(item.apiType)
    val contentTypeText = localizedContentType(context, item.apiType)
    return HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        contentTypeText = contentTypeText,
        isSeries = isSeries,
        yearText = extractYearText(item.type, item.releaseInfo, item.released, showFullReleaseDate),
        runtimeText = formatHeroRuntime(item.runtime),
        imdbText = item.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
        ageRatingText = item.ageRating,
        statusText = normalizeAnimeStatus(item.status),
        countryText = item.country,
        languageText = localizedLanguageText(item.language),
        genres = item.genres.take(3).asStable(),
        poster = item.poster,
        backdrop = item.backdropUrl,
        imageUrl = item.poster ?: item.backdropUrl,
        awards = item.awards
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeContinueWatchingRow(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onCwItemFocused: (itemIndex: Int) -> Unit = {}
) {
    if (uiState.continueWatchingItems.isEmpty() && uiState.upcomingItems.isEmpty()) return
    val (cardWidth, imageHeight) = animeCwCardSize(uiState)
    val onDetailsClick: (ContinueWatchingItem) -> Unit = { item ->
        handleAnimeCwClick(item, onNavigateToDetail)
    }
    val cornerRadius = uiState.posterCardCornerRadiusDp.dp
    Column(modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)) {
        ContinueWatchingSection(
            items = uiState.continueWatchingItems.asStable(),
            onItemClick = onContinueWatchingClick,
            onDetailsClick = onDetailsClick,
            onRemoveItem = onRemoveItem,
            onStartFromBeginning = onContinueWatchingStartFromBeginning,
            showManualPlayOption = false,
            title = stringResource(R.string.continue_watching),
            blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
            cardStyle = uiState.continueWatchingCardStyle,
            cardWidth = cardWidth,
            imageHeight = imageHeight,
            cornerRadius = cornerRadius,
            onItemFocused = onCwItemFocused
        )
        if (uiState.upcomingItems.isNotEmpty()) {
            ContinueWatchingSection(
                items = uiState.upcomingItems.asStable(),
                onItemClick = onContinueWatchingClick,
                onDetailsClick = onDetailsClick,
                onRemoveItem = onRemoveItem,
                onStartFromBeginning = onContinueWatchingStartFromBeginning,
                showManualPlayOption = false,
                title = stringResource(R.string.upcoming_section_title),
                blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                cardStyle = uiState.continueWatchingCardStyle,
                cardWidth = cardWidth,
                imageHeight = imageHeight,
                cornerRadius = cornerRadius
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeGridContinueWatchingRow(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit
) {
    if (uiState.continueWatchingItems.isEmpty() && uiState.upcomingItems.isEmpty()) return
    val onDetailsClick: (ContinueWatchingItem) -> Unit = { item ->
        handleAnimeCwClick(item, onNavigateToDetail)
    }
    Column(modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)) {
        GridContinueWatchingSection(
            items = uiState.continueWatchingItems.asStable(),
            onItemClick = onContinueWatchingClick,
            onDetailsClick = onDetailsClick,
            onRemoveItem = onRemoveItem,
            onStartFromBeginning = onContinueWatchingStartFromBeginning,
            showManualPlayOption = false,
            title = stringResource(R.string.continue_watching),
            blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
            cardStyle = uiState.continueWatchingCardStyle,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp
        )
        if (uiState.upcomingItems.isNotEmpty()) {
            GridContinueWatchingSection(
                items = uiState.upcomingItems.asStable(),
                onItemClick = onContinueWatchingClick,
                onDetailsClick = onDetailsClick,
                onRemoveItem = onRemoveItem,
                onStartFromBeginning = onContinueWatchingStartFromBeginning,
                showManualPlayOption = false,
                title = stringResource(R.string.upcoming_section_title),
                blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                cardStyle = uiState.continueWatchingCardStyle,
                cornerRadius = uiState.posterCardCornerRadiusDp.dp
            )
        }
    }
}

private fun animeCwCardSize(uiState: AnimeHomeUiState): Pair<Dp, Dp> {
    val baseWidth = uiState.posterCardWidthDp.dp
    val baseHeight = uiState.posterCardHeightDp.dp
    return when (uiState.continueWatchingCardStyle) {
        ContinueWatchingCardStyle.POSTER -> baseWidth to baseHeight
        ContinueWatchingCardStyle.WIDE -> baseWidth * 2.5f to baseWidth * 2.5f * 0.4f
        ContinueWatchingCardStyle.CARD -> baseWidth * (16f / 9f) to baseWidth
    }
}

private fun handleAnimeCwClick(
    item: ContinueWatchingItem,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    when (item) {
        is ContinueWatchingItem.InProgress ->
            onNavigateToDetail(
                item.progress.contentId,
                item.progress.contentType,
                item.progress.addonBaseUrl.orEmpty()
            )
        is ContinueWatchingItem.NextUp ->
            onNavigateToDetail(
                item.info.contentId,
                item.info.contentType,
                item.info.addonBaseUrl.orEmpty()
            )
    }
}

private fun MetaPreview.toAnimeClassicFocusArtwork(useBackdrop: Boolean): ClassicFocusArtwork =
    ClassicFocusArtwork(
        imageUrl = if (useBackdrop) {
            firstNonBlank(background, landscapePoster, poster)
        } else {
            firstNonBlank(poster, landscapePoster, background)
        },
        seed = "$id|$name|$apiType"
    )

private fun ContinueWatchingItem.toAnimeClassicFocusArtwork(useBackdrop: Boolean): ClassicFocusArtwork =
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



package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    var hasEnteredCatalogContent by rememberSaveable { mutableStateOf(false) }
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(hasCatalogContent) {
        if (hasCatalogContent) {
            hasEnteredCatalogContent = true
        }
    }

    val posterCardStyle = remember(
        uiState.posterCardWidthDp,
        uiState.posterCardCornerRadiusDp
    ) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        when {
            uiState.isLoading && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error == "No addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_addons),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            uiState.error == "No catalog addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_catalog_addons),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            else -> {
                val shouldShowLoadingGate = !hasEnteredCatalogContent && !hasCatalogContent
                LaunchedEffect(shouldShowLoadingGate) {
                    if (shouldShowLoadingGate) {
                        showHomeContentWithAnimation = false
                    } else {
                        // Flip on the next frame so AnimatedVisibility can run enter transition.
                        kotlinx.coroutines.yield()
                        showHomeContentWithAnimation = true
                    }
                }
                if (shouldShowLoadingGate) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = fadeIn(animationSpec = tween(320)) +
                            slideInVertically(
                                initialOffsetY = { it / 24 },
                                animationSpec = tween(320)
                            )
                    ) {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll
                            )

                            HomeLayout.GRID -> GridHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll
                            )

                            HomeLayout.MODERN -> ModernHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassicHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    ClassicHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        onRequestTrailerPreview = { item ->
            viewModel.requestTrailerPreview(item)
        },
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = { vi, vo, ri, ii, m ->
            viewModel.saveFocusState(vi, vo, ri, ii, m)
        }
    )
}

@Composable
private fun GridHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit
) {
    val gridFocusState by viewModel.gridFocusState.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveGridFocusState = { vi, vo ->
            viewModel.saveGridFocusState(vi, vo)
        }
    )
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, ri: Int, ii: Int, m: Map<String, Int> ->
            viewModel.saveFocusState(vi, vo, ri, ii, m)
        }
    }
    ModernHomeContent(
        uiState = uiState,
        focusState = focusState,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = saveModernFocusState
    )
}

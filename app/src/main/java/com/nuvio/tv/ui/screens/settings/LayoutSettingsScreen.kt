@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun LayoutSettingsScreen(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.layout_title),
        subtitle = stringResource(R.string.layout_subtitle)
    ) {
        LayoutSettingsContent(viewModel = viewModel)
    }
}

private enum class LayoutSettingsSection {
    HOME_LAYOUT,
    HOME_CONTENT,
    DETAIL_PAGE,
    CONTINUE_WATCHING,
    FOCUSED_POSTER,
    POSTER_CARD_STYLE
}

@Composable
fun LayoutSettingsContent(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    essentialMode: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var homeLayoutExpanded by rememberSaveable(essentialMode) { mutableStateOf(essentialMode) }
    var homeContentExpanded by rememberSaveable { mutableStateOf(false) }
    var detailPageExpanded by rememberSaveable { mutableStateOf(false) }
    var continueWatchingExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedPosterExpanded by rememberSaveable { mutableStateOf(false) }
    var posterCardStyleExpanded by rememberSaveable { mutableStateOf(false) }
    var showCwSortModeDialog by rememberSaveable { mutableStateOf(false) }

    val defaultHomeLayoutHeaderFocus = remember { FocusRequester() }
    val homeContentHeaderFocus = remember { FocusRequester() }
    val detailPageHeaderFocus = remember { FocusRequester() }
    val continueWatchingHeaderFocus = remember { FocusRequester() }
    val focusedPosterHeaderFocus = remember { FocusRequester() }
    val posterCardStyleHeaderFocus = remember { FocusRequester() }
    val homeLayoutHeaderFocus = initialFocusRequester ?: defaultHomeLayoutHeaderFocus

    var focusedSection by remember { mutableStateOf<LayoutSettingsSection?>(null) }
    var activePreviewLayout by remember(uiState.selectedLayout) { mutableStateOf(uiState.selectedLayout) }

    LaunchedEffect(homeLayoutExpanded, focusedSection) {
        if (!homeLayoutExpanded && focusedSection == LayoutSettingsSection.HOME_LAYOUT) {
            homeLayoutHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(homeContentExpanded, focusedSection) {
        if (!homeContentExpanded && focusedSection == LayoutSettingsSection.HOME_CONTENT) {
            homeContentHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(detailPageExpanded, focusedSection) {
        if (!detailPageExpanded && focusedSection == LayoutSettingsSection.DETAIL_PAGE) {
            detailPageHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(continueWatchingExpanded, focusedSection) {
        if (!continueWatchingExpanded && focusedSection == LayoutSettingsSection.CONTINUE_WATCHING) {
            continueWatchingHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(focusedPosterExpanded, focusedSection) {
        if (!focusedPosterExpanded && focusedSection == LayoutSettingsSection.FOCUSED_POSTER) {
            focusedPosterHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(posterCardStyleExpanded, focusedSection) {
        if (!posterCardStyleExpanded && focusedSection == LayoutSettingsSection.POSTER_CARD_STYLE) {
            posterCardStyleHeaderFocus.requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.layout_title),
            subtitle = stringResource(
                if (essentialMode) R.string.layout_selection_subtitle else R.string.layout_subtitle
            )
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
        val layoutListState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = layoutListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "home_layout_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_home),
                    description = stringResource(R.string.layout_section_home_desc),
                    expanded = homeLayoutExpanded,
                    onToggle = { homeLayoutExpanded = !homeLayoutExpanded },
                    focusRequester = homeLayoutHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LayoutCard(
                            layout = HomeLayout.MODERN,
                            isSelected = uiState.selectedLayout == HomeLayout.MODERN,
                            showLivePreview = activePreviewLayout == HomeLayout.MODERN || uiState.selectedLayout == HomeLayout.MODERN,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.MODERN))
                            },
                            onFocused = {
                                focusedSection = LayoutSettingsSection.HOME_LAYOUT
                                activePreviewLayout = HomeLayout.MODERN
                            },
                            modifier = Modifier.weight(1f)
                        )
                        LayoutCard(
                            layout = HomeLayout.GRID,
                            isSelected = uiState.selectedLayout == HomeLayout.GRID,
                            showLivePreview = activePreviewLayout == HomeLayout.GRID || uiState.selectedLayout == HomeLayout.GRID,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.GRID))
                            },
                            onFocused = {
                                focusedSection = LayoutSettingsSection.HOME_LAYOUT
                                activePreviewLayout = HomeLayout.GRID
                            },
                            modifier = Modifier.weight(1f)
                        )
                        LayoutCard(
                            layout = HomeLayout.CLASSIC,
                            isSelected = uiState.selectedLayout == HomeLayout.CLASSIC,
                            showLivePreview = activePreviewLayout == HomeLayout.CLASSIC || uiState.selectedLayout == HomeLayout.CLASSIC,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.CLASSIC))
                            },
                            onFocused = {
                                focusedSection = LayoutSettingsSection.HOME_LAYOUT
                                activePreviewLayout = HomeLayout.CLASSIC
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uiState.selectedLayout == HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_landscape_posters),
                            subtitle = stringResource(R.string.layout_landscape_posters_sub),
                            checked = uiState.modernLandscapePostersEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernLandscapePostersEnabled(
                                        !uiState.modernLandscapePostersEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                        )
                    }

                    if (uiState.selectedLayout == HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_fullscreen_hero_backdrop),
                            subtitle = stringResource(R.string.layout_fullscreen_hero_backdrop_sub),
                            checked = uiState.modernHeroFullScreenBackdropEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernHeroFullScreenBackdropEnabled(!uiState.modernHeroFullScreenBackdropEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                        )
                    }

                    if (uiState.selectedLayout == HomeLayout.CLASSIC) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_classic_focus_gradient),
                            subtitle = stringResource(R.string.layout_classic_focus_gradient_sub),
                            checked = uiState.classicFocusGradientEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetClassicFocusGradientEnabled(!uiState.classicFocusGradientEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                        )
                    }

                    if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty() && uiState.selectedLayout != HomeLayout.MODERN) {
                        Text(
                            text = stringResource(R.string.layout_hero_catalogs),
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioColors.TextSecondary
                        )
                        Text(
                            text = stringResource(R.string.layout_hero_catalogs_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary
                        )
                        LazyRow(
                            contentPadding = PaddingValues(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = uiState.availableCatalogs,
                                key = { it.key }
                            ) { catalog ->
                                CatalogChip(
                                    catalogInfo = catalog,
                                    isSelected = catalog.key in uiState.heroCatalogKeys,
                                    onClick = {
                                        viewModel.onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key))
                                    },
                                    onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                                )
                            }
                        }
                    }
                }
            }

            if (!essentialMode) {
            item(key = "home_content_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_content),
                    description = stringResource(R.string.layout_section_content_desc),
                    expanded = homeContentExpanded,
                    onToggle = { homeContentExpanded = !homeContentExpanded },
                    focusRequester = homeContentHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                ) {
                    if (!uiState.modernSidebarEnabled) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_collapse_sidebar),
                            subtitle = stringResource(R.string.layout_collapse_sidebar_sub),
                            checked = uiState.sidebarCollapsedByDefault,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    CompactToggleRow(
                        title = stringResource(R.string.layout_modern_sidebar),
                        subtitle = stringResource(R.string.layout_modern_sidebar_sub),
                        checked = uiState.modernSidebarEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetModernSidebarEnabled(!uiState.modernSidebarEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    if (uiState.modernSidebarEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_modern_sidebar_blur),
                            subtitle = stringResource(R.string.layout_modern_sidebar_blur_sub),
                            checked = uiState.modernSidebarBlurEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernSidebarBlurEnabled(!uiState.modernSidebarBlurEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    DiscoverLocationRow(
                        selectedLocation = uiState.discoverLocation,
                        rememberedLocation = uiState.lastNonOffDiscoverLocation,
                        onLocationSelected = { location ->
                            viewModel.onEvent(LayoutSettingsEvent.SetDiscoverLocation(location))
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    if (uiState.selectedLayout != HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_show_hero),
                            subtitle = stringResource(R.string.layout_show_hero_sub),
                            checked = uiState.heroSectionEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    if (uiState.selectedLayout != HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_poster_labels),
                            subtitle = stringResource(R.string.layout_poster_labels_sub),
                            checked = uiState.posterLabelsEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetPosterLabelsEnabled(!uiState.posterLabelsEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    if (uiState.selectedLayout != HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_addon_name),
                            subtitle = stringResource(R.string.layout_addon_name_sub),
                            checked = uiState.catalogAddonNameEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    CompactToggleRow(
                        title = stringResource(R.string.layout_catalog_type),
                        subtitle = stringResource(R.string.layout_catalog_type_sub),
                        checked = uiState.catalogTypeSuffixEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    CompactToggleRow(
                        title = stringResource(R.string.layout_hide_unreleased),
                        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
                        checked = uiState.hideUnreleasedContent,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                }
            }

            item(key = "detail_page_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_detail),
                    description = stringResource(R.string.layout_section_detail_desc),
                    expanded = detailPageExpanded,
                    onToggle = { detailPageExpanded = !detailPageExpanded },
                    focusRequester = detailPageHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                ) {
                    CompactToggleRow(
                        title = stringResource(R.string.layout_blur_unwatched),
                        subtitle = stringResource(R.string.layout_blur_unwatched_sub),
                        checked = uiState.blurUnwatchedEpisodes,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetBlurUnwatchedEpisodes(!uiState.blurUnwatchedEpisodes)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_trailer_button),
                        subtitle = stringResource(R.string.layout_trailer_button_sub),
                        checked = uiState.detailPageTrailerButtonEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(
                                    !uiState.detailPageTrailerButtonEnabled
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_prefer_external_meta),
                        subtitle = stringResource(R.string.layout_prefer_external_meta_sub),
                        checked = uiState.preferExternalMetaAddonDetail,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(
                                    !uiState.preferExternalMetaAddonDetail
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_show_full_release_date),
                        subtitle = stringResource(R.string.layout_show_full_release_date_sub),
                        checked = uiState.showFullReleaseDate,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetShowFullReleaseDate(!uiState.showFullReleaseDate)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )
                }
            }

            item(key = "continue_watching_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_continue_watching),
                    description = stringResource(R.string.layout_section_continue_watching_desc),
                    expanded = continueWatchingExpanded,
                    onToggle = { continueWatchingExpanded = !continueWatchingExpanded },
                    focusRequester = continueWatchingHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.CONTINUE_WATCHING }
                ) {
                    CompactToggleRow(
                        title = stringResource(R.string.layout_use_episode_thumbnails_cw),
                        subtitle = stringResource(R.string.layout_use_episode_thumbnails_cw_sub),
                        checked = uiState.useEpisodeThumbnailsInCw,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw(!uiState.useEpisodeThumbnailsInCw)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.CONTINUE_WATCHING }
                    )

                    if (uiState.useEpisodeThumbnailsInCw) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_blur_cw_next_up),
                            subtitle = stringResource(R.string.layout_blur_cw_next_up_sub),
                            checked = uiState.blurContinueWatchingNextUp,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetBlurContinueWatchingNextUp(!uiState.blurContinueWatchingNextUp)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.CONTINUE_WATCHING }
                        )
                    }

                    CompactToggleRow(
                        title = stringResource(R.string.layout_next_up_furthest_episode),
                        subtitle = stringResource(R.string.layout_next_up_furthest_episode_sub),
                        checked = uiState.nextUpFromFurthestEpisode,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetNextUpFromFurthestEpisode(!uiState.nextUpFromFurthestEpisode)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.CONTINUE_WATCHING }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_show_unaired_next_up),
                        subtitle = stringResource(R.string.layout_show_unaired_next_up_sub),
                        checked = uiState.showUnairedNextUp,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetShowUnairedNextUp(!uiState.showUnairedNextUp)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.CONTINUE_WATCHING }
                    )

                    SettingsActionRow(
                        title = stringResource(R.string.layout_cw_sort_mode),
                        subtitle = stringResource(R.string.layout_cw_sort_mode_sub),
                        value = when (uiState.continueWatchingSortMode) {
                            ContinueWatchingSortMode.DEFAULT -> stringResource(R.string.layout_cw_sort_default)
                            ContinueWatchingSortMode.STREAMING_STYLE -> stringResource(R.string.layout_cw_sort_streaming)
                        },
                        onClick = { showCwSortModeDialog = true },
                        onFocused = { focusedSection = LayoutSettingsSection.CONTINUE_WATCHING }
                    )
                }
            }

            if (uiState.selectedLayout != HomeLayout.GRID) {
            item(key = "focused_poster_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_focused),
                    description = stringResource(R.string.layout_section_focused_desc),
                    expanded = focusedPosterExpanded,
                    onToggle = { focusedPosterExpanded = !focusedPosterExpanded },
                    focusRequester = focusedPosterHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                ) {
                    val isModern = uiState.selectedLayout == HomeLayout.MODERN
                    val isModernLandscape = isModern && uiState.modernLandscapePostersEnabled
                    val showAutoplayRow = AppFeaturePolicy.inAppTrailerPlaybackEnabled &&
                        (uiState.focusedPosterBackdropExpandEnabled || isModernLandscape)

                    if (!isModernLandscape) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_expand_poster),
                            subtitle = stringResource(R.string.layout_expand_poster_sub),
                            checked = uiState.focusedPosterBackdropExpandEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled(
                                        !uiState.focusedPosterBackdropExpandEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (!isModernLandscape && uiState.focusedPosterBackdropExpandEnabled) {
                        SliderSettingsItem(
                            icon = Icons.Default.Timer,
                            title = stringResource(R.string.layout_expand_delay),
                            subtitle = stringResource(R.string.layout_expand_delay_sub),
                            value = uiState.focusedPosterBackdropExpandDelaySeconds,
                            valueText = "${uiState.focusedPosterBackdropExpandDelaySeconds}s",
                            minValue = 0,
                            maxValue = 10,
                            step = 1,
                            onValueChange = { seconds ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds(seconds)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (showAutoplayRow) {
                        CompactToggleRow(
                            title = if (isModern) {
                                stringResource(R.string.layout_autoplay_trailer)
                            } else {
                                stringResource(R.string.layout_autoplay_trailer_expanded)
                            },
                            subtitle = if (isModern) {
                                stringResource(R.string.layout_autoplay_trailer_sub)
                            } else {
                                stringResource(R.string.layout_autoplay_trailer_expanded_sub)
                            },
                            checked = uiState.focusedPosterBackdropTrailerEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled(
                                        !uiState.focusedPosterBackdropTrailerEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (showAutoplayRow && uiState.focusedPosterBackdropTrailerEnabled) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_trailer_muted),
                            subtitle = if (isModern) {
                                stringResource(R.string.layout_trailer_muted_sub_preview)
                            } else {
                                stringResource(R.string.layout_trailer_muted_sub_expanded)
                            },
                            checked = uiState.focusedPosterBackdropTrailerMuted,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted(
                                        !uiState.focusedPosterBackdropTrailerMuted
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (
                        isModern &&
                        showAutoplayRow &&
                        uiState.focusedPosterBackdropTrailerEnabled
                    ) {
                        ModernTrailerPlaybackTargetRow(
                            selectedTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget,
                            onTargetSelected = { target ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerPlaybackTarget(target)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }
                }
            }
            }

            item(key = "poster_style_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_card_style),
                    description = stringResource(R.string.layout_section_card_style_desc),
                    expanded = posterCardStyleExpanded,
                    onToggle = { posterCardStyleExpanded = !posterCardStyleExpanded },
                    focusRequester = posterCardStyleHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.POSTER_CARD_STYLE }
                ) {
                    PosterCardStyleControls(
                        widthDp = uiState.posterCardWidthDp,
                        cornerRadiusDp = uiState.posterCardCornerRadiusDp,
                        onWidthSelected = { width ->
                            viewModel.onEvent(LayoutSettingsEvent.SetPosterCardWidth(width))
                        },
                        onCornerRadiusSelected = { radius ->
                            viewModel.onEvent(LayoutSettingsEvent.SetPosterCardCornerRadius(radius))
                        },
                        onReset = {
                            viewModel.onEvent(LayoutSettingsEvent.ResetPosterCardStyle)
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.POSTER_CARD_STYLE }
                    )
                }
            }
            }
        }
        SettingsVerticalScrollIndicators(state = layoutListState)
        }
        }

        if (showCwSortModeDialog) {
            ContinueWatchingSortModeDialog(
                currentMode = uiState.continueWatchingSortMode,
                onModeSelected = { mode ->
                    viewModel.onEvent(LayoutSettingsEvent.SetContinueWatchingSortMode(mode))
                    showCwSortModeDialog = false
                },
                onDismiss = { showCwSortModeDialog = false }
            )
        }
    }
}

@Composable
private fun ContinueWatchingSortModeDialog(
    currentMode: ContinueWatchingSortMode,
    onModeSelected: (ContinueWatchingSortMode) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val options = listOf(
        Triple(
            ContinueWatchingSortMode.DEFAULT,
            stringResource(R.string.layout_cw_sort_default),
            stringResource(R.string.layout_cw_sort_default_desc)
        ),
        Triple(
            ContinueWatchingSortMode.STREAMING_STYLE,
            stringResource(R.string.layout_cw_sort_streaming),
            stringResource(R.string.layout_cw_sort_streaming_desc)
        )
    )

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.layout_cw_sort_mode),
        width = 420.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].first.name }
                ) { index ->
                    val (mode, title, description) = options[index]
                    val isSelected = mode == currentMode

                    Card(
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                            focusedContainerColor = NuvioColors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    color = NuvioColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NuvioColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsActionRow(
            title = title,
            subtitle = description,
            value = if (expanded) stringResource(R.string.layout_open) else stringResource(R.string.layout_closed),
            onClick = onToggle,
            trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            modifier = Modifier.focusRequester(focusRequester),
            onFocused = onFocused
        )

        if (expanded) {
            SettingsGroupCard {
                content()
            }
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsToggleRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onToggle = onToggle,
        onFocused = onFocused
    )
}

@Composable
private fun ModernTrailerPlaybackTargetRow(
    selectedTarget: FocusedPosterTrailerPlaybackTarget,
    onTargetSelected: (FocusedPosterTrailerPlaybackTarget) -> Unit,
    onFocused: () -> Unit
) {
    Text(
        text = stringResource(R.string.layout_trailer_location),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioColors.TextSecondary
    )
    Text(
        text = stringResource(R.string.layout_trailer_location_sub),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextTertiary
    )
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "trailer_target_expanded_card") {
            SettingsChoiceChip(
                label = stringResource(R.string.layout_trailer_expanded_card),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)
                },
                onFocused = onFocused
            )
        }
        item(key = "trailer_target_hero_media") {
            SettingsChoiceChip(
                label = stringResource(R.string.layout_trailer_hero_media),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
                },
                onFocused = onFocused
            )
        }
    }
}

@Composable
private fun DiscoverLocationRow(
    selectedLocation: DiscoverLocation,
    rememberedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit,
    onFocused: () -> Unit
) {
    val sectionEnabled = selectedLocation != DiscoverLocation.OFF
    var dialogOpen by remember { mutableStateOf(false) }

    CompactToggleRow(
        title = stringResource(R.string.layout_show_discover),
        subtitle = stringResource(R.string.layout_show_discover_sub),
        checked = sectionEnabled,
        onToggle = {
            onLocationSelected(
                when (selectedLocation) {
                    DiscoverLocation.OFF -> rememberedLocation
                    DiscoverLocation.IN_SEARCH,
                    DiscoverLocation.IN_SIDEBAR -> DiscoverLocation.OFF
                }
            )
        },
        onFocused = onFocused
    )
    if (sectionEnabled) {
        val currentLabel = when (selectedLocation) {
            DiscoverLocation.IN_SIDEBAR -> stringResource(R.string.layout_discover_location_in_sidebar)
            DiscoverLocation.IN_SEARCH -> stringResource(R.string.layout_discover_location_in_search)
            DiscoverLocation.OFF -> ""
        }
        SettingsActionRow(
            title = stringResource(R.string.layout_discover_location_action),
            subtitle = currentLabel,
            onClick = { dialogOpen = true },
            onFocused = onFocused
        )
    }

    if (dialogOpen) {
        DiscoverLocationDialog(
            selectedLocation = selectedLocation,
            onLocationSelected = { location ->
                onLocationSelected(location)
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverLocationDialog(
    selectedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(
            DiscoverLocation.IN_SEARCH,
            stringResource(R.string.layout_discover_location_in_search),
            stringResource(R.string.layout_discover_location_in_search_desc)
        ),
        Triple(
            DiscoverLocation.IN_SIDEBAR,
            stringResource(R.string.layout_discover_location_in_sidebar),
            stringResource(R.string.layout_discover_location_in_sidebar_desc)
        )
    )

    val effectiveSelected = if (selectedLocation == DiscoverLocation.OFF) {
        DiscoverLocation.IN_SEARCH
    } else {
        selectedLocation
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.layout_discover_location_dialog_title),
        width = 460.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].first.name }
                ) { index ->
                    val (location, title, description) = options[index]
                    val isSelected = location == effectiveSelected

                    Card(
                        onClick = { onLocationSelected(location) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                            focusedContainerColor = NuvioColors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    color = NuvioColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NuvioColors.Primary,
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutCard(
    layout: HomeLayout,
    isSelected: Boolean,
    showLivePreview: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                if (showLivePreview) {
                    when (layout) {
                        HomeLayout.CLASSIC -> ClassicLayoutPreview(modifier = Modifier.fillMaxWidth())
                        HomeLayout.GRID -> GridLayoutPreview(modifier = Modifier.fillMaxWidth())
                        HomeLayout.MODERN -> ModernLayoutPreview(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    LayoutPreviewPlaceholder()
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = NuvioColors.FocusRing,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 6.dp)
                    )
                }
                Text(
                    text = when (layout) {
                        HomeLayout.CLASSIC -> stringResource(R.string.layout_classic)
                        HomeLayout.GRID -> stringResource(R.string.layout_grid)
                        HomeLayout.MODERN -> stringResource(R.string.layout_modern)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun LayoutPreviewPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(
                color = NuvioColors.BackgroundCard,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(10.dp)
                .background(NuvioColors.Border, RoundedCornerShape(999.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(NuvioColors.BackgroundElevated, RoundedCornerShape(10.dp))
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(NuvioColors.Border, RoundedCornerShape(999.dp))
                )
            }
        }
    }
}

@Composable
private fun CatalogChip(
    catalogInfo: CatalogInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsChoiceChip(
        label = catalogInfo.name,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused
    )
}

@Composable
private fun PosterCardStyleControls(
    widthDp: Int,
    cornerRadiusDp: Int,
    onWidthSelected: (Int) -> Unit,
    onCornerRadiusSelected: (Int) -> Unit,
    onReset: () -> Unit,
    onFocused: () -> Unit
) {
    val widthOptions = listOf(
        PresetOption(stringResource(R.string.layout_preset_compact), 104),
        PresetOption(stringResource(R.string.layout_preset_dense), 112),
        PresetOption(stringResource(R.string.layout_preset_standard), 120),
        PresetOption(stringResource(R.string.layout_preset_balanced), 126),
        PresetOption(stringResource(R.string.layout_preset_comfort), 134),
        PresetOption(stringResource(R.string.layout_preset_large), 140)
    )
    val radiusOptions = listOf(
        PresetOption(stringResource(R.string.layout_preset_sharp), 0),
        PresetOption(stringResource(R.string.layout_preset_subtle), 4),
        PresetOption(stringResource(R.string.layout_preset_classic), 8),
        PresetOption(stringResource(R.string.layout_preset_rounded), 12),
        PresetOption(stringResource(R.string.layout_preset_pill), 16)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OptionRow(
            title = stringResource(R.string.layout_card_width),
            selectedValue = widthDp,
            options = widthOptions,
            onSelected = onWidthSelected,
            onFocused = onFocused
        )
        OptionRow(
            title = stringResource(R.string.layout_card_radius),
            selectedValue = cornerRadiusDp,
            options = radiusOptions,
            onSelected = onCornerRadiusSelected,
            onFocused = onFocused
        )

        Button(
            onClick = onReset,
            modifier = Modifier.onFocusChanged {
                if (it.isFocused) onFocused()
            },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(SettingsPillRadius)),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.Background,
                focusedContainerColor = NuvioColors.Background
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(SettingsPillRadius)
                )
            )
        ) {
            Text(
                text = stringResource(R.string.layout_reset_default),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextPrimary
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    selectedValue: Int,
    options: List<PresetOption>,
    onSelected: (Int) -> Unit,
    onFocused: () -> Unit
) {
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: stringResource(R.string.layout_custom)

    Text(
        text = "$title ($selectedLabel)",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioColors.TextSecondary
    )

    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = options,
            key = { it.value }
        ) { option ->
            ValueChip(
                label = option.label,
                isSelected = option.value == selectedValue,
                onClick = { onSelected(option.value) },
                onFocused = onFocused
            )
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsChoiceChip(
        label = label,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused
    )
}

private data class PresetOption(
    val label: String,
    val value: Int
)

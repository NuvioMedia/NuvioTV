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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview
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
    FOCUSED_POSTER,
    POSTER_CARD_STYLE
}

@Composable
fun LayoutSettingsContent(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var homeLayoutExpanded by rememberSaveable { mutableStateOf(false) }
    var homeContentExpanded by rememberSaveable { mutableStateOf(false) }
    var detailPageExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedPosterExpanded by rememberSaveable { mutableStateOf(false) }
    var posterCardStyleExpanded by rememberSaveable { mutableStateOf(false) }

    val defaultHomeLayoutHeaderFocus = remember { FocusRequester() }
    val homeContentHeaderFocus = remember { FocusRequester() }
    val detailPageHeaderFocus = remember { FocusRequester() }
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
            subtitle = stringResource(R.string.layout_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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

                    if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
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
                    CompactToggleRow(
                        title = stringResource(R.string.layout_show_discover),
                        subtitle = stringResource(R.string.layout_show_discover_sub),
                        checked = uiState.searchDiscoverEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetSearchDiscoverEnabled(!uiState.searchDiscoverEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
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
                }
            }

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
                    val showAutoplayRow = uiState.focusedPosterBackdropExpandEnabled || isModernLandscape

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
                            minValue = 1,
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

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.collectAsState
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
        title = "Layout Settings",
        subtitle = "Adjust home layout, content visibility, and poster behavior"
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
    val uiState by viewModel.uiState.collectAsState()

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
            title = "Layout Settings",
            subtitle = "Adjust home layout, content visibility, and poster behavior"
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
            item {
                CollapsibleSectionCard(
                    title = "Home Layout",
                    description = "Choose structure and hero source.",
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
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.MODERN))
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT },
                            modifier = Modifier.weight(1f)
                        )
                        LayoutCard(
                            layout = HomeLayout.GRID,
                            isSelected = uiState.selectedLayout == HomeLayout.GRID,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.GRID))
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT },
                            modifier = Modifier.weight(1f)
                        )
                        LayoutCard(
                            layout = HomeLayout.CLASSIC,
                            isSelected = uiState.selectedLayout == HomeLayout.CLASSIC,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.CLASSIC))
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uiState.selectedLayout == HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = "Landscape Posters",
                            subtitle = "Switch between portrait and landscape cards for Modern view.",
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
                        CompactToggleRow(
                            title = "Show Preview Row",
                            subtitle = "Show a partial preview of the row below in Modern Home layout.",
                            checked = uiState.modernNextRowPreviewEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernNextRowPreviewEnabled(
                                        !uiState.modernNextRowPreviewEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                        )
                    }

                    if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
                        Text(
                            text = "Hero Catalogs",
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioColors.TextSecondary
                        )
                        Text(
                            text = "Select one or more catalogs for hero content.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary
                        )
                        LazyRow(
                            contentPadding = PaddingValues(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.availableCatalogs) { catalog ->
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

            item {
                CollapsibleSectionCard(
                    title = "Home Content",
                    description = "Control what appears on home and search.",
                    expanded = homeContentExpanded,
                    onToggle = { homeContentExpanded = !homeContentExpanded },
                    focusRequester = homeContentHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                ) {
                    if (!uiState.modernSidebarEnabled) {
                        CompactToggleRow(
                            title = "Collapse Sidebar",
                            subtitle = "Hide sidebar by default; show when focused.",
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
                        title = "Modern Sidebar ON/OFF",
                        subtitle = "Enable floating sidebar navigation.",
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
                            title = "Modern Sidebar Blur",
                            subtitle = "Toggle blur effect for modern sidebar surfaces.",
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
                        title = "Show Hero Section",
                        subtitle = "Display hero carousel at top of home.",
                        checked = uiState.heroSectionEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    CompactToggleRow(
                        title = "Show Discover in Search",
                        subtitle = "Show browse section when search is empty.",
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
                            title = "Show Poster Labels",
                            subtitle = "Show titles under posters in rows, grid, and see-all.",
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
                            title = "Show Addon Name",
                            subtitle = "Show source name under catalog titles.",
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
                        title = "Show Catalog Type",
                        subtitle = "Show type suffix next to catalog name (Movie/Series).",
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

            item {
                CollapsibleSectionCard(
                    title = "Detail Page",
                    description = "Settings for the detail and episode screens.",
                    expanded = detailPageExpanded,
                    onToggle = { detailPageExpanded = !detailPageExpanded },
                    focusRequester = detailPageHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                ) {
                    CompactToggleRow(
                        title = "Blur Unwatched Episodes",
                        subtitle = "Blur episode thumbnails until watched to avoid spoilers.",
                        checked = uiState.blurUnwatchedEpisodes,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetBlurUnwatchedEpisodes(!uiState.blurUnwatchedEpisodes)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = "Show Trailer Button",
                        subtitle = "Show trailer button on detail page (only when trailer is available).",
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
                        title = "Prefer meta from external addon",
                        subtitle = "Use metadata from external addon instead of catalog addon.",
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

            if (uiState.selectedLayout != HomeLayout.MODERN) {
                item {
                    CollapsibleSectionCard(
                        title = "Focused Poster",
                        description = "Advanced behavior for focused poster cards.",
                        expanded = focusedPosterExpanded,
                        onToggle = { focusedPosterExpanded = !focusedPosterExpanded },
                        focusRequester = focusedPosterHeaderFocus,
                        onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                    ) {
                        CompactToggleRow(
                            title = "Expand Focused Poster to Backdrop",
                            subtitle = "Expand focused poster after idle delay.",
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

                        if (uiState.focusedPosterBackdropExpandEnabled) {
                            SliderSettingsItem(
                                icon = Icons.Default.Timer,
                                title = "Backdrop Expand Delay",
                                subtitle = "How long to wait before expanding focused cards.",
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

                            CompactToggleRow(
                                title = "Autoplay Trailer in Expanded Card",
                                subtitle = "Play trailer inside expanded backdrop when available.",
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

                        if (uiState.focusedPosterBackdropExpandEnabled && uiState.focusedPosterBackdropTrailerEnabled) {
                            CompactToggleRow(
                                title = "Play Trailer Muted",
                                subtitle = "Mute trailer audio in expanded cards.",
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
                    }
                }
            }

            item {
                CollapsibleSectionCard(
                    title = "Poster Card Style",
                    description = "Tune card width and corner radius.",
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
            value = if (expanded) "Open" else "Closed",
            onClick = onToggle,
            trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            modifier = Modifier.focusRequester(focusRequester),
            onFocused = onFocused
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
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
private fun LayoutCard(
    layout: HomeLayout,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
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
                when (layout) {
                    HomeLayout.CLASSIC -> ClassicLayoutPreview(modifier = Modifier.fillMaxWidth())
                    HomeLayout.GRID -> GridLayoutPreview(modifier = Modifier.fillMaxWidth())
                    HomeLayout.MODERN -> ModernLayoutPreview(modifier = Modifier.fillMaxWidth())
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
                        contentDescription = "Selected",
                        tint = NuvioColors.FocusRing,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 6.dp)
                    )
                }
                Text(
                    text = layout.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
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
        PresetOption("Compact", 104),
        PresetOption("Dense", 112),
        PresetOption("Standard", 120),
        PresetOption("Balanced", 126),
        PresetOption("Comfort", 134),
        PresetOption("Large", 140)
    )
    val radiusOptions = listOf(
        PresetOption("Sharp", 0),
        PresetOption("Subtle", 4),
        PresetOption("Classic", 8),
        PresetOption("Rounded", 12),
        PresetOption("Pill", 16)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OptionRow(
            title = "Width",
            selectedValue = widthDp,
            options = widthOptions,
            onSelected = onWidthSelected,
            onFocused = onFocused
        )
        OptionRow(
            title = "Corner Radius",
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
                text = "Reset to Default",
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
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: "Custom"

    Text(
        text = "$title ($selectedLabel)",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioColors.TextSecondary
    )

    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { option ->
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

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview

@Composable
internal fun LayoutHomeLayoutSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val firstHomeLayoutFocusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsOptionRow(firstHomeLayoutFocusRequester),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        listOf(HomeLayout.MODERN, HomeLayout.GRID, HomeLayout.CLASSIC).forEachIndexed { index, layout ->
            LayoutCard(
                layout = layout,
                isSelected = uiState.selectedLayout == layout,
                onClick = { onEvent(LayoutSettingsEvent.SelectLayout(layout)) },
                modifier = Modifier
                    .weight(1f)
                    .then(if (index == 0) Modifier.focusRequester(firstHomeLayoutFocusRequester) else Modifier)
            )
        }
    }

    if (uiState.selectedLayout == HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_landscape_posters),
            subtitle = stringResource(R.string.layout_landscape_posters_sub),
            checked = uiState.modernLandscapePostersEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetModernLandscapePostersEnabled(!uiState.modernLandscapePostersEnabled))
            }
        )
        SettingsToggleRow(
            title = stringResource(R.string.layout_fullscreen_hero_backdrop),
            subtitle = stringResource(R.string.layout_fullscreen_hero_backdrop_sub),
            checked = uiState.modernHeroFullScreenBackdropEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetModernHeroFullScreenBackdropEnabled(!uiState.modernHeroFullScreenBackdropEnabled))
            }
        )
    }

    if (uiState.selectedLayout == HomeLayout.CLASSIC) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_classic_focus_gradient),
            subtitle = stringResource(R.string.layout_classic_focus_gradient_sub),
            checked = uiState.classicFocusGradientEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetClassicFocusGradientEnabled(!uiState.classicFocusGradientEnabled))
            }
        )
    }

    if (uiState.selectedLayout != HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_show_hero),
            subtitle = stringResource(R.string.layout_show_hero_sub),
            checked = uiState.heroSectionEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)) }
        )
        if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
            Text(
                text = stringResource(R.string.layout_hero_catalogs),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary
            )
            Text(
                text = stringResource(R.string.layout_hero_catalogs_sub),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
            val firstHeroCatalogFocusRequester = remember { FocusRequester() }
            LazyRow(
                modifier = Modifier.settingsOptionRow(firstHeroCatalogFocusRequester),
                contentPadding = PaddingValues(end = NuvioTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                itemsIndexed(
                    items = uiState.availableCatalogs,
                    key = { _, catalog -> catalog.key }
                ) { catalogIndex, catalog ->
                    CatalogChip(
                        catalogInfo = catalog,
                        isSelected = catalog.key in uiState.heroCatalogKeys,
                        onClick = { onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key)) },
                        modifier = if (catalogIndex == 0) {
                            Modifier.focusRequester(firstHeroCatalogFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun LayoutHomeContentSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    if (uiState.selectedLayout != HomeLayout.MODERN) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_poster_labels),
            subtitle = stringResource(R.string.layout_poster_labels_sub),
            checked = uiState.posterLabelsEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetPosterLabelsEnabled(!uiState.posterLabelsEnabled)) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.layout_addon_name),
            subtitle = stringResource(R.string.layout_addon_name_sub),
            checked = uiState.catalogAddonNameEnabled,
            onToggle = { onEvent(LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)) }
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.layout_catalog_type),
        subtitle = stringResource(R.string.layout_catalog_type_sub),
        checked = uiState.catalogTypeSuffixEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_hide_unreleased),
        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
        checked = uiState.hideUnreleasedContent,
        onToggle = { onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_overall_ratings),
        subtitle = stringResource(
            if (uiState.homeImdbRatingsVisibility.showRatings) {
                R.string.layout_overall_ratings_sub_on
            } else {
                R.string.layout_overall_ratings_sub_off
            }
        ),
        checked = uiState.homeImdbRatingsVisibility.showRatings,
        onToggle = {
            val visibility = if (uiState.homeImdbRatingsVisibility.showRatings) {
                HomeImdbRatingsVisibility.HIDE_ALL
            } else {
                HomeImdbRatingsVisibility.SHOW_ALL
            }
            onEvent(LayoutSettingsEvent.SetHomeImdbRatingsVisibility(visibility))
        }
    )
}

@Composable
internal fun LayoutSidebarSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_modern_sidebar),
        subtitle = stringResource(R.string.layout_modern_sidebar_sub),
        checked = uiState.modernSidebarEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetModernSidebarEnabled(!uiState.modernSidebarEnabled)) }
    )
    if (uiState.modernSidebarEnabled) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_modern_sidebar_blur),
                subtitle = stringResource(R.string.layout_modern_sidebar_blur_sub),
                checked = uiState.modernSidebarBlurEnabled,
                onToggle = { onEvent(LayoutSettingsEvent.SetModernSidebarBlurEnabled(!uiState.modernSidebarBlurEnabled)) }
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.layout_hide_floating_pill),
            subtitle = stringResource(R.string.layout_hide_floating_pill_sub),
            checked = uiState.sidebarCollapsedByDefault,
            onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
        )
    } else {
        SettingsToggleRow(
            title = stringResource(R.string.layout_collapse_sidebar),
            subtitle = stringResource(R.string.layout_collapse_sidebar_sub),
            checked = uiState.sidebarCollapsedByDefault,
            onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
        )
    }
    DiscoverLocationRow(
        selectedLocation = uiState.discoverLocation,
        rememberedLocation = uiState.lastNonOffDiscoverLocation,
        onLocationSelected = { location -> onEvent(LayoutSettingsEvent.SetDiscoverLocation(location)) }
    )
}

@Composable
private fun LayoutCard(
    layout: HomeLayout,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val animatePreview = isSelected || isFocused

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
            }
        },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
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
                    HomeLayout.CLASSIC -> ClassicLayoutPreview(
                        modifier = Modifier.fillMaxWidth(),
                        animated = animatePreview
                    )
                    HomeLayout.GRID -> GridLayoutPreview(
                        modifier = Modifier.fillMaxWidth(),
                        animated = animatePreview
                    )
                    HomeLayout.MODERN -> ModernLayoutPreview(
                        modifier = Modifier.fillMaxWidth(),
                        animated = animatePreview
                    )
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
                        tint = NuvioTheme.colors.FocusRing,
                        modifier = Modifier
                            .size(NuvioTheme.spacing.lg)
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
                    color = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
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
    modifier: Modifier = Modifier
) {
    SettingsChoiceChip(
        modifier = modifier,
        label = catalogInfo.name,
        selected = isSelected,
        onClick = onClick
    )
}

@Composable
private fun DiscoverLocationRow(
    selectedLocation: DiscoverLocation,
    rememberedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit
) {
    val sectionEnabled = selectedLocation != DiscoverLocation.OFF
    var dialogOpen by remember { mutableStateOf(false) }

    SettingsToggleRow(
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
        }
    )
    if (sectionEnabled) {
        val currentLabel = when (selectedLocation) {
            DiscoverLocation.IN_SIDEBAR -> stringResource(R.string.layout_discover_location_in_sidebar)
            DiscoverLocation.IN_SEARCH -> stringResource(R.string.layout_discover_location_in_search)
            DiscoverLocation.OFF -> ""
        }
        SettingsActionRow(
            title = stringResource(R.string.layout_discover_location_action),
            subtitle = null,
            value = currentLabel,
            onClick = { dialogOpen = true }
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
    val options = listOf(
        SettingsPickerOption(
            DiscoverLocation.IN_SEARCH,
            stringResource(R.string.layout_discover_location_in_search),
            stringResource(R.string.layout_discover_location_in_search_desc)
        ),
        SettingsPickerOption(
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

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_discover_location_dialog_title),
        options = options,
        selectedValue = effectiveSelected,
        onOptionSelected = onLocationSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 320.dp
    )
}

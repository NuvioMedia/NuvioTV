@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_COVERAGE
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_STRENGTH
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_SHEEN_STRENGTH
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.CardCwStylePreview
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PosterCwStylePreview
import com.nuvio.tv.ui.components.WideCwStylePreview
import com.nuvio.tv.ui.components.cardDepthVisual

@Composable
internal fun LayoutContinueWatchingSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    var showSortModeDialog by rememberSaveable { mutableStateOf(false) }

    SettingsToggleRow(
        title = stringResource(R.string.layout_cw_enabled),
        subtitle = stringResource(R.string.layout_cw_enabled_sub),
        checked = uiState.continueWatchingEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetContinueWatchingEnabled(!uiState.continueWatchingEnabled)) }
    )

    if (uiState.continueWatchingEnabled) {
        val firstStyleFocusRequester = remember { FocusRequester() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .settingsOptionRow(firstStyleFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            listOf(
                ContinueWatchingCardStyle.CARD,
                ContinueWatchingCardStyle.WIDE,
                ContinueWatchingCardStyle.POSTER
            ).forEachIndexed { index, style ->
                CwStyleCard(
                    style = style,
                    isSelected = uiState.continueWatchingCardStyle == style,
                    onClick = { onEvent(LayoutSettingsEvent.SetContinueWatchingCardStyle(style)) },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (index == 0) Modifier.focusRequester(firstStyleFocusRequester) else Modifier)
                )
            }
        }

        val episodeThumbnailsApply = uiState.continueWatchingCardStyle != ContinueWatchingCardStyle.POSTER
        if (episodeThumbnailsApply) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_use_episode_thumbnails_cw),
                subtitle = stringResource(R.string.layout_use_episode_thumbnails_cw_sub),
                checked = uiState.useEpisodeThumbnailsInCw,
                onToggle = { onEvent(LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw(!uiState.useEpisodeThumbnailsInCw)) }
            )
        }
        if (episodeThumbnailsApply && uiState.useEpisodeThumbnailsInCw) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_blur_cw_next_up),
                subtitle = stringResource(R.string.layout_blur_cw_next_up_sub),
                checked = uiState.blurContinueWatchingNextUp,
                onToggle = { onEvent(LayoutSettingsEvent.SetBlurContinueWatchingNextUp(!uiState.blurContinueWatchingNextUp)) }
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.layout_next_up_furthest_episode),
            subtitle = stringResource(R.string.layout_next_up_furthest_episode_sub),
            checked = uiState.nextUpFromFurthestEpisode,
            onToggle = { onEvent(LayoutSettingsEvent.SetNextUpFromFurthestEpisode(!uiState.nextUpFromFurthestEpisode)) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.layout_show_unaired_next_up),
            subtitle = stringResource(R.string.layout_show_unaired_next_up_sub),
            checked = uiState.showUnairedNextUp,
            onToggle = { onEvent(LayoutSettingsEvent.SetShowUnairedNextUp(!uiState.showUnairedNextUp)) }
        )
        SettingsActionRow(
            title = stringResource(R.string.layout_cw_sort_mode),
            subtitle = stringResource(R.string.layout_cw_sort_mode_sub),
            value = when (uiState.continueWatchingSortMode) {
                ContinueWatchingSortMode.DEFAULT -> stringResource(R.string.layout_cw_sort_default)
                ContinueWatchingSortMode.STREAMING_STYLE -> stringResource(R.string.layout_cw_sort_streaming)
                ContinueWatchingSortMode.SPLIT_UPCOMING -> stringResource(R.string.layout_cw_sort_split_upcoming)
            },
            onClick = { showSortModeDialog = true }
        )
    }

    if (showSortModeDialog) {
        ContinueWatchingSortModeDialog(
            currentMode = uiState.continueWatchingSortMode,
            onModeSelected = { mode ->
                onEvent(LayoutSettingsEvent.SetContinueWatchingSortMode(mode))
                showSortModeDialog = false
            },
            onDismiss = { showSortModeDialog = false }
        )
    }
}

internal fun LayoutSettingsUiState.focusedPosterHasOptions(): Boolean {
    val modernLandscape = selectedLayout == HomeLayout.MODERN && modernLandscapePostersEnabled
    return selectedLayout != HomeLayout.GRID && (!modernLandscape || AppFeaturePolicy.inAppTrailerPlaybackEnabled)
}

@Composable
internal fun LayoutFocusedPosterSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val isModern = uiState.selectedLayout == HomeLayout.MODERN
    val isModernLandscape = isModern && uiState.modernLandscapePostersEnabled
    val showAutoplayRow = AppFeaturePolicy.inAppTrailerPlaybackEnabled &&
        (uiState.focusedPosterBackdropExpandEnabled || isModernLandscape)

    if (!isModernLandscape) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_expand_poster),
            subtitle = stringResource(R.string.layout_expand_poster_sub),
            checked = uiState.focusedPosterBackdropExpandEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled(!uiState.focusedPosterBackdropExpandEnabled))
            }
        )
        if (uiState.focusedPosterBackdropExpandEnabled) {
            SliderSettingsItem(
                title = stringResource(R.string.layout_expand_delay),
                subtitle = stringResource(R.string.layout_expand_delay_sub),
                value = uiState.focusedPosterBackdropExpandDelaySeconds,
                valueText = "${uiState.focusedPosterBackdropExpandDelaySeconds}s",
                minValue = 0,
                maxValue = 10,
                step = 1,
                onValueChange = { seconds ->
                    onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds(seconds))
                }
            )
        }
    }

    if (showAutoplayRow) {
        SettingsToggleRow(
            title = stringResource(
                if (isModern) R.string.layout_autoplay_trailer else R.string.layout_autoplay_trailer_expanded
            ),
            subtitle = stringResource(
                if (isModern) R.string.layout_autoplay_trailer_sub else R.string.layout_autoplay_trailer_expanded_sub
            ),
            checked = uiState.focusedPosterBackdropTrailerEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled(!uiState.focusedPosterBackdropTrailerEnabled))
            }
        )
        if (uiState.focusedPosterBackdropTrailerEnabled) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_trailer_muted),
                subtitle = stringResource(
                    if (isModern) R.string.layout_trailer_muted_sub_preview else R.string.layout_trailer_muted_sub_expanded
                ),
                checked = uiState.focusedPosterBackdropTrailerMuted,
                onToggle = {
                    onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted(!uiState.focusedPosterBackdropTrailerMuted))
                }
            )
            if (isModern) {
                ModernTrailerPlaybackTargetRow(
                    selectedTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget,
                    onTargetSelected = { target ->
                        onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerPlaybackTarget(target))
                    }
                )
            }
        }
    }
}

@Composable
internal fun LayoutPosterCardSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
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

    OptionRow(
        title = stringResource(R.string.layout_card_width),
        selectedValue = uiState.posterCardWidthDp,
        options = widthOptions,
        onSelected = { width -> onEvent(LayoutSettingsEvent.SetPosterCardWidth(width)) }
    )
    OptionRow(
        title = stringResource(R.string.layout_card_radius),
        selectedValue = uiState.posterCardCornerRadiusDp,
        options = radiusOptions,
        onSelected = { radius -> onEvent(LayoutSettingsEvent.SetPosterCardCornerRadius(radius)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_always_show_landscape_clearlogo),
        subtitle = stringResource(R.string.layout_always_show_landscape_clearlogo_sub),
        checked = uiState.alwaysShowLandscapeClearlogo,
        onToggle = { onEvent(LayoutSettingsEvent.SetAlwaysShowLandscapeClearlogo(!uiState.alwaysShowLandscapeClearlogo)) }
    )
    SettingsResetButton(
        text = stringResource(R.string.layout_reset_default),
        onClick = { onEvent(LayoutSettingsEvent.ResetPosterCardStyle) }
    )
}

@Composable
internal fun LayoutCardDepthSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    var showFineTuneDialog by rememberSaveable { mutableStateOf(false) }

    CardDepthStyleControls(
        style = uiState.cardDepthStyle,
        onEnabledChange = { enabled -> onEvent(LayoutSettingsEvent.SetCardDepthEnabled(enabled)) },
        onEdgeStrengthChange = { strength -> onEvent(LayoutSettingsEvent.SetCardDepthEdgeStrength(strength)) },
        onSheenStrengthChange = { strength -> onEvent(LayoutSettingsEvent.SetCardDepthSheenStrength(strength)) },
        onEdgeCoverageChange = { coverage -> onEvent(LayoutSettingsEvent.SetCardDepthEdgeCoverage(coverage)) },
        onSurfaceEnabledChange = { surface, enabled ->
            onEvent(LayoutSettingsEvent.SetCardDepthSurfaceEnabled(surface, enabled))
        },
        onFineTune = { showFineTuneDialog = true },
        onReset = { onEvent(LayoutSettingsEvent.ResetCardDepthStyle) }
    )

    if (showFineTuneDialog) {
        CardDepthFineTuneDialog(
            style = uiState.cardDepthStyle,
            onEdgeStrengthChange = { strength -> onEvent(LayoutSettingsEvent.SetCardDepthEdgeStrength(strength)) },
            onSheenStrengthChange = { strength -> onEvent(LayoutSettingsEvent.SetCardDepthSheenStrength(strength)) },
            onEdgeCoverageChange = { coverage -> onEvent(LayoutSettingsEvent.SetCardDepthEdgeCoverage(coverage)) },
            onReset = {
                onEvent(LayoutSettingsEvent.SetCardDepthEdgeStrength(DEFAULT_CARD_DEPTH_EDGE_STRENGTH))
                onEvent(LayoutSettingsEvent.SetCardDepthSheenStrength(DEFAULT_CARD_DEPTH_SHEEN_STRENGTH))
                onEvent(LayoutSettingsEvent.SetCardDepthEdgeCoverage(DEFAULT_CARD_DEPTH_EDGE_COVERAGE))
            },
            onDismiss = { showFineTuneDialog = false }
        )
    }
}

@Composable
internal fun LayoutCustomPosterSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit,
    onConfigureViaPhone: () -> Unit
) {
    val pattern = uiState.customPosterUrlPattern
    val isActive = pattern.isNotBlank()

    SettingsNote(text = stringResource(R.string.layout_custom_poster_description))
    if (isActive) {
        Text(
            text = pattern,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)
        )
    }
    SettingsActionRow(
        title = stringResource(R.string.layout_custom_poster_qr),
        subtitle = if (isActive) stringResource(R.string.layout_custom_poster_active) else null,
        onClick = onConfigureViaPhone
    )
    if (isActive) {
        SettingsActionRow(
            title = stringResource(R.string.layout_custom_poster_clear),
            subtitle = null,
            onClick = { onEvent(LayoutSettingsEvent.ClearCustomPosterSettings) }
        )
        SettingsSectionLabel(text = stringResource(R.string.settings_card_depth_apply_to))
        listOf(
            CustomPosterScreen.HOME to R.string.layout_custom_poster_screen_home,
            CustomPosterScreen.CONTINUE_WATCHING to R.string.layout_custom_poster_screen_continue_watching,
            CustomPosterScreen.COLLECTIONS to R.string.layout_custom_poster_screen_collections,
            CustomPosterScreen.LIBRARY to R.string.layout_custom_poster_screen_library,
            CustomPosterScreen.SEARCH to R.string.layout_custom_poster_screen_search,
            CustomPosterScreen.DETAILS to R.string.layout_custom_poster_screen_details
        ).forEach { (screen, label) ->
            val enabled = screen in uiState.customPosterEnabledScreens
            SettingsToggleRow(
                title = stringResource(label),
                subtitle = null,
                checked = enabled,
                onToggle = { onEvent(LayoutSettingsEvent.SetCustomPosterScreenEnabled(screen, !enabled)) }
            )
        }
    }
}

@Composable
private fun CwStyleCard(
    style: ContinueWatchingCardStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

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
                when (style) {
                    ContinueWatchingCardStyle.CARD -> CardCwStylePreview(modifier = Modifier.fillMaxSize())
                    ContinueWatchingCardStyle.WIDE -> WideCwStylePreview(modifier = Modifier.fillMaxSize())
                    ContinueWatchingCardStyle.POSTER -> PosterCwStylePreview(modifier = Modifier.fillMaxSize())
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
                    text = when (style) {
                        ContinueWatchingCardStyle.CARD -> stringResource(R.string.layout_cw_card_style_card)
                        ContinueWatchingCardStyle.WIDE -> stringResource(R.string.layout_cw_card_style_wide)
                        ContinueWatchingCardStyle.POSTER -> stringResource(R.string.layout_cw_card_style_poster)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingSortModeDialog(
    currentMode: ContinueWatchingSortMode,
    onModeSelected: (ContinueWatchingSortMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            ContinueWatchingSortMode.DEFAULT,
            stringResource(R.string.layout_cw_sort_default),
            stringResource(R.string.layout_cw_sort_default_desc)
        ),
        SettingsPickerOption(
            ContinueWatchingSortMode.STREAMING_STYLE,
            stringResource(R.string.layout_cw_sort_streaming),
            stringResource(R.string.layout_cw_sort_streaming_desc)
        ),
        SettingsPickerOption(
            ContinueWatchingSortMode.SPLIT_UPCOMING,
            stringResource(R.string.layout_cw_sort_split_upcoming),
            stringResource(R.string.layout_cw_sort_split_upcoming_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_cw_sort_mode),
        options = options,
        selectedValue = currentMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun ModernTrailerPlaybackTargetRow(
    selectedTarget: FocusedPosterTrailerPlaybackTarget,
    onTargetSelected: (FocusedPosterTrailerPlaybackTarget) -> Unit
) {
    Text(
        text = stringResource(R.string.layout_trailer_location),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary
    )
    Text(
        text = stringResource(R.string.layout_trailer_location_sub),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary
    )
    val firstTrailerTargetFocusRequester = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier.settingsOptionRow(firstTrailerTargetFocusRequester),
        contentPadding = PaddingValues(end = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        item(key = "trailer_target_expanded_card") {
            SettingsChoiceChip(
                modifier = Modifier.focusRequester(firstTrailerTargetFocusRequester),
                label = stringResource(R.string.layout_trailer_expanded_card),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)
                }
            )
        }
        item(key = "trailer_target_hero_media") {
            SettingsChoiceChip(
                label = stringResource(R.string.layout_trailer_hero_media),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
                }
            )
        }
    }
}

@Composable
private fun CardDepthStyleControls(
    style: CardDepthStyle,
    onEnabledChange: (Boolean) -> Unit,
    onEdgeStrengthChange: (Int) -> Unit,
    onSheenStrengthChange: (Int) -> Unit,
    onEdgeCoverageChange: (Int) -> Unit,
    onSurfaceEnabledChange: (CardDepthSurface, Boolean) -> Unit,
    onFineTune: () -> Unit,
    onReset: () -> Unit
) {
    val edgeOptions = listOf(
        PresetOption(stringResource(R.string.settings_card_depth_edge_subtle), 28),
        PresetOption(stringResource(R.string.settings_card_depth_edge_balanced), 42),
        PresetOption(stringResource(R.string.settings_card_depth_edge_bold), 56)
    )
    val sheenOptions = listOf(
        PresetOption(stringResource(R.string.settings_card_depth_sheen_off), 0),
        PresetOption(stringResource(R.string.settings_card_depth_sheen_soft), 10),
        PresetOption(stringResource(R.string.settings_card_depth_sheen_bright), 16)
    )
    val coverageOptions = listOf(
        PresetOption(stringResource(R.string.settings_card_depth_coverage_top), 0),
        PresetOption(stringResource(R.string.settings_card_depth_coverage_half), 50),
        PresetOption(stringResource(R.string.settings_card_depth_coverage_full), 100)
    )
    val surfaces = listOf(
        stringResource(R.string.settings_card_depth_surface_posters) to CardDepthSurface.POSTERS,
        stringResource(R.string.settings_card_depth_surface_continue_watching) to CardDepthSurface.CONTINUE_WATCHING,
        stringResource(R.string.settings_card_depth_surface_episodes) to CardDepthSurface.EPISODE_CARDS,
        stringResource(R.string.settings_card_depth_surface_cast) to CardDepthSurface.CAST,
        stringResource(R.string.settings_card_depth_surface_trailers) to CardDepthSurface.TRAILERS
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.settings_card_depth_enabled),
            subtitle = stringResource(R.string.settings_card_depth_description),
            checked = style.enabled,
            onToggle = { onEnabledChange(!style.enabled) }
        )

        if (style.enabled) {
            OptionRow(
                title = stringResource(R.string.settings_card_depth_edge),
                selectedValue = style.edgeStrength,
                options = edgeOptions,
                onSelected = onEdgeStrengthChange
            )
            OptionRow(
                title = stringResource(R.string.settings_card_depth_sheen),
                selectedValue = style.sheenStrength,
                options = sheenOptions,
                onSelected = onSheenStrengthChange
            )
            OptionRow(
                title = stringResource(R.string.settings_card_depth_edge_coverage),
                selectedValue = style.edgeCoverage,
                options = coverageOptions,
                onSelected = onEdgeCoverageChange
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_card_depth_fine_tune),
                subtitle = stringResource(R.string.settings_card_depth_fine_tune_hint_tv),
                onClick = onFineTune,
                trailingIcon = Icons.Default.Tune
            )
            SettingsSectionLabel(text = stringResource(R.string.settings_card_depth_apply_to))
            surfaces.forEach { (title, surface) ->
                SettingsToggleRow(
                    title = title,
                    subtitle = null,
                    checked = style.isSurfaceEnabled(surface),
                    onToggle = {
                        onSurfaceEnabledChange(surface, !style.isSurfaceEnabled(surface))
                    }
                )
            }
        }

        SettingsResetButton(
            text = stringResource(R.string.layout_reset_default),
            onClick = onReset
        )
    }
}

@Composable
private fun CardDepthFineTuneDialog(
    style: CardDepthStyle,
    onEdgeStrengthChange: (Int) -> Unit,
    onSheenStrengthChange: (Int) -> Unit,
    onEdgeCoverageChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_card_depth_fine_tune_title),
        subtitle = stringResource(R.string.settings_card_depth_fine_tune_hint_tv),
        width = 680.dp,
        usePlatformDefaultWidth = false
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            CardDepthPreview(
                style = style,
                modifier = Modifier
                    .width(260.dp)
                    .aspectRatio(2f / 3f)
            )
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                SliderSettingsItem(
                    title = stringResource(R.string.settings_card_depth_edge_value),
                    value = style.edgeStrength.coerceAtMost(70),
                    valueText = "${style.edgeStrength}%",
                    minValue = 0,
                    maxValue = 70,
                    step = 1,
                    onValueChange = onEdgeStrengthChange,
                    modifier = Modifier.focusRequester(initialFocusRequester)
                )
                SliderSettingsItem(
                    title = stringResource(R.string.settings_card_depth_sheen_value),
                    value = style.sheenStrength.coerceAtMost(25),
                    valueText = "${style.sheenStrength}%",
                    minValue = 0,
                    maxValue = 25,
                    step = 1,
                    onValueChange = onSheenStrengthChange
                )
                SliderSettingsItem(
                    title = stringResource(R.string.settings_card_depth_coverage_value),
                    value = style.edgeCoverage,
                    valueText = "${style.edgeCoverage}%",
                    minValue = 0,
                    maxValue = 100,
                    step = 1,
                    onValueChange = onEdgeCoverageChange
                )
                CardDepthResetButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CardDepthResetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(SettingsSecondaryCardRadius)
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = shape),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        )
    ) {
        Text(
            text = stringResource(R.string.layout_reset_default),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
    }
}

@Composable
private fun CardDepthPreview(
    style: CardDepthStyle,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF33415C),
                        Color(0xFF232D42),
                        Color(0xFF141A28)
                    )
                )
            )
            .cardDepthVisual(
                shape = shape,
                edgeStrength = style.edgeStrength.toFloat(),
                sheenStrength = style.sheenStrength.toFloat(),
                edgeCoverage = style.edgeCoverage.toFloat()
            )
    )
}

@Composable
private fun OptionRow(
    title: String,
    selectedValue: Int,
    options: List<PresetOption>,
    onSelected: (Int) -> Unit
) {
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: stringResource(R.string.layout_custom)

    Text(
        text = "$title ($selectedLabel)",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary
    )

    val firstOptionFocusRequester = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier.settingsOptionRow(firstOptionFocusRequester),
        contentPadding = PaddingValues(end = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        itemsIndexed(
            items = options,
            key = { _, option -> option.value }
        ) { optionIndex, option ->
            ValueChip(
                label = option.label,
                isSelected = option.value == selectedValue,
                onClick = { onSelected(option.value) },
                modifier = if (optionIndex == 0) {
                    Modifier.focusRequester(firstOptionFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsChoiceChip(
        modifier = modifier,
        label = label,
        selected = isSelected,
        onClick = onClick
    )
}

private data class PresetOption(
    val label: String,
    val value: Int
)

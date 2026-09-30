package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle

@Composable
internal fun LayoutDetailPageSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    var showOverlayStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showRatingsDialog by rememberSaveable { mutableStateOf(false) }

    SettingsActionRow(
        title = stringResource(R.string.layout_episode_options_overlay),
        subtitle = stringResource(R.string.layout_episode_options_overlay_sub),
        value = episodeOptionsOverlayStyleLabel(uiState.episodeOptionsOverlayStyle),
        onClick = { showOverlayStyleDialog = true }
    )
    SettingsToggleRow(
        title = stringResource(R.string.random_episode_title),
        subtitle = stringResource(R.string.layout_random_episode_sub),
        checked = uiState.randomEpisodeEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetRandomEpisodeEnabled(!uiState.randomEpisodeEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_blur_unwatched),
        subtitle = stringResource(R.string.layout_blur_unwatched_sub),
        checked = uiState.blurUnwatchedEpisodes,
        onToggle = { onEvent(LayoutSettingsEvent.SetBlurUnwatchedEpisodes(!uiState.blurUnwatchedEpisodes)) }
    )
    SettingsActionRow(
        title = stringResource(R.string.layout_episode_ratings),
        subtitle = stringResource(R.string.layout_episode_ratings_sub),
        value = episodeRatingsVisibilityLabel(uiState.detailImdbRatingsVisibility),
        onClick = { showRatingsDialog = true }
    )
    if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
        SettingsToggleRow(
            title = stringResource(R.string.audio_autoplay_trailers),
            subtitle = stringResource(R.string.audio_autoplay_trailers_sub),
            checked = uiState.detailPageTrailerAutoplayEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled(!uiState.detailPageTrailerAutoplayEnabled))
            }
        )
        if (uiState.detailPageTrailerAutoplayEnabled) {
            SettingsToggleRow(
                title = stringResource(R.string.audio_trailer_background),
                subtitle = stringResource(R.string.audio_trailer_background_sub),
                checked = uiState.detailPageTrailerBackgroundEnabled,
                onToggle = {
                    onEvent(LayoutSettingsEvent.SetDetailPageTrailerBackgroundEnabled(!uiState.detailPageTrailerBackgroundEnabled))
                }
            )
            SliderSettingsItem(
                title = stringResource(R.string.audio_trailer_delay),
                value = uiState.detailPageTrailerAutoplayDelaySeconds,
                valueText = "${uiState.detailPageTrailerAutoplayDelaySeconds}s",
                minValue = 0,
                maxValue = 15,
                step = 1,
                onValueChange = { seconds ->
                    onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayDelaySeconds(seconds))
                }
            )
        }
    }
    SettingsToggleRow(
        title = stringResource(R.string.layout_trailer_button),
        subtitle = stringResource(R.string.layout_trailer_button_sub),
        checked = uiState.detailPageTrailerButtonEnabled,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(!uiState.detailPageTrailerButtonEnabled))
        }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_prefer_external_meta),
        subtitle = stringResource(R.string.layout_prefer_external_meta_sub),
        checked = uiState.preferExternalMetaAddonDetail,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(!uiState.preferExternalMetaAddonDetail))
        }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_show_full_release_date),
        subtitle = stringResource(R.string.layout_show_full_release_date_sub),
        checked = uiState.showFullReleaseDate,
        onToggle = { onEvent(LayoutSettingsEvent.SetShowFullReleaseDate(!uiState.showFullReleaseDate)) }
    )

    if (showOverlayStyleDialog) {
        EpisodeOptionsOverlayStyleDialog(
            currentStyle = uiState.episodeOptionsOverlayStyle,
            onStyleSelected = { style ->
                onEvent(LayoutSettingsEvent.SetEpisodeOptionsOverlayStyle(style))
                showOverlayStyleDialog = false
            },
            onDismiss = { showOverlayStyleDialog = false }
        )
    }
    if (showRatingsDialog) {
        EpisodeRatingsDialog(
            currentVisibility = uiState.detailImdbRatingsVisibility,
            onVisibilitySelected = { visibility ->
                onEvent(LayoutSettingsEvent.SetDetailImdbRatingsVisibility(visibility))
                showRatingsDialog = false
            },
            onDismiss = { showRatingsDialog = false }
        )
    }
}

@Composable
internal fun LayoutStreamsSection(
    streamBadgeUiState: StreamBadgeSettingsUiState,
    onShowFileSizeBadgesChange: (Boolean) -> Unit,
    onShowAddonLogoChange: (Boolean) -> Unit,
    onBadgePlacementSelected: (StreamBadgePlacement) -> Unit,
    onConfigureBadges: () -> Unit
) {
    var showPositionDialog by rememberSaveable { mutableStateOf(false) }

    SettingsSectionLabel(text = stringResource(R.string.settings_stream_badges_section))
    SettingsToggleRow(
        title = stringResource(R.string.settings_stream_size_badges_title),
        subtitle = stringResource(R.string.settings_stream_size_badges_description),
        checked = streamBadgeUiState.showFileSizeBadges,
        onToggle = { onShowFileSizeBadgesChange(!streamBadgeUiState.showFileSizeBadges) }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_stream_badge_position_title),
        subtitle = null,
        value = streamBadgePlacementLabel(streamBadgeUiState.badgePlacement),
        onClick = { showPositionDialog = true }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_stream_badge_urls_title),
        subtitle = streamBadgeRulesPreview(streamBadgeUiState),
        onClick = onConfigureBadges
    )

    SettingsSectionLabel(text = stringResource(R.string.settings_stream_display_section))
    SettingsToggleRow(
        title = stringResource(R.string.settings_stream_addon_logo_title),
        subtitle = stringResource(R.string.settings_stream_addon_logo_description),
        checked = streamBadgeUiState.showAddonLogo,
        onToggle = { onShowAddonLogoChange(!streamBadgeUiState.showAddonLogo) }
    )

    if (showPositionDialog) {
        StreamBadgePositionDialog(
            currentPlacement = streamBadgeUiState.badgePlacement,
            onPlacementSelected = { placement ->
                onBadgePlacementSelected(placement)
                showPositionDialog = false
            },
            onDismiss = { showPositionDialog = false }
        )
    }
}

@Composable
private fun episodeRatingsVisibilityLabel(visibility: DetailImdbRatingsVisibility): String =
    when (visibility) {
        DetailImdbRatingsVisibility.SHOW_ALL -> stringResource(R.string.layout_ratings_show)
        DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES -> stringResource(R.string.layout_ratings_hide_unwatched)
        DetailImdbRatingsVisibility.HIDE_EPISODES,
        DetailImdbRatingsVisibility.HIDE_ALL -> stringResource(R.string.layout_ratings_hide)
    }

@Composable
private fun episodeOptionsOverlayStyleLabel(style: EpisodeOptionsOverlayStyle): String =
    when (style) {
        EpisodeOptionsOverlayStyle.NONE -> stringResource(R.string.layout_episode_options_overlay_none)
        EpisodeOptionsOverlayStyle.ARTWORK -> stringResource(R.string.layout_episode_options_overlay_artwork)
        EpisodeOptionsOverlayStyle.BLUR -> stringResource(R.string.layout_episode_options_overlay_blur)
    }

@Composable
private fun streamBadgePlacementLabel(placement: StreamBadgePlacement): String =
    when (placement) {
        StreamBadgePlacement.TOP -> stringResource(R.string.settings_stream_badge_position_top)
        StreamBadgePlacement.BOTTOM -> stringResource(R.string.settings_stream_badge_position_bottom)
    }

@Composable
private fun streamBadgeRulesPreview(uiState: StreamBadgeSettingsUiState): String {
    val rules = uiState.rules.normalized()
    return if (rules.hasImport) {
        stringResource(
            R.string.settings_fusion_badges_summary,
            rules.imports.size,
            STREAM_BADGE_IMPORT_LIMIT,
            rules.enabledFilterCount
        )
    } else {
        stringResource(R.string.settings_fusion_badges_empty)
    }
}

@Composable
private fun StreamBadgePositionDialog(
    currentPlacement: StreamBadgePlacement,
    onPlacementSelected: (StreamBadgePlacement) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            StreamBadgePlacement.BOTTOM,
            stringResource(R.string.settings_stream_badge_position_bottom)
        ),
        SettingsPickerOption(
            StreamBadgePlacement.TOP,
            stringResource(R.string.settings_stream_badge_position_top)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_stream_badge_position_dialog_title),
        subtitle = stringResource(R.string.settings_stream_badge_position_dialog_description),
        options = options,
        selectedValue = currentPlacement,
        onOptionSelected = onPlacementSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 260.dp
    )
}

@Composable
private fun EpisodeRatingsDialog(
    currentVisibility: DetailImdbRatingsVisibility,
    onVisibilitySelected: (DetailImdbRatingsVisibility) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            DetailImdbRatingsVisibility.SHOW_ALL,
            stringResource(R.string.layout_ratings_show)
        ),
        SettingsPickerOption(
            DetailImdbRatingsVisibility.HIDE_EPISODES,
            stringResource(R.string.layout_ratings_hide)
        ),
        SettingsPickerOption(
            DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES,
            stringResource(R.string.layout_ratings_hide_unwatched)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_episode_ratings),
        subtitle = stringResource(R.string.layout_episode_ratings_sub),
        options = options,
        selectedValue = currentVisibility,
        onOptionSelected = onVisibilitySelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 340.dp
    )
}

@Composable
private fun EpisodeOptionsOverlayStyleDialog(
    currentStyle: EpisodeOptionsOverlayStyle,
    onStyleSelected: (EpisodeOptionsOverlayStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            EpisodeOptionsOverlayStyle.BLUR,
            stringResource(R.string.layout_episode_options_overlay_blur),
            stringResource(R.string.layout_episode_options_overlay_blur_desc)
        ),
        SettingsPickerOption(
            EpisodeOptionsOverlayStyle.ARTWORK,
            stringResource(R.string.layout_episode_options_overlay_artwork),
            stringResource(R.string.layout_episode_options_overlay_artwork_desc)
        ),
        SettingsPickerOption(
            EpisodeOptionsOverlayStyle.NONE,
            stringResource(R.string.layout_episode_options_overlay_none),
            stringResource(R.string.layout_episode_options_overlay_none_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_episode_options_overlay),
        subtitle = stringResource(R.string.layout_episode_options_overlay_sub),
        options = options,
        selectedValue = currentStyle,
        onOptionSelected = onStyleSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 380.dp
    )
}

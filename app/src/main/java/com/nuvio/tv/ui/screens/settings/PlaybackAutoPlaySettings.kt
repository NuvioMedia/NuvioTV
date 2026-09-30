@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import com.nuvio.tv.R
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
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.ui.components.NuvioDialog
import kotlin.math.roundToInt
import java.util.Locale

@Composable
internal fun PlaybackStreamSelectionSection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    val effectiveAutoPlaySource = if (
        !AppFeaturePolicy.pluginsEnabled &&
        settings.streamAutoPlaySource == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY
    ) {
        StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
    } else {
        settings.streamAutoPlaySource
    }

    SettingsActionRow(
        title = stringResource(R.string.autoplay_stream_selection),
        subtitle = null,
        value = when (settings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> stringResource(R.string.autoplay_mode_manual)
            StreamAutoPlayMode.FIRST_STREAM -> stringResource(R.string.autoplay_mode_first)
            StreamAutoPlayMode.REGEX_MATCH -> stringResource(R.string.autoplay_mode_regex)
        },
        onClick = { onOpenDialog(PlaybackDialog.STREAM_AUTO_PLAY_MODE) }
    )

    if (settings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
        SettingsActionRow(
            title = stringResource(R.string.autoplay_regex_title),
            subtitle = settings.streamAutoPlayRegex.ifBlank { stringResource(R.string.autoplay_regex_placeholder) },
            onClick = { onOpenDialog(PlaybackDialog.STREAM_REGEX) }
        )
    }

    if (settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL) {
        SettingsActionRow(
            title = stringResource(R.string.autoplay_scope),
            subtitle = null,
            value = when (effectiveAutoPlaySource) {
                StreamAutoPlaySource.ALL_SOURCES -> stringResource(R.string.autoplay_scope_all)
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> stringResource(R.string.autoplay_scope_addons)
                StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> stringResource(R.string.autoplay_scope_plugins)
            },
            onClick = { onOpenDialog(PlaybackDialog.STREAM_AUTO_PLAY_SOURCE) }
        )

        if (effectiveAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            SettingsActionRow(
                title = stringResource(R.string.autoplay_allowed_addons),
                subtitle = null,
                value = selectionSummary(
                    selectedCount = settings.streamAutoPlaySelectedAddons.size,
                    allLabel = stringResource(R.string.autoplay_all_addons)
                ),
                onClick = { onOpenDialog(PlaybackDialog.STREAM_AUTO_PLAY_ADDONS) }
            )
        }

        if (
            AppFeaturePolicy.pluginsEnabled &&
            effectiveAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        ) {
            SettingsActionRow(
                title = stringResource(R.string.autoplay_allowed_plugins),
                subtitle = null,
                value = selectionSummary(
                    selectedCount = settings.streamAutoPlaySelectedPlugins.size,
                    allLabel = stringResource(R.string.autoplay_all_plugins)
                ),
                onClick = { onOpenDialog(PlaybackDialog.STREAM_AUTO_PLAY_PLUGINS) }
            )
        }
    }

    val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
    SliderSettingsItem(
        title = stringResource(R.string.autoplay_timeout_title),
        subtitle = stringResource(R.string.autoplay_timeout_sub),
        values = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES,
        selected = timeoutSeconds,
        valueText = when (timeoutSeconds) {
            0 -> stringResource(R.string.autoplay_timeout_instant)
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED -> stringResource(R.string.autoplay_timeout_unlimited)
            else -> "${timeoutSeconds}s"
        },
        onValueChange = { seconds -> onUpdate { setStreamAutoPlayTimeoutSeconds(seconds) } }
    )

    SettingsToggleRow(
        title = stringResource(R.string.autoplay_reuse_last_link),
        subtitle = stringResource(R.string.autoplay_reuse_last_link_sub),
        checked = settings.streamReuseLastLinkEnabled,
        onToggle = { onUpdate { setStreamReuseLastLinkEnabled(!settings.streamReuseLastLinkEnabled) } }
    )

    if (settings.streamReuseLastLinkEnabled) {
        SettingsActionRow(
            title = stringResource(R.string.autoplay_last_link_cache),
            subtitle = null,
            value = formatReuseCacheDuration(settings.streamReuseLastLinkCacheHours),
            onClick = { onOpenDialog(PlaybackDialog.REUSE_LAST_LINK_CACHE) }
        )
    }
}

@Composable
internal fun PlaybackUpNextSection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.autoplay_next_episode),
        subtitle = stringResource(R.string.autoplay_next_episode_sub),
        checked = settings.streamAutoPlayNextEpisodeEnabled,
        onToggle = { onUpdate { setStreamAutoPlayNextEpisodeEnabled(!settings.streamAutoPlayNextEpisodeEnabled) } }
    )

    if (settings.streamAutoPlayNextEpisodeEnabled && settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
        SettingsToggleRow(
            title = stringResource(R.string.autoplay_next_episode_fallback),
            subtitle = stringResource(R.string.autoplay_next_episode_fallback_sub),
            checked = settings.streamAutoPlayNextEpisodeFallbackEnabled,
            onToggle = {
                onUpdate { setStreamAutoPlayNextEpisodeFallbackEnabled(!settings.streamAutoPlayNextEpisodeFallbackEnabled) }
            }
        )
    }

    SettingsActionRow(
        title = stringResource(R.string.autoplay_threshold_mode),
        subtitle = null,
        value = when (settings.nextEpisodeThresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> stringResource(R.string.autoplay_threshold_pct)
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> stringResource(R.string.autoplay_threshold_min)
        },
        onClick = { onOpenDialog(PlaybackDialog.NEXT_EPISODE_THRESHOLD_MODE) }
    )

    when (settings.nextEpisodeThresholdMode) {
        NextEpisodeThresholdMode.PERCENTAGE -> SliderSettingsItem(
            title = stringResource(R.string.autoplay_threshold_pct_title),
            subtitle = stringResource(R.string.autoplay_threshold_pct_sub),
            value = (settings.nextEpisodeThresholdPercent * 2f).roundToInt(),
            valueText = "${formatHalfStepValue(settings.nextEpisodeThresholdPercent)}%",
            minValue = 194,
            maxValue = 200,
            step = 1,
            onValueChange = { value -> onUpdate { setNextEpisodeThresholdPercent(value / 2f) } }
        )
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> SliderSettingsItem(
            title = stringResource(R.string.autoplay_threshold_min_title),
            subtitle = stringResource(R.string.autoplay_threshold_pct_sub),
            value = (settings.nextEpisodeThresholdMinutesBeforeEnd * 2f).roundToInt(),
            valueText = "${formatHalfStepValue(settings.nextEpisodeThresholdMinutesBeforeEnd)} min",
            minValue = 0,
            maxValue = 7,
            step = 1,
            onValueChange = { value -> onUpdate { setNextEpisodeThresholdMinutesBeforeEnd(value / 2f) } }
        )
    }

    if (settings.streamAutoPlayNextEpisodeEnabled) {
        SettingsToggleRow(
            title = stringResource(R.string.still_watching_setting_title),
            subtitle = stringResource(R.string.still_watching_setting_sub),
            checked = settings.stillWatchingEnabled,
            onToggle = { onUpdate { setStillWatchingEnabled(!settings.stillWatchingEnabled) } }
        )

        if (settings.stillWatchingEnabled) {
            SliderSettingsItem(
                title = stringResource(R.string.still_watching_threshold_title),
                subtitle = stringResource(R.string.still_watching_threshold_sub),
                value = settings.stillWatchingEpisodeThreshold,
                valueText = "${settings.stillWatchingEpisodeThreshold}",
                minValue = 2,
                maxValue = 6,
                step = 1,
                onValueChange = { threshold -> onUpdate { setStillWatchingEpisodeThreshold(threshold) } }
            )
        }
    }

    SettingsToggleRow(
        title = stringResource(R.string.autoplay_prefer_binge_group),
        subtitle = stringResource(R.string.autoplay_prefer_binge_group_sub),
        checked = settings.streamAutoPlayPreferBingeGroupForNextEpisode,
        onToggle = {
            onUpdate {
                setStreamAutoPlayPreferBingeGroupForNextEpisode(!settings.streamAutoPlayPreferBingeGroupForNextEpisode)
            }
        }
    )

    if (settings.streamAutoPlayPreferBingeGroupForNextEpisode) {
        SettingsToggleRow(
            title = stringResource(R.string.autoplay_reuse_binge_group),
            subtitle = stringResource(R.string.autoplay_reuse_binge_group_sub),
            checked = settings.streamAutoPlayReuseBingeGroup,
            onToggle = { onUpdate { setStreamAutoPlayReuseBingeGroup(!settings.streamAutoPlayReuseBingeGroup) } }
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.autoplay_post_play_recommendations),
        subtitle = stringResource(R.string.autoplay_post_play_recommendations_sub),
        checked = settings.postPlayRecommendationsEnabled,
        onToggle = { onUpdate { setPostPlayRecommendationsEnabled(!settings.postPlayRecommendationsEnabled) } }
    )

    if (settings.postPlayRecommendationsEnabled) {
        SliderSettingsItem(
            title = stringResource(R.string.autoplay_post_play_movie_threshold),
            subtitle = stringResource(R.string.autoplay_post_play_movie_threshold_sub),
            value = settings.postPlayMovieThresholdPercent,
            valueText = "${settings.postPlayMovieThresholdPercent}%",
            minValue = PlayerSettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
            maxValue = PlayerSettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
            step = 1,
            onValueChange = { percent -> onUpdate { setPostPlayMovieThresholdPercent(percent) } }
        )
    }
}

@Composable
private fun selectionSummary(selectedCount: Int, allLabel: String): String =
    if (selectedCount == 0) allLabel else stringResource(R.string.autoplay_selected_count, selectedCount)

private fun formatHalfStepValue(value: Float): String {
    return if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@Composable
internal fun AutoPlaySettingsDialogs(
    dialog: PlaybackDialog?,
    settings: PlayerSettings,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    when (dialog) {
        PlaybackDialog.STREAM_AUTO_PLAY_MODE -> StreamAutoPlayModeDialog(
            selectedMode = settings.streamAutoPlayMode,
            onModeSelected = { mode ->
                onUpdate { setStreamAutoPlayMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.STREAM_AUTO_PLAY_SOURCE -> StreamAutoPlaySourceDialog(
            selectedSource = settings.streamAutoPlaySource,
            onSourceSelected = { source ->
                onUpdate { setStreamAutoPlaySource(source) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.STREAM_REGEX -> StreamRegexDialog(
            initialRegex = settings.streamAutoPlayRegex,
            onSave = { regex ->
                onUpdate { setStreamAutoPlayRegex(regex) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.NEXT_EPISODE_THRESHOLD_MODE -> NextEpisodeThresholdModeDialog(
            selectedMode = settings.nextEpisodeThresholdMode,
            onModeSelected = { mode ->
                onUpdate { setNextEpisodeThresholdMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.STREAM_AUTO_PLAY_ADDONS -> StreamAutoPlayProviderSelectionDialog(
            title = stringResource(R.string.autoplay_allowed_addons),
            allLabel = stringResource(R.string.autoplay_all_addons),
            items = installedAddonNames,
            selectedItems = settings.streamAutoPlaySelectedAddons,
            onSelectionSaved = { selected -> onUpdate { setStreamAutoPlaySelectedAddons(selected) } },
            onDismiss = onDismiss
        )
        PlaybackDialog.STREAM_AUTO_PLAY_PLUGINS -> StreamAutoPlayProviderSelectionDialog(
            title = stringResource(R.string.autoplay_allowed_plugins),
            allLabel = stringResource(R.string.autoplay_all_plugins),
            items = enabledPluginNames,
            selectedItems = settings.streamAutoPlaySelectedPlugins,
            onSelectionSaved = { selected -> onUpdate { setStreamAutoPlaySelectedPlugins(selected) } },
            onDismiss = onDismiss
        )
        PlaybackDialog.REUSE_LAST_LINK_CACHE -> StreamReuseLastLinkCacheDurationDialog(
            selectedHours = settings.streamReuseLastLinkCacheHours,
            onDurationSelected = { hours ->
                onUpdate { setStreamReuseLastLinkCacheHours(hours) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}

@Composable
private fun NextEpisodeThresholdModeDialog(
    selectedMode: NextEpisodeThresholdMode,
    onModeSelected: (NextEpisodeThresholdMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            NextEpisodeThresholdMode.PERCENTAGE,
            stringResource(R.string.autoplay_threshold_pct),
            stringResource(R.string.autoplay_threshold_pct_desc)
        ),
        SettingsPickerOption(
            NextEpisodeThresholdMode.MINUTES_BEFORE_END,
            stringResource(R.string.autoplay_threshold_min),
            stringResource(R.string.autoplay_threshold_min_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.autoplay_threshold_mode),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 520.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun formatReuseCacheDuration(hours: Int): String {
    return when {
        hours < 24 -> stringResource(
            if (hours == 1) R.string.cache_duration_hour_one else R.string.cache_duration_hour_other,
            hours
        )
        hours % 24 == 0 -> {
            val days = hours / 24
            stringResource(
                if (days == 1) R.string.cache_duration_day_one else R.string.cache_duration_day_other,
                days
            )
        }
        else -> {
            val days = hours / 24
            val remainingHours = hours % 24
            stringResource(R.string.cache_duration_days_hours, days, remainingHours)
        }
    }
}

@Composable
private fun StreamAutoPlayModeDialog(
    selectedMode: StreamAutoPlayMode,
    onModeSelected: (StreamAutoPlayMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(StreamAutoPlayMode.MANUAL, stringResource(R.string.autoplay_mode_manual), stringResource(R.string.autoplay_mode_manual_desc)),
        SettingsPickerOption(StreamAutoPlayMode.FIRST_STREAM, stringResource(R.string.autoplay_mode_first), stringResource(R.string.autoplay_mode_first_desc)),
        SettingsPickerOption(StreamAutoPlayMode.REGEX_MATCH, stringResource(R.string.autoplay_mode_regex), stringResource(R.string.autoplay_mode_regex_desc))
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.autoplay_stream_selection),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun StreamReuseLastLinkCacheDurationDialog(
    selectedHours: Int,
    onDurationSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        1,
        2,
        3,
        6,
        12,
        24,
        48,
        72,
        168
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.autoplay_last_link_cache),
        options = options.map { hours ->
            SettingsPickerOption(hours, formatReuseCacheDuration(hours))
        },
        selectedValue = selectedHours,
        onOptionSelected = onDurationSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun StreamAutoPlaySourceDialog(
    selectedSource: StreamAutoPlaySource,
    onSourceSelected: (StreamAutoPlaySource) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            StreamAutoPlaySource.ALL_SOURCES,
            stringResource(R.string.autoplay_scope_all),
            stringResource(R.string.autoplay_scope_all_desc)
        ),
        SettingsPickerOption(
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            stringResource(R.string.autoplay_scope_addons),
            stringResource(R.string.autoplay_scope_addons_desc)
        ),
        SettingsPickerOption(
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            stringResource(R.string.autoplay_scope_plugins),
            stringResource(R.string.autoplay_scope_plugins_desc)
        )
    ).filter { option ->
        AppFeaturePolicy.pluginsEnabled || option.value != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY
    }

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.autoplay_scope),
        options = options,
        selectedValue = selectedSource,
        onOptionSelected = onSourceSelected,
        onDismiss = onDismiss,
        width = 520.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun StreamAutoPlayProviderSelectionDialog(
    title: String,
    allLabel: String,
    items: List<String>,
    selectedItems: Set<String>,
    onSelectionSaved: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(selectedItems, items) {
        mutableStateOf(selectedItems.intersect(items.toSet()))
    }
    val focusRequester = remember { FocusRequester() }
    val focusedItem = remember(selectedItems, items) {
        items.firstOrNull { it in selectedItems }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = {
            onSelectionSaved(selected)
            onDismiss()
        },
        title = title,
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Card(
                onClick = { selected = emptySet() },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusedItem == null) Modifier.focusRequester(focusRequester) else Modifier),
                colors = CardDefaults.colors(
                    containerColor = if (selected.isEmpty()) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                scale = CardDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = allLabel,
                        color = if (selected.isEmpty()) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected.isEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = NuvioTheme.colors.Primary,
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.autoplay_no_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = NuvioTheme.spacing.xs)
                ) {
                    items(
                        items = items,
                        key = { it }
                    ) { item ->
                        val isSelected = item in selected
                        Card(
                            onClick = {
                                selected = if (isSelected) {
                                    selected - item
                                } else {
                                    selected + item
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (item == focusedItem) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item,
                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.cd_selected),
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.height(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRegexDialog(
    initialRegex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var regex by remember(initialRegex) { mutableStateOf(initialRegex) }
    var regexError by remember { mutableStateOf<String?>(null) }
    val strInvalidRegex = stringResource(R.string.autoplay_invalid_regex)
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val presetAny1080p = stringResource(R.string.autoplay_regex_preset_any_1080p_plus)
    val preset4kRemux = stringResource(R.string.autoplay_regex_preset_4k_remux)
    val preset1080pStandard = stringResource(R.string.autoplay_regex_preset_1080p_standard)
    val preset720pSmaller = stringResource(R.string.autoplay_regex_preset_720p_smaller)
    val presetWebSources = stringResource(R.string.autoplay_regex_preset_web_sources)
    val presetBlurayQuality = stringResource(R.string.autoplay_regex_preset_bluray_quality)
    val presetHevcX265 = stringResource(R.string.autoplay_regex_preset_hevc_x265)
    val presetAvcX264 = stringResource(R.string.autoplay_regex_preset_avc_x264)
    val presetHdrDv = stringResource(R.string.autoplay_regex_preset_hdr_dolby_vision)
    val presetDolbyAtmosDts = stringResource(R.string.autoplay_regex_preset_dolby_atmos_dts)
    val presetEnglish = stringResource(R.string.autoplay_regex_preset_english)
    val presetNoCamTs = stringResource(R.string.autoplay_regex_preset_no_cam_ts)
    val presetNoRemuxHdr = stringResource(R.string.autoplay_regex_preset_no_remux_hdr)
    val presets = remember(
        presetAny1080p, preset4kRemux, preset1080pStandard, preset720pSmaller,
        presetWebSources, presetBlurayQuality, presetHevcX265, presetAvcX264,
        presetHdrDv, presetDolbyAtmosDts, presetEnglish, presetNoCamTs, presetNoRemuxHdr
    ) {
        listOf(
            presetAny1080p to "(2160p|4k|1080p)",
            preset4kRemux to "(2160p|4k|remux)",
            preset1080pStandard to "(1080p|full\\s*hd)",
            preset720pSmaller to "(720p|webrip|web-dl)",
            presetWebSources to "(web[-\\s]?dl|webrip)",
            presetBlurayQuality to "(bluray|b[dr]rip|remux)",
            presetHevcX265 to "(hevc|x265|h\\.265)",
            presetAvcX264 to "(x264|h\\.264|avc)",
            presetHdrDv to "(hdr|hdr10\\+?|dv|dolby\\s*vision)",
            presetDolbyAtmosDts to "(atmos|truehd|dts[-\\s]?hd|dtsx?)",
            presetEnglish to "(\\beng\\b|english)",
            presetNoCamTs to "^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$",
            presetNoRemuxHdr to "(?is)^(?!.*\\b(hdr|hdr10|dv|dolby|vision|hevc|remux|2160p)\\b).+$"
        )
    }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.autoplay_regex_title),
        subtitle = stringResource(R.string.autoplay_regex_matches),
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.autoplay_regex_presets),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextSecondary
                )

                val firstPresetFocusRequester = remember { FocusRequester() }
                LazyRow(
                    modifier = Modifier.settingsOptionRow(firstPresetFocusRequester),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    itemsIndexed(
                        items = presets,
                        key = { _, preset -> preset.first }
                    ) { presetIndex, (label, pattern) ->
                        var isFocused by remember { mutableStateOf(false) }
                        Card(
                            onClick = {
                                regex = pattern
                                regexError = null
                            },
                            modifier = Modifier
                                .onFocusChanged { isFocused = it.isFocused }
                                .then(
                                    if (presetIndex == 0) {
                                        Modifier.focusRequester(firstPresetFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            colors = CardDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundElevated,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                                )
                            ),
                            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                            )
                        }
                    }
                }

                Card(
                    onClick = { inputFocusRequester.requestFocus() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ),
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        )
                    ),
                    shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1f)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                        BasicTextField(
                            value = regex,
                            onValueChange = {
                                regex = it
                                regexError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester)
                                .onKeyEvent { keyEvent ->
                                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                        keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                                },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                            decorationBox = { innerTextField ->
                                if (regex.isBlank()) {
                                    Text(
                                        text = "4K|2160p|Remux",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioTheme.colors.TextTertiary
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                if (regexError != null) {
                    Text(
                        text = regexError ?: "",
                        color = NuvioTheme.colors.Error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Button(
                        onClick = {
                            regex = ""
                            regexError = null
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    ) {
                        Text(stringResource(R.string.action_none))
                    }
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Button(
                        onClick = {
                            val value = regex.trim()
                            if (value.isNotEmpty()) {
                                val valid = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
                                if (!valid) {
                                    regexError = strInvalidRegex
                                    return@Button
                                }
                            }
                            onSave(value)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

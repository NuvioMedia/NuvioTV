@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors

private enum class PlaybackSection {
    GENERAL,
    STREAM_SELECTION,
    AUDIO_TRAILER,
    SUBTITLES
}

private data class PlaybackGeneralUi(
    val isExternalPlayer: Boolean,
    val frameRateMatchingLabel: String
)

private data class PlaybackStreamSelectionUi(
    val playerPreferenceLabel: String
)

private fun frameRateMatchingModeLabel(mode: FrameRateMatchingMode, off: String, onStart: String, onStartStop: String): String {
    return when (mode) {
        FrameRateMatchingMode.OFF -> off
        FrameRateMatchingMode.START -> onStart
        FrameRateMatchingMode.START_STOP -> onStartStop
    }
}

@Composable
internal fun PlaybackSettingsSections(
    initialFocusRequester: FocusRequester? = null,
    playerSettings: PlayerSettings,
    trailerSettings: TrailerSettings,
    onShowPlayerPreferenceDialog: () -> Unit,
    onShowAudioLanguageDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onShowLanguageDialog: () -> Unit,
    onShowSecondaryLanguageDialog: () -> Unit,
    onShowSubtitleOrganizationDialog: () -> Unit,
    onShowTextColorDialog: () -> Unit,
    onShowBackgroundColorDialog: () -> Unit,
    onShowOutlineColorDialog: () -> Unit,
    onShowStreamAutoPlayModeDialog: () -> Unit,
    onShowStreamAutoPlaySourceDialog: () -> Unit,
    onShowStreamAutoPlayAddonSelectionDialog: () -> Unit,
    onShowStreamAutoPlayPluginSelectionDialog: () -> Unit,
    onShowStreamRegexDialog: () -> Unit,
    onShowNextEpisodeThresholdModeDialog: () -> Unit,
    onShowReuseLastLinkCacheDialog: () -> Unit,
    onSetStreamAutoPlayNextEpisodeEnabled: (Boolean) -> Unit,
    onSetStreamAutoPlayPreferBingeGroupForNextEpisode: (Boolean) -> Unit,
    onSetNextEpisodeThresholdPercent: (Float) -> Unit,
    onSetNextEpisodeThresholdMinutesBeforeEnd: (Float) -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onSetLoadingOverlayEnabled: (Boolean) -> Unit,
    onSetPauseOverlayEnabled: (Boolean) -> Unit,
    onSetOsdClockEnabled: (Boolean) -> Unit,
    onSetSkipIntroEnabled: (Boolean) -> Unit,
    onSetFrameRateMatchingMode: (FrameRateMatchingMode) -> Unit,
    onSetTrailerEnabled: (Boolean) -> Unit,
    onSetTrailerDelaySeconds: (Int) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetMapDV7ToHevc: (Boolean) -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (com.nuvio.tv.data.local.LibassRenderType) -> Unit
) {
    var generalExpanded by rememberSaveable { mutableStateOf(false) }
    var afrExpanded by rememberSaveable { mutableStateOf(false) }
    var streamExpanded by rememberSaveable { mutableStateOf(false) }
    var audioTrailerExpanded by rememberSaveable { mutableStateOf(false) }
    var subtitlesExpanded by rememberSaveable { mutableStateOf(false) }

    val defaultGeneralHeaderFocus = remember { FocusRequester() }
    val afrHeaderFocus = remember { FocusRequester() }
    val streamHeaderFocus = remember { FocusRequester() }
    val audioTrailerHeaderFocus = remember { FocusRequester() }
    val subtitlesHeaderFocus = remember { FocusRequester() }
    val generalHeaderFocus = initialFocusRequester ?: defaultGeneralHeaderFocus

    var focusedSection by remember { mutableStateOf<PlaybackSection?>(null) }

    val strAfrOff = stringResource(R.string.playback_afr_off)
    val strAfrOnStart = stringResource(R.string.playback_afr_on_start)
    val strAfrOnStartStop = stringResource(R.string.playback_afr_on_start_stop)
    val strSectionGeneral = stringResource(R.string.playback_section_general)
    val strSectionGeneralDesc = stringResource(R.string.playback_section_general_desc)
    val strSectionPlayer = stringResource(R.string.playback_section_player)
    val strSectionPlayerDesc = stringResource(R.string.playback_section_player_desc)
    val strSectionAudio = stringResource(R.string.playback_section_audio)
    val strSectionAudioDesc = stringResource(R.string.playback_section_audio_desc)
    val strSectionSubtitles = stringResource(R.string.playback_section_subtitles)
    val strSectionSubtitlesDesc = stringResource(R.string.playback_section_subtitles_desc)
    val generalUi = PlaybackGeneralUi(
        isExternalPlayer = playerSettings.playerPreference == PlayerPreference.EXTERNAL,
        frameRateMatchingLabel = frameRateMatchingModeLabel(
            mode = playerSettings.frameRateMatchingMode,
            off = strAfrOff,
            onStart = strAfrOnStart,
            onStartStop = strAfrOnStartStop
        )
    )
    val streamSelectionUi = PlaybackStreamSelectionUi(
        playerPreferenceLabel = when (playerSettings.playerPreference) {
            PlayerPreference.INTERNAL -> stringResource(R.string.playback_player_internal)
            PlayerPreference.EXTERNAL -> stringResource(R.string.playback_player_external)
            PlayerPreference.ASK_EVERY_TIME -> stringResource(R.string.playback_player_ask)
        }
    )

    LaunchedEffect(generalExpanded, focusedSection) {
        if (!generalExpanded && focusedSection == PlaybackSection.GENERAL) {
            generalHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(streamExpanded, focusedSection) {
        if (!streamExpanded && focusedSection == PlaybackSection.STREAM_SELECTION) {
            streamHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(audioTrailerExpanded, focusedSection) {
        if (!audioTrailerExpanded && focusedSection == PlaybackSection.AUDIO_TRAILER) {
            audioTrailerHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(subtitlesExpanded, focusedSection) {
        if (!subtitlesExpanded && focusedSection == PlaybackSection.SUBTITLES) {
            subtitlesHeaderFocus.requestFocus()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        playbackCollapsibleSection(
            keyPrefix = "general",
            title = strSectionGeneral,
            description = strSectionGeneralDesc,
            expanded = generalExpanded,
            onToggle = { generalExpanded = !generalExpanded },
            focusRequester = generalHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.GENERAL }
        ) {
            item(key = "general_loading_overlay") {
                ToggleSettingsItem(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.playback_loading_overlay),
                    subtitle = stringResource(R.string.playback_loading_overlay_sub),
                    isChecked = playerSettings.loadingOverlayEnabled,
                    onCheckedChange = onSetLoadingOverlayEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer
                )
            }

            item(key = "general_pause_overlay") {
                ToggleSettingsItem(
                    icon = Icons.Default.PauseCircle,
                    title = stringResource(R.string.playback_pause_overlay),
                    subtitle = stringResource(R.string.playback_pause_overlay_sub),
                    isChecked = playerSettings.pauseOverlayEnabled,
                    onCheckedChange = onSetPauseOverlayEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer
                )
            }

            item(key = "general_osd_clock") {
                ToggleSettingsItem(
                    icon = Icons.Default.Timer,
                    title = stringResource(R.string.playback_osd_clock),
                    subtitle = stringResource(R.string.playback_show_clock_sub),
                    isChecked = playerSettings.osdClockEnabled,
                    onCheckedChange = onSetOsdClockEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer
                )
            }

            item(key = "general_skip_intro") {
                ToggleSettingsItem(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.playback_skip_intro),
                    subtitle = stringResource(R.string.playback_skip_intro_sub),
                    isChecked = playerSettings.skipIntroEnabled,
                    onCheckedChange = onSetSkipIntroEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer
                )
            }

            item(key = "general_afr_header") {
                PlaybackSectionHeader(
                    title = stringResource(R.string.playback_auto_frame_rate),
                    description = generalUi.frameRateMatchingLabel,
                    expanded = afrExpanded,
                    onToggle = { afrExpanded = !afrExpanded },
                    focusRequester = afrHeaderFocus,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer
                )
            }

            if (afrExpanded) {
                item(key = "general_afr_options") {
                    FrameRateMatchingModeOptions(
                        selectedMode = playerSettings.frameRateMatchingMode,
                        onSelect = onSetFrameRateMatchingMode,
                        onFocused = { focusedSection = PlaybackSection.GENERAL },
                        enabled = !generalUi.isExternalPlayer
                    )
                }
            }
        }

        playbackCollapsibleSection(
            keyPrefix = "stream_selection",
            title = strSectionPlayer,
            description = strSectionPlayerDesc,
            expanded = streamExpanded,
            onToggle = { streamExpanded = !streamExpanded },
            focusRequester = streamHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
        ) {
            item(key = "stream_player_preference") {
                NavigationSettingsItem(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.playback_player),
                    subtitle = streamSelectionUi.playerPreferenceLabel,
                    onClick = onShowPlayerPreferenceDialog,
                    onFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
                )
            }

            autoPlaySettingsItems(
                playerSettings = playerSettings,
                onShowModeDialog = onShowStreamAutoPlayModeDialog,
                onShowSourceDialog = onShowStreamAutoPlaySourceDialog,
                onShowAddonSelectionDialog = onShowStreamAutoPlayAddonSelectionDialog,
                onShowPluginSelectionDialog = onShowStreamAutoPlayPluginSelectionDialog,
                onShowRegexDialog = onShowStreamRegexDialog,
                onShowNextEpisodeThresholdModeDialog = onShowNextEpisodeThresholdModeDialog,
                onShowReuseLastLinkCacheDialog = onShowReuseLastLinkCacheDialog,
                onSetStreamAutoPlayNextEpisodeEnabled = onSetStreamAutoPlayNextEpisodeEnabled,
                onSetStreamAutoPlayPreferBingeGroupForNextEpisode = onSetStreamAutoPlayPreferBingeGroupForNextEpisode,
                onSetNextEpisodeThresholdPercent = onSetNextEpisodeThresholdPercent,
                onSetNextEpisodeThresholdMinutesBeforeEnd = onSetNextEpisodeThresholdMinutesBeforeEnd,
                onSetReuseLastLinkEnabled = onSetReuseLastLinkEnabled,
                onItemFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
            )
        }

        playbackCollapsibleSection(
            keyPrefix = "audio_trailer",
            title = strSectionAudio,
            description = strSectionAudioDesc,
            expanded = audioTrailerExpanded,
            onToggle = { audioTrailerExpanded = !audioTrailerExpanded },
            focusRequester = audioTrailerHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.AUDIO_TRAILER }
        ) {
            trailerAndAudioSettingsItems(
                playerSettings = playerSettings,
                trailerSettings = trailerSettings,
                onShowAudioLanguageDialog = onShowAudioLanguageDialog,
                onShowDecoderPriorityDialog = onShowDecoderPriorityDialog,
                onSetTrailerEnabled = onSetTrailerEnabled,
                onSetTrailerDelaySeconds = onSetTrailerDelaySeconds,
                onSetSkipSilence = onSetSkipSilence,
                onSetTunnelingEnabled = onSetTunnelingEnabled,
                onSetMapDV7ToHevc = onSetMapDV7ToHevc,
                onItemFocused = { focusedSection = PlaybackSection.AUDIO_TRAILER },
                enabled = !generalUi.isExternalPlayer
            )
        }

        playbackCollapsibleSection(
            keyPrefix = "subtitles",
            title = strSectionSubtitles,
            description = strSectionSubtitlesDesc,
            expanded = subtitlesExpanded,
            onToggle = { subtitlesExpanded = !subtitlesExpanded },
            focusRequester = subtitlesHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.SUBTITLES }
        ) {
            subtitleSettingsItems(
                playerSettings = playerSettings,
                onShowLanguageDialog = onShowLanguageDialog,
                onShowSecondaryLanguageDialog = onShowSecondaryLanguageDialog,
                onShowSubtitleOrganizationDialog = onShowSubtitleOrganizationDialog,
                onShowTextColorDialog = onShowTextColorDialog,
                onShowBackgroundColorDialog = onShowBackgroundColorDialog,
                onShowOutlineColorDialog = onShowOutlineColorDialog,
                onSetSubtitleSize = onSetSubtitleSize,
                onSetSubtitleVerticalOffset = onSetSubtitleVerticalOffset,
                onSetSubtitleBold = onSetSubtitleBold,
                onSetSubtitleOutlineEnabled = onSetSubtitleOutlineEnabled,
                onSetUseLibass = onSetUseLibass,
                onSetLibassRenderType = onSetLibassRenderType,
                onItemFocused = { focusedSection = PlaybackSection.SUBTITLES },
                enabled = !generalUi.isExternalPlayer
            )
        }
    }
}

private fun LazyListScope.playbackCollapsibleSection(
    keyPrefix: String,
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onHeaderFocused: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    item(key = "${keyPrefix}_header") {
        PlaybackSectionHeader(
            title = title,
            description = description,
            expanded = expanded,
            onToggle = onToggle,
            focusRequester = focusRequester,
            onFocused = onHeaderFocused
        )
    }

    if (expanded) {
        content()
        item(key = "${keyPrefix}_end_divider") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(1.dp)
                    .background(NuvioColors.Border)
            )
        }
    }
}

@Composable
private fun PlaybackSectionHeader(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    enabled: Boolean = true
) {
    SettingsActionRow(
        title = title,
        subtitle = description,
        value = if (expanded) stringResource(R.string.playback_afr_open) else stringResource(R.string.playback_afr_closed),
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        onFocused = onFocused,
        enabled = enabled,
        trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight
    )
}

@Composable
private fun FrameRateMatchingModeOptions(
    selectedMode: FrameRateMatchingMode,
    onSelect: (FrameRateMatchingMode) -> Unit,
    onFocused: () -> Unit,
    enabled: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_off),
            subtitle = stringResource(R.string.playback_afr_off_sub),
            isSelected = selectedMode == FrameRateMatchingMode.OFF,
            onClick = { onSelect(FrameRateMatchingMode.OFF) },
            onFocused = onFocused,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(8.dp))

        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_on_start),
            subtitle = stringResource(R.string.playback_afr_on_start_sub),
            isSelected = selectedMode == FrameRateMatchingMode.START,
            onClick = { onSelect(FrameRateMatchingMode.START) },
            onFocused = onFocused,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(8.dp))

        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_on_start_stop),
            subtitle = stringResource(R.string.playback_afr_on_start_stop_sub),
            isSelected = selectedMode == FrameRateMatchingMode.START_STOP,
            onClick = { onSelect(FrameRateMatchingMode.START_STOP) },
            onFocused = onFocused,
            enabled = enabled
        )
    }
}

@Composable
internal fun PlaybackSettingsDialogsHost(
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    showPlayerPreferenceDialog: Boolean,
    showLanguageDialog: Boolean,
    showSecondaryLanguageDialog: Boolean,
    showSubtitleOrganizationDialog: Boolean,
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    showAudioLanguageDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    showStreamAutoPlayModeDialog: Boolean,
    showStreamAutoPlaySourceDialog: Boolean,
    showStreamAutoPlayAddonSelectionDialog: Boolean,
    showStreamAutoPlayPluginSelectionDialog: Boolean,
    showStreamRegexDialog: Boolean,
    showNextEpisodeThresholdModeDialog: Boolean,
    showReuseLastLinkCacheDialog: Boolean,
    onSetPlayerPreference: (PlayerPreference) -> Unit,
    onDismissPlayerPreferenceDialog: () -> Unit,
    onSetSubtitlePreferredLanguage: (String?) -> Unit,
    onSetSubtitleSecondaryLanguage: (String?) -> Unit,
    onSetSubtitleOrganizationMode: (com.nuvio.tv.data.local.SubtitleOrganizationMode) -> Unit,
    onSetSubtitleTextColor: (Color) -> Unit,
    onSetSubtitleBackgroundColor: (Color) -> Unit,
    onSetSubtitleOutlineColor: (Color) -> Unit,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetStreamAutoPlayMode: (com.nuvio.tv.data.local.StreamAutoPlayMode) -> Unit,
    onSetStreamAutoPlaySource: (com.nuvio.tv.data.local.StreamAutoPlaySource) -> Unit,
    onSetNextEpisodeThresholdMode: (com.nuvio.tv.data.local.NextEpisodeThresholdMode) -> Unit,
    onSetStreamAutoPlayRegex: (String) -> Unit,
    onSetStreamAutoPlaySelectedAddons: (Set<String>) -> Unit,
    onSetStreamAutoPlaySelectedPlugins: (Set<String>) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleOrganizationDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit,
    onDismissStreamAutoPlayModeDialog: () -> Unit,
    onDismissStreamAutoPlaySourceDialog: () -> Unit,
    onDismissStreamRegexDialog: () -> Unit,
    onDismissStreamAutoPlayAddonSelectionDialog: () -> Unit,
    onDismissStreamAutoPlayPluginSelectionDialog: () -> Unit,
    onDismissNextEpisodeThresholdModeDialog: () -> Unit,
    onDismissReuseLastLinkCacheDialog: () -> Unit
) {
    if (showPlayerPreferenceDialog) {
        PlayerPreferenceDialog(
            currentPreference = playerSettings.playerPreference,
            onPreferenceSelected = { preference ->
                onSetPlayerPreference(preference)
                onDismissPlayerPreferenceDialog()
            },
            onDismiss = onDismissPlayerPreferenceDialog
        )
    }

    SubtitleSettingsDialogs(
        showLanguageDialog = showLanguageDialog,
        showSecondaryLanguageDialog = showSecondaryLanguageDialog,
        showSubtitleOrganizationDialog = showSubtitleOrganizationDialog,
        showTextColorDialog = showTextColorDialog,
        showBackgroundColorDialog = showBackgroundColorDialog,
        showOutlineColorDialog = showOutlineColorDialog,
        playerSettings = playerSettings,
        onSetPreferredLanguage = onSetSubtitlePreferredLanguage,
        onSetSecondaryLanguage = onSetSubtitleSecondaryLanguage,
        onSetSubtitleOrganizationMode = onSetSubtitleOrganizationMode,
        onSetTextColor = onSetSubtitleTextColor,
        onSetBackgroundColor = onSetSubtitleBackgroundColor,
        onSetOutlineColor = onSetSubtitleOutlineColor,
        onDismissLanguageDialog = onDismissLanguageDialog,
        onDismissSecondaryLanguageDialog = onDismissSecondaryLanguageDialog,
        onDismissSubtitleOrganizationDialog = onDismissSubtitleOrganizationDialog,
        onDismissTextColorDialog = onDismissTextColorDialog,
        onDismissBackgroundColorDialog = onDismissBackgroundColorDialog,
        onDismissOutlineColorDialog = onDismissOutlineColorDialog
    )

    AudioSettingsDialogs(
        showAudioLanguageDialog = showAudioLanguageDialog,
        showDecoderPriorityDialog = showDecoderPriorityDialog,
        selectedLanguage = playerSettings.preferredAudioLanguage,
        selectedPriority = playerSettings.decoderPriority,
        onSetPreferredAudioLanguage = onSetPreferredAudioLanguage,
        onSetDecoderPriority = onSetDecoderPriority,
        onDismissAudioLanguageDialog = onDismissAudioLanguageDialog,
        onDismissDecoderPriorityDialog = onDismissDecoderPriorityDialog
    )

    AutoPlaySettingsDialogs(
        showModeDialog = showStreamAutoPlayModeDialog,
        showSourceDialog = showStreamAutoPlaySourceDialog,
        showRegexDialog = showStreamRegexDialog,
        showAddonSelectionDialog = showStreamAutoPlayAddonSelectionDialog,
        showPluginSelectionDialog = showStreamAutoPlayPluginSelectionDialog,
        showNextEpisodeThresholdModeDialog = showNextEpisodeThresholdModeDialog,
        showReuseLastLinkCacheDialog = showReuseLastLinkCacheDialog,
        playerSettings = playerSettings,
        installedAddonNames = installedAddonNames,
        enabledPluginNames = enabledPluginNames,
        onSetMode = onSetStreamAutoPlayMode,
        onSetSource = onSetStreamAutoPlaySource,
        onSetNextEpisodeThresholdMode = onSetNextEpisodeThresholdMode,
        onSetRegex = onSetStreamAutoPlayRegex,
        onSetSelectedAddons = onSetStreamAutoPlaySelectedAddons,
        onSetSelectedPlugins = onSetStreamAutoPlaySelectedPlugins,
        onSetReuseLastLinkCacheHours = onSetReuseLastLinkCacheHours,
        onDismissModeDialog = onDismissStreamAutoPlayModeDialog,
        onDismissSourceDialog = onDismissStreamAutoPlaySourceDialog,
        onDismissRegexDialog = onDismissStreamRegexDialog,
        onDismissAddonSelectionDialog = onDismissStreamAutoPlayAddonSelectionDialog,
        onDismissPluginSelectionDialog = onDismissStreamAutoPlayPluginSelectionDialog,
        onDismissNextEpisodeThresholdModeDialog = onDismissNextEpisodeThresholdModeDialog,
        onDismissReuseLastLinkCacheDialog = onDismissReuseLastLinkCacheDialog
    )
}

@Composable
private fun PlayerPreferenceDialog(
    currentPreference: PlayerPreference,
    onPreferenceSelected: (PlayerPreference) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val options = listOf(
        Triple(PlayerPreference.INTERNAL, stringResource(R.string.playback_player_internal), "Use NuvioTV's built-in player"),
        Triple(PlayerPreference.EXTERNAL, stringResource(R.string.playback_player_external), stringResource(R.string.playback_player_external_desc)),
        Triple(PlayerPreference.ASK_EVERY_TIME, stringResource(R.string.playback_player_ask), stringResource(R.string.playback_player_ask_desc))
    )

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playback_player),
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
                    val (preference, title, description) = options[index]
                    val isSelected = preference == currentPreference

                    Card(
                        onClick = { onPreferenceSelected(preference) },
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

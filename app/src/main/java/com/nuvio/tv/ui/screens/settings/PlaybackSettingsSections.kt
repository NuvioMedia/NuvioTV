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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import com.nuvio.tv.ui.theme.NuvioColors

private enum class PlaybackSection {
    GENERAL,
    STREAM_SELECTION,
    AUDIO_TRAILER,
    SUBTITLES,
    BUFFER_NETWORK
}

private fun frameRateMatchingModeLabel(mode: FrameRateMatchingMode): String {
    return when (mode) {
        FrameRateMatchingMode.OFF -> "Off"
        FrameRateMatchingMode.START -> "On start"
        FrameRateMatchingMode.START_STOP -> "On start/stop"
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
    onSetLibassRenderType: (com.nuvio.tv.data.local.LibassRenderType) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeMb: (Int) -> Unit,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onResetBufferSettingsToDefaults: () -> Unit,
    onResetNetworkSettingsToDefaults: () -> Unit,
) {
    var generalExpanded by rememberSaveable { mutableStateOf(false) }
    var afrExpanded by rememberSaveable { mutableStateOf(false) }
    var streamExpanded by rememberSaveable { mutableStateOf(false) }
    var audioTrailerExpanded by rememberSaveable { mutableStateOf(false) }
    var subtitlesExpanded by rememberSaveable { mutableStateOf(false) }
    var bufferAndNetworkExpanded by rememberSaveable { mutableStateOf(false) }

    val defaultGeneralHeaderFocus = remember { FocusRequester() }
    val afrHeaderFocus = remember { FocusRequester() }
    val streamHeaderFocus = remember { FocusRequester() }
    val audioTrailerHeaderFocus = remember { FocusRequester() }
    val subtitlesHeaderFocus = remember { FocusRequester() }
    val generalHeaderFocus = initialFocusRequester ?: defaultGeneralHeaderFocus
    val bufferAndNetworkHeaderFocus = remember { FocusRequester() }

    var focusedSection by remember { mutableStateOf<PlaybackSection?>(null) }

    val isExternalPlayer = playerSettings.playerPreference == PlayerPreference.EXTERNAL

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
            title = "General",
            description = "Core playback behavior.",
            expanded = generalExpanded,
            onToggle = { generalExpanded = !generalExpanded },
            focusRequester = generalHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.GENERAL }
        ) {
            item {
                ToggleSettingsItem(
                    icon = Icons.Default.Image,
                    title = "Loading Overlay",
                    subtitle = "Show loading screen until first video frame appears.",
                    isChecked = playerSettings.loadingOverlayEnabled,
                    onCheckedChange = onSetLoadingOverlayEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !isExternalPlayer
                )
            }

            item {
                ToggleSettingsItem(
                    icon = Icons.Default.PauseCircle,
                    title = "Pause Overlay",
                    subtitle = "Show details overlay after 5 seconds while paused.",
                    isChecked = playerSettings.pauseOverlayEnabled,
                    onCheckedChange = onSetPauseOverlayEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !isExternalPlayer
                )
            }

            item {
                ToggleSettingsItem(
                    icon = Icons.Default.Timer,
                    title = "OSD Clock",
                    subtitle = "Show current time and end time while controls are visible.",
                    isChecked = playerSettings.osdClockEnabled,
                    onCheckedChange = onSetOsdClockEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !isExternalPlayer
                )
            }

            item {
                ToggleSettingsItem(
                    icon = Icons.Default.History,
                    title = "Skip Intro",
                    subtitle = "Use introdb.app to detect intros and recaps.",
                    isChecked = playerSettings.skipIntroEnabled,
                    onCheckedChange = onSetSkipIntroEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !isExternalPlayer
                )
            }

            item {
                PlaybackSectionHeader(
                    title = "Auto Frame Rate",
                    description = frameRateMatchingModeLabel(playerSettings.frameRateMatchingMode),
                    expanded = afrExpanded,
                    onToggle = { afrExpanded = !afrExpanded },
                    focusRequester = afrHeaderFocus,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !isExternalPlayer
                )
            }

            if (afrExpanded) {
                item {
                    FrameRateMatchingModeOptions(
                        selectedMode = playerSettings.frameRateMatchingMode,
                        onSelect = onSetFrameRateMatchingMode,
                        onFocused = { focusedSection = PlaybackSection.GENERAL },
                        enabled = !isExternalPlayer
                    )
                }
            }
        }

        playbackCollapsibleSection(
            keyPrefix = "stream_selection",
            title = "Player & Stream Selection",
            description = "Player preference, auto-play, and source filtering.",
            expanded = streamExpanded,
            onToggle = { streamExpanded = !streamExpanded },
            focusRequester = streamHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
        ) {
            item {
                NavigationSettingsItem(
                    icon = Icons.Default.PlayArrow,
                    title = "Player",
                    subtitle = when (playerSettings.playerPreference) {
                        PlayerPreference.INTERNAL -> "Internal"
                        PlayerPreference.EXTERNAL -> "External"
                        PlayerPreference.ASK_EVERY_TIME -> "Ask every time"
                    },
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
                onSetNextEpisodeThresholdPercent = onSetNextEpisodeThresholdPercent,
                onSetNextEpisodeThresholdMinutesBeforeEnd = onSetNextEpisodeThresholdMinutesBeforeEnd,
                onSetReuseLastLinkEnabled = onSetReuseLastLinkEnabled,
                onItemFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
            )
        }

        playbackCollapsibleSection(
            keyPrefix = "audio_trailer",
            title = "Audio & Trailer",
            description = "Trailer behavior and audio controls.",
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
                enabled = !isExternalPlayer
            )
        }

        playbackCollapsibleSection(
            keyPrefix = "subtitles",
            title = "Subtitles",
            description = "Language, style, and render mode.",
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
                enabled = !isExternalPlayer
            )
        }

        playbackCollapsibleSection(
            keyPrefix = "buffer",
            title = "Buffer & Network",
            description = "How much content to keep in memory and how to fetch streams.",
            expanded = bufferAndNetworkExpanded,
            onToggle = { bufferAndNetworkExpanded = !bufferAndNetworkExpanded },
            focusRequester = bufferAndNetworkHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.BUFFER_NETWORK }
        ) {
            bufferAndNetworkSettingsItems(
                playerSettings = playerSettings,
                onSetBufferMinBufferMs = onSetBufferMinBufferMs,
                onSetBufferMaxBufferMs = onSetBufferMaxBufferMs,
                onSetBufferForPlaybackMs = onSetBufferForPlaybackMs,
                onSetBufferForPlaybackAfterRebufferMs = onSetBufferForPlaybackAfterRebufferMs,
                onSetBufferTargetSizeMb = onSetBufferTargetSizeMb,
                onSetBufferBackBufferDurationMs = onSetBufferBackBufferDurationMs,
                onResetToDefaults = onResetBufferSettingsToDefaults,
                onSetUseParallelConnections = onSetUseParallelConnections,
                onSetParallelConnectionCount = onSetParallelConnectionCount,
                onSetParallelChunkSizeMb = onSetParallelChunkSizeMb,
                onResetNetworkToDefaults = onResetNetworkSettingsToDefaults
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
        value = if (expanded) "Open" else "Closed",
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
            title = "Off",
            subtitle = "Don't change display refresh rate.",
            isSelected = selectedMode == FrameRateMatchingMode.OFF,
            onClick = { onSelect(FrameRateMatchingMode.OFF) },
            onFocused = onFocused,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(8.dp))

        RenderTypeSettingsItem(
            title = "On start",
            subtitle = "Switch when playback starts.",
            isSelected = selectedMode == FrameRateMatchingMode.START,
            onClick = { onSelect(FrameRateMatchingMode.START) },
            onFocused = onFocused,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(8.dp))

        RenderTypeSettingsItem(
            title = "On start/stop",
            subtitle = "Switch on start and restore on stop.",
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
        Triple(PlayerPreference.INTERNAL, "Internal", "Use NuvioTV's built-in player"),
        Triple(PlayerPreference.EXTERNAL, "External", "Always open streams in an external app"),
        Triple(PlayerPreference.ASK_EVERY_TIME, "Ask every time", "Choose the player each time")
    )

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Player",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(options.size) { index ->
                        val (preference, title, description) = options[index]
                        val isSelected = preference == currentPreference

                        Card(
                            onClick = { onPreferenceSelected(preference) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            ),
                            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
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
                                        contentDescription = "Selected",
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
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayCapabilities
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.components.NuvioDialog

private enum class PlaybackSection {
    GENERAL,
    STREAM_SELECTION,
    AUDIO_TRAILER,
    SUBTITLES,
    P2P,
    BUFFER_NETWORK,
    DIAGNOSTICS
}

private data class PlaybackGeneralUi(
    val isExternalPlayer: Boolean,
    val frameRateMatchingLabel: String
)

private data class PlaybackStreamSelectionUi(
    val playerPreferenceLabel: String,
    val internalEngineLabel: String
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
    onShowPlayerPreferenceDialog: () -> Unit,
    onShowInternalPlayerEngineDialog: () -> Unit,
    onShowAudioLanguageDialog: () -> Unit,
    onShowSecondaryAudioLanguageDialog: () -> Unit,
    onShowAudioOutputChannelsDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onShowMpvHardwareDecodeModeDialog: () -> Unit,
    onShowLanguageDialog: () -> Unit,
    onShowSecondaryLanguageDialog: () -> Unit,
    onShowSubtitleStartupModeDialog: () -> Unit,
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
    onSetStreamAutoPlayReuseBingeGroup: (Boolean) -> Unit,
    onSetAutoSwitchInternalPlayerOnError: (Boolean) -> Unit,
    onSetExternalPlayerForwardSubtitles: (Boolean) -> Unit,
    onSetNextEpisodeThresholdPercent: (Float) -> Unit,
    onSetNextEpisodeThresholdMinutesBeforeEnd: (Float) -> Unit,
    onSetStreamAutoPlayTimeoutSeconds: (Int) -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onSetStillWatchingEnabled: (Boolean) -> Unit,
    onSetStillWatchingEpisodeThreshold: (Int) -> Unit,
    onSetShowPlayerLoadingStatus: (Boolean) -> Unit,
    onSetLoadingOverlayEnabled: (Boolean) -> Unit,
    onSetPauseOverlayEnabled: (Boolean) -> Unit,
    onSetOsdClockEnabled: (Boolean) -> Unit,
    onSetSkipIntroEnabled: (Boolean) -> Unit,
    onSetParentalGuideEnabled: (Boolean) -> Unit,
    onSetAutoSkipSegmentTypeEnabled: (AutoSkipSegmentType, Boolean) -> Unit,
    onSetFrameRateMatchingMode: (FrameRateMatchingMode) -> Unit,
    onSetResolutionMatchingEnabled: (Boolean) -> Unit,
    onDisableAfrAndResolution: () -> Unit,
    onDisableAfrOnly: () -> Unit,
    onDisableResolutionOnly: () -> Unit,
    onSetDownmixEnabled: (Boolean) -> Unit,
    onSetMaintainOriginalAudioOnDownmix: (Boolean) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetRememberAudioDelayPerDevice: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetForceOpticalPassthrough: (Boolean) -> Unit,
    onShowDv7HandlingModeDialog: () -> Unit,
    onSetDv5ToDv81Enabled: (Boolean) -> Unit,
    onSetDv7ToDv81PreserveMappingEnabled: (Boolean) -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetUseForcedSubtitles: (Boolean) -> Unit,
    onSetSubtitleShowOnlyPreferredLanguages: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (LibassRenderType) -> Unit,
    p2pEnabled: Boolean = false,
    onSetP2pEnabled: (Boolean) -> Unit = {},
    hideTorrentStats: Boolean = false,
    onSetHideTorrentStats: (Boolean) -> Unit = {},
    onSetBufferEngineEnabled: (Boolean) -> Unit,
    onSetParallelNetworkEnabled: (Boolean) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeMb: (Int) -> Unit,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetAllowLargeTargetBuffer: (Boolean) -> Unit,
    onSetBufferBudgetManaged: (Boolean) -> Unit,
    onSetVodCacheEnabled: (Boolean) -> Unit,
    onSetVodCacheSizeMode: (VodCacheSizeMode) -> Unit,
    onSetVodCacheSizeMb: (Int) -> Unit,
    onResetBufferSettingsToDefaults: () -> Unit,
    onResetNetworkSettingsToDefaults: () -> Unit
) {
    var generalExpanded by rememberSaveable { mutableStateOf(false) }
    var afrExpanded by rememberSaveable { mutableStateOf(false) }
    var autoSkipExpanded by rememberSaveable { mutableStateOf(false) }
    var streamExpanded by rememberSaveable { mutableStateOf(false) }
    var audioTrailerExpanded by rememberSaveable { mutableStateOf(false) }
    var subtitlesExpanded by rememberSaveable { mutableStateOf(false) }
    var p2pExpanded by rememberSaveable { mutableStateOf(false) }
    var bufferAndNetworkExpanded by rememberSaveable { mutableStateOf(false) }

    val defaultGeneralHeaderFocus = remember { FocusRequester() }
    val afrHeaderFocus = remember { FocusRequester() }
    val autoSkipHeaderFocus = remember { FocusRequester() }
    val streamHeaderFocus = remember { FocusRequester() }
    val audioTrailerHeaderFocus = remember { FocusRequester() }
    val subtitlesHeaderFocus = remember { FocusRequester() }
    val p2pHeaderFocus = remember { FocusRequester() }
    val bufferAndNetworkHeaderFocus = remember { FocusRequester() }
    val generalHeaderFocus = initialFocusRequester ?: defaultGeneralHeaderFocus

    var focusedSection by remember { mutableStateOf<PlaybackSection?>(null) }

    val context = LocalContext.current
    val activity = remember(context) {
        var ctx: android.content.Context? = context
        while (ctx != null && ctx !is android.app.Activity) {
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        }
        ctx as? android.app.Activity
    }
    var displayCapabilities by remember { mutableStateOf(DisplayCapabilities.Snapshot.Unknown) }
    LaunchedEffect(activity, afrExpanded) {
        if (activity != null) {
            val snapshot = DisplayCapabilities.detect(activity)
            displayCapabilities = snapshot
            if (afrExpanded) {
                DisplayCapabilities.logSummary(snapshot)
            }
        } else {
            android.util.Log.w(
                "DisplayCapabilities",
                "Settings: could not resolve host Activity from LocalContext"
            )
        }
    }
    val showAfrWarning = playerSettings.frameRateMatchingMode != FrameRateMatchingMode.OFF ||
        (playerSettings.resolutionMatchingEnabled &&
            displayCapabilities.apiSupported &&
            !displayCapabilities.supportsResolutionSwitching)

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
    val strSectionBufferNetwork = stringResource(R.string.playback_section_buffer_network)
    val strSectionBufferNetworkDesc = stringResource(R.string.playback_section_buffer_network_desc)
    val strSectionP2p = stringResource(R.string.settings_p2p_title)
    val strSectionP2pDesc = stringResource(R.string.settings_p2p_subtitle)
    val strHideTorrentStats = stringResource(R.string.settings_p2p_hide_stats_title)
    val strHideTorrentStatsDesc = stringResource(R.string.settings_p2p_hide_stats_subtitle)
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
        },
        internalEngineLabel = when (playerSettings.internalPlayerEngine) {
            InternalPlayerEngine.EXOPLAYER -> stringResource(R.string.playback_engine_exoplayer)
            InternalPlayerEngine.MVP_PLAYER -> stringResource(R.string.playback_engine_mvplayer)
            InternalPlayerEngine.AUTO -> stringResource(R.string.playback_player_auto)
        }
    )

    LaunchedEffect(generalExpanded, focusedSection) {
        if (!generalExpanded && focusedSection == PlaybackSection.GENERAL) {
            generalHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(autoSkipExpanded, focusedSection) {
        if (!autoSkipExpanded && focusedSection == PlaybackSection.GENERAL) {
            autoSkipHeaderFocus.requestFocus()
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
    LaunchedEffect(p2pExpanded, focusedSection) {
        if (!p2pExpanded && focusedSection == PlaybackSection.P2P) {
            p2pHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(bufferAndNetworkExpanded, focusedSection) {
        if (!bufferAndNetworkExpanded && focusedSection == PlaybackSection.BUFFER_NETWORK) {
            bufferAndNetworkHeaderFocus.requestFocus()
        }
    }

    val playbackListState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = playbackListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
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

            item(key = "general_parental_guide") {
                ToggleSettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.playback_parental_guide),
                    subtitle = stringResource(R.string.playback_parental_guide_sub),
                    isChecked = playerSettings.parentalGuideEnabled,
                    onCheckedChange = onSetParentalGuideEnabled,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer
                )
            }

            item(key = "general_auto_skip_header") {
                PlaybackSectionHeader(
                    title = stringResource(R.string.playback_auto_skip_segments),
                    description = stringResource(R.string.playback_auto_skip_segments_sub),
                    expanded = autoSkipExpanded,
                    onToggle = { autoSkipExpanded = !autoSkipExpanded },
                    focusRequester = autoSkipHeaderFocus,
                    onFocused = { focusedSection = PlaybackSection.GENERAL },
                    enabled = !generalUi.isExternalPlayer && playerSettings.skipIntroEnabled
                )
            }

            if (autoSkipExpanded) {
                item(key = "general_auto_skip_intro") {
                    ToggleSettingsItem(
                        icon = Icons.Default.SkipNext,
                        title = stringResource(R.string.auto_skip_intro),
                        subtitle = stringResource(R.string.auto_skip_intro_sub),
                        isChecked = AutoSkipSegmentType.INTRO in playerSettings.autoSkipSegmentTypes,
                        onCheckedChange = {
                            onSetAutoSkipSegmentTypeEnabled(AutoSkipSegmentType.INTRO, it)
                        },
                        onFocused = { focusedSection = PlaybackSection.GENERAL },
                        enabled = !generalUi.isExternalPlayer && playerSettings.skipIntroEnabled
                    )
                }

                item(key = "general_auto_skip_recap") {
                    ToggleSettingsItem(
                        icon = Icons.Default.SkipNext,
                        title = stringResource(R.string.auto_skip_recap),
                        subtitle = stringResource(R.string.auto_skip_recap_sub),
                        isChecked = AutoSkipSegmentType.RECAP in playerSettings.autoSkipSegmentTypes,
                        onCheckedChange = {
                            onSetAutoSkipSegmentTypeEnabled(AutoSkipSegmentType.RECAP, it)
                        },
                        onFocused = { focusedSection = PlaybackSection.GENERAL },
                        enabled = !generalUi.isExternalPlayer && playerSettings.skipIntroEnabled
                    )
                }

                item(key = "general_auto_skip_outro") {
                    ToggleSettingsItem(
                        icon = Icons.Default.SkipNext,
                        title = stringResource(R.string.auto_skip_outro),
                        subtitle = stringResource(R.string.auto_skip_outro_sub),
                        isChecked = AutoSkipSegmentType.OUTRO in playerSettings.autoSkipSegmentTypes,
                        onCheckedChange = {
                            onSetAutoSkipSegmentTypeEnabled(AutoSkipSegmentType.OUTRO, it)
                        },
                        onFocused = { focusedSection = PlaybackSection.GENERAL },
                        enabled = !generalUi.isExternalPlayer && playerSettings.skipIntroEnabled
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

            if (playerSettings.playerPreference != PlayerPreference.INTERNAL) {
                item(key = "external_player_forward_subtitles") {
                    ToggleSettingsItem(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.playback_external_forward_subtitles),
                        subtitle = stringResource(R.string.playback_external_forward_subtitles_sub),
                        isChecked = playerSettings.externalPlayerForwardSubtitles,
                        onCheckedChange = onSetExternalPlayerForwardSubtitles,
                        onFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
                    )
                }
            }

            item(key = "stream_internal_player_engine") {
                NavigationSettingsItem(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.playback_internal_player_engine),
                    subtitle = streamSelectionUi.internalEngineLabel,
                    onClick = onShowInternalPlayerEngineDialog,
                    onFocused = { focusedSection = PlaybackSection.STREAM_SELECTION },
                    enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL
                )
            }

            item(key = "stream_auto_switch_internal_player_on_error") {
                ToggleSettingsItem(
                    icon = Icons.Default.SwapHoriz,
                    title = stringResource(R.string.playback_auto_switch_internal_player_on_error),
                    subtitle = stringResource(R.string.playback_auto_switch_internal_player_on_error_sub),
                    isChecked = playerSettings.autoSwitchInternalPlayerOnError,
                    onCheckedChange = onSetAutoSwitchInternalPlayerOnError,
                    onFocused = { focusedSection = PlaybackSection.STREAM_SELECTION },
                    enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL
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
                onSetStreamAutoPlayReuseBingeGroup = onSetStreamAutoPlayReuseBingeGroup,
                onSetNextEpisodeThresholdPercent = onSetNextEpisodeThresholdPercent,
                onSetNextEpisodeThresholdMinutesBeforeEnd = onSetNextEpisodeThresholdMinutesBeforeEnd,
                onSetStreamAutoPlayTimeoutSeconds = onSetStreamAutoPlayTimeoutSeconds,
                onSetReuseLastLinkEnabled = onSetReuseLastLinkEnabled,
                onSetStillWatchingEnabled = onSetStillWatchingEnabled,
                onSetStillWatchingEpisodeThreshold = onSetStillWatchingEpisodeThreshold,
                onItemFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
            )

            item(key = "stream_show_loading_status") {
                ToggleSettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.playback_show_loading_status),
                    subtitle = stringResource(R.string.playback_show_loading_status_sub),
                    isChecked = playerSettings.showPlayerLoadingStatus,
                    onCheckedChange = onSetShowPlayerLoadingStatus,
                    onFocused = { focusedSection = PlaybackSection.STREAM_SELECTION }
                )
            }
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
                onShowAudioLanguageDialog = onShowAudioLanguageDialog,
                onShowSecondaryAudioLanguageDialog = onShowSecondaryAudioLanguageDialog,
                onShowAudioOutputChannelsDialog = onShowAudioOutputChannelsDialog,
                onShowDecoderPriorityDialog = onShowDecoderPriorityDialog,
                onShowMpvHardwareDecodeModeDialog = onShowMpvHardwareDecodeModeDialog,
                onShowDv7HandlingModeDialog = onShowDv7HandlingModeDialog,
                onSetDownmixEnabled = onSetDownmixEnabled,
                onSetMaintainOriginalAudioOnDownmix = onSetMaintainOriginalAudioOnDownmix,
                onSetSkipSilence = onSetSkipSilence,
                onSetRememberAudioDelayPerDevice = onSetRememberAudioDelayPerDevice,
                onSetTunnelingEnabled = onSetTunnelingEnabled,
                onSetForceOpticalPassthrough = onSetForceOpticalPassthrough,
                onSetDv5ToDv81Enabled = onSetDv5ToDv81Enabled,
                onSetDv7ToDv81PreserveMappingEnabled = onSetDv7ToDv81PreserveMappingEnabled,
                onItemFocused = { focusedSection = PlaybackSection.AUDIO_TRAILER },
                enabled = !generalUi.isExternalPlayer,
                videoExtraItems = {
                    item(key = "general_afr_header") {
                        PlaybackSectionHeader(
                            title = stringResource(R.string.playback_auto_frame_rate),
                            description = generalUi.frameRateMatchingLabel,
                            expanded = afrExpanded,
                            onToggle = { afrExpanded = !afrExpanded },
                            focusRequester = afrHeaderFocus,
                            onFocused = { focusedSection = PlaybackSection.AUDIO_TRAILER },
                            enabled = !generalUi.isExternalPlayer,
                            showWarningIcon = showAfrWarning,
                            icon = Icons.Default.Speed
                        )
                    }

                    if (afrExpanded) {
                        item(key = "general_afr_capability_warning") {
                            AfrCapabilityWarningCard(
                                snapshot = displayCapabilities,
                                afrModeOn = playerSettings.frameRateMatchingMode != FrameRateMatchingMode.OFF,
                                resolutionMatchingOn = playerSettings.resolutionMatchingEnabled,
                                headerFocusRequester = afrHeaderFocus,
                                onDisableAll = onDisableAfrAndResolution,
                                onDisableAfrOnly = onDisableAfrOnly,
                                onDisableResolutionOnly = onDisableResolutionOnly,
                                onFocused = { focusedSection = PlaybackSection.AUDIO_TRAILER }
                            )
                        }
                        item(key = "general_afr_options") {
                            FrameRateMatchingModeOptions(
                                selectedMode = playerSettings.frameRateMatchingMode,
                                resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled,
                                resolutionSwitchingSupported = !displayCapabilities.apiSupported ||
                                    displayCapabilities.supportsResolutionSwitching,
                                onSelect = onSetFrameRateMatchingMode,
                                onSetResolutionMatchingEnabled = onSetResolutionMatchingEnabled,
                                onFocused = { focusedSection = PlaybackSection.AUDIO_TRAILER },
                                enabled = !generalUi.isExternalPlayer
                            )
                        }
                    }
                }
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
                onShowSubtitleStartupModeDialog = onShowSubtitleStartupModeDialog,
                onShowTextColorDialog = onShowTextColorDialog,
                onShowBackgroundColorDialog = onShowBackgroundColorDialog,
                onShowOutlineColorDialog = onShowOutlineColorDialog,
                onSetSubtitleSize = onSetSubtitleSize,
                onSetSubtitleVerticalOffset = onSetSubtitleVerticalOffset,
                onSetSubtitleBold = onSetSubtitleBold,
                onSetUseForcedSubtitles = onSetUseForcedSubtitles,
                onSetSubtitleShowOnlyPreferredLanguages = onSetSubtitleShowOnlyPreferredLanguages,
                onSetSubtitleOutlineEnabled = onSetSubtitleOutlineEnabled,
                onSetUseLibass = onSetUseLibass,
                onSetLibassRenderType = onSetLibassRenderType,
                onItemFocused = { focusedSection = PlaybackSection.SUBTITLES },
                enabled = !generalUi.isExternalPlayer,
                languageSelectionEnabled = !generalUi.isExternalPlayer || playerSettings.externalPlayerForwardSubtitles
            )
        }

        playbackCollapsibleSection(
            keyPrefix = "p2p",
            title = strSectionP2p,
            description = strSectionP2pDesc,
            expanded = p2pExpanded,
            onToggle = { p2pExpanded = !p2pExpanded },
            focusRequester = p2pHeaderFocus,
            onHeaderFocused = { focusedSection = PlaybackSection.P2P }
        ) {
            item(key = "p2p_enabled") {
                ToggleSettingsItem(
                    icon = Icons.Default.Info,
                    title = strSectionP2p,
                    subtitle = strSectionP2pDesc,
                    isChecked = p2pEnabled,
                    onCheckedChange = onSetP2pEnabled,
                    onFocused = { focusedSection = PlaybackSection.P2P }
                )
            }
            item(key = "p2p_hide_stats") {
                ToggleSettingsItem(
                    icon = Icons.Default.Info,
                    title = strHideTorrentStats,
                    subtitle = strHideTorrentStatsDesc,
                    isChecked = hideTorrentStats,
                    onCheckedChange = onSetHideTorrentStats,
                    onFocused = { focusedSection = PlaybackSection.P2P }
                )
            }
        }

        if (playerSettings.internalPlayerEngine == InternalPlayerEngine.EXOPLAYER ||
            playerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO) {
            playbackCollapsibleSection(
                keyPrefix = "buffer_network",
                title = strSectionBufferNetwork,
                description = strSectionBufferNetworkDesc,
                expanded = bufferAndNetworkExpanded,
                onToggle = { bufferAndNetworkExpanded = !bufferAndNetworkExpanded },
                focusRequester = bufferAndNetworkHeaderFocus,
                onHeaderFocused = { focusedSection = PlaybackSection.BUFFER_NETWORK }
            ) {
                bufferAndNetworkSettingsItems(
                    playerSettings = playerSettings,
                    onSetBufferEngineEnabled = onSetBufferEngineEnabled,
                    onSetParallelNetworkEnabled = onSetParallelNetworkEnabled,
                    onSetBufferMinBufferMs = onSetBufferMinBufferMs,
                    onSetBufferMaxBufferMs = onSetBufferMaxBufferMs,
                    onSetBufferForPlaybackMs = onSetBufferForPlaybackMs,
                    onSetBufferForPlaybackAfterRebufferMs = onSetBufferForPlaybackAfterRebufferMs,
                    onSetBufferTargetSizeMb = onSetBufferTargetSizeMb,
                    onSetBufferBackBufferDurationMs = onSetBufferBackBufferDurationMs,
                    onSetAllowLargeTargetBuffer = onSetAllowLargeTargetBuffer,
                    onSetBufferBudgetManaged = onSetBufferBudgetManaged,
                    onSetVodCacheEnabled = onSetVodCacheEnabled,
                    onSetVodCacheSizeMode = onSetVodCacheSizeMode,
                    onSetVodCacheSizeMb = onSetVodCacheSizeMb,
                    onResetToDefaults = onResetBufferSettingsToDefaults,
                    onSetUseParallelConnections = onSetUseParallelConnections,
                    onSetParallelConnectionCount = onSetParallelConnectionCount,
                    onSetParallelChunkSizeMb = onSetParallelChunkSizeMb,
                    onResetNetworkToDefaults = onResetNetworkSettingsToDefaults
                )
            }
        }

    }
        SettingsVerticalScrollIndicators(state = playbackListState)
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
                    .padding(horizontal = NuvioTheme.spacing.xs)
                    .height(NuvioTheme.spacing.hairline)
                    .background(NuvioTheme.colors.Border)
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
    enabled: Boolean = true,
    showWarningIcon: Boolean = false,
    icon: ImageVector? = null
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
        trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
        titleTrailingIcon = if (showWarningIcon) Icons.Default.Warning else null,
        titleTrailingIconTint = Color(0xFFFFB74D),
        leadingIcon = icon
    )
}

@Composable
private fun FrameRateMatchingModeOptions(
    selectedMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean,
    resolutionSwitchingSupported: Boolean,
    onSelect: (FrameRateMatchingMode) -> Unit,
    onSetResolutionMatchingEnabled: (Boolean) -> Unit,
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

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_on_start),
            subtitle = stringResource(R.string.playback_afr_on_start_sub),
            isSelected = selectedMode == FrameRateMatchingMode.START,
            onClick = { onSelect(FrameRateMatchingMode.START) },
            onFocused = onFocused,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_on_start_stop),
            subtitle = stringResource(R.string.playback_afr_on_start_stop_sub),
            isSelected = selectedMode == FrameRateMatchingMode.START_STOP,
            onClick = { onSelect(FrameRateMatchingMode.START_STOP) },
            onFocused = onFocused,
            enabled = enabled
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        ToggleSettingsItem(
            icon = Icons.Default.Image,
            title = stringResource(R.string.playback_resolution_matching),
            subtitle = stringResource(
                if (!resolutionSwitchingSupported) R.string.playback_resolution_matching_unsupported_sub
                else R.string.playback_resolution_matching_sub
            ),
            isChecked = resolutionMatchingEnabled,
            onCheckedChange = onSetResolutionMatchingEnabled,
            onFocused = onFocused,
            enabled = enabled,
            titleTrailingIcon = if (resolutionMatchingEnabled && !resolutionSwitchingSupported) Icons.Default.Warning else null,
            titleTrailingIconTint = Color(0xFFFFB74D)
        )
    }
}

@Composable
private fun AfrCapabilityWarningCard(
    snapshot: DisplayCapabilities.Snapshot,
    afrModeOn: Boolean,
    resolutionMatchingOn: Boolean,
    headerFocusRequester: FocusRequester,
    onDisableAll: () -> Unit,
    onDisableAfrOnly: () -> Unit,
    onDisableResolutionOnly: () -> Unit,
    onFocused: () -> Unit
) {
    if (!snapshot.apiSupported) return

    val afrProblem = afrModeOn && !snapshot.supportsFrameRateSwitching
    val resProblem = resolutionMatchingOn && !snapshot.supportsResolutionSwitching
    if (!afrProblem && !resProblem) return

    val bodyRes = when {
        afrProblem && resProblem -> R.string.playback_afr_capability_both_problem_body
        afrProblem -> R.string.playback_afr_capability_only_afr_unsupported_body
        else -> R.string.playback_afr_capability_only_res_unsupported_body
    }
    val buttonRes = when {
        afrProblem && resProblem -> R.string.playback_afr_capability_disable_both_button
        afrProblem -> R.string.playback_afr_capability_disable_button
        else -> R.string.playback_afr_capability_disable_resolution_button
    }
    val onDisable: () -> Unit = when {
        afrProblem && resProblem -> onDisableAll
        afrProblem -> onDisableAfrOnly
        else -> onDisableResolutionOnly
    }
    val warningTone = Color(0xFFFFB74D)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
            .background(NuvioTheme.colors.BackgroundCard)
            .border(
                width = NuvioTheme.spacing.hairline,
                color = warningTone.copy(alpha = 0.55f),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = warningTone,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.playback_afr_capability_unsupported_title),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        AfrCapabilityDisableButton(
            label = stringResource(buttonRes),
            onClick = {
                runCatching { headerFocusRequester.requestFocus() }
                onDisable()
            },
            onFocused = onFocused
        )
    }
}

@Composable
private fun AfrCapabilityDisableButton(
    label: String,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
            )
        }
    }
}

@Composable
internal fun PlaybackSettingsDialogsHost(
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    showPlayerPreferenceDialog: Boolean,
    showInternalPlayerEngineDialog: Boolean,
    showLanguageDialog: Boolean,
    showSecondaryLanguageDialog: Boolean,
    showSubtitleStartupModeDialog: Boolean,
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    showAudioLanguageDialog: Boolean,
    showSecondaryAudioLanguageDialog: Boolean,
    showAudioOutputChannelsDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    showMpvHardwareDecodeModeDialog: Boolean,
    showDv7HandlingModeDialog: Boolean,
    showStreamAutoPlayModeDialog: Boolean,
    showStreamAutoPlaySourceDialog: Boolean,
    showStreamAutoPlayAddonSelectionDialog: Boolean,
    showStreamAutoPlayPluginSelectionDialog: Boolean,
    showStreamRegexDialog: Boolean,
    showNextEpisodeThresholdModeDialog: Boolean,
    showReuseLastLinkCacheDialog: Boolean,
    onSetPlayerPreference: (PlayerPreference) -> Unit,
    onDismissPlayerPreferenceDialog: () -> Unit,
    onSetInternalPlayerEngine: (InternalPlayerEngine) -> Unit,
    onDismissInternalPlayerEngineDialog: () -> Unit,
    onSetSubtitlePreferredLanguage: (String?) -> Unit,
    onSetSubtitleSecondaryLanguage: (String?) -> Unit,
    onSetAddonSubtitleStartupMode: (AddonSubtitleStartupMode) -> Unit,
    onSetSubtitleTextColor: (Color) -> Unit,
    onSetSubtitleBackgroundColor: (Color) -> Unit,
    onSetSubtitleOutlineColor: (Color) -> Unit,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetAudioOutputChannels: (AudioOutputChannels) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetMpvHardwareDecodeMode: (com.nuvio.tv.data.local.MpvHardwareDecodeMode) -> Unit,
    onSetDv7HandlingMode: (Dv7HandlingMode) -> Unit,
    onSetStreamAutoPlayMode: (com.nuvio.tv.data.local.StreamAutoPlayMode) -> Unit,
    onSetStreamAutoPlaySource: (com.nuvio.tv.data.local.StreamAutoPlaySource) -> Unit,
    onSetNextEpisodeThresholdMode: (com.nuvio.tv.data.local.NextEpisodeThresholdMode) -> Unit,
    onSetStreamAutoPlayRegex: (String) -> Unit,
    onSetStreamAutoPlaySelectedAddons: (Set<String>) -> Unit,
    onSetStreamAutoPlaySelectedPlugins: (Set<String>) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleStartupModeDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissSecondaryAudioLanguageDialog: () -> Unit,
    onDismissAudioOutputChannelsDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit,
    onDismissMpvHardwareDecodeModeDialog: () -> Unit,
    onDismissDv7HandlingModeDialog: () -> Unit,
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

    if (showInternalPlayerEngineDialog) {
        InternalPlayerEngineDialog(
            currentEngine = playerSettings.internalPlayerEngine,
            onEngineSelected = { engine ->
                onSetInternalPlayerEngine(engine)
                onDismissInternalPlayerEngineDialog()
            },
            onDismiss = onDismissInternalPlayerEngineDialog
        )
    }

    SubtitleSettingsDialogs(
        showLanguageDialog = showLanguageDialog,
        showSecondaryLanguageDialog = showSecondaryLanguageDialog,
        showSubtitleStartupModeDialog = showSubtitleStartupModeDialog,
        showTextColorDialog = showTextColorDialog,
        showBackgroundColorDialog = showBackgroundColorDialog,
        showOutlineColorDialog = showOutlineColorDialog,
        playerSettings = playerSettings,
        onSetPreferredLanguage = onSetSubtitlePreferredLanguage,
        onSetSecondaryLanguage = onSetSubtitleSecondaryLanguage,
        onSetAddonSubtitleStartupMode = onSetAddonSubtitleStartupMode,
        onSetTextColor = onSetSubtitleTextColor,
        onSetBackgroundColor = onSetSubtitleBackgroundColor,
        onSetOutlineColor = onSetSubtitleOutlineColor,
        onDismissLanguageDialog = onDismissLanguageDialog,
        onDismissSecondaryLanguageDialog = onDismissSecondaryLanguageDialog,
        onDismissSubtitleStartupModeDialog = onDismissSubtitleStartupModeDialog,
        onDismissTextColorDialog = onDismissTextColorDialog,
        onDismissBackgroundColorDialog = onDismissBackgroundColorDialog,
        onDismissOutlineColorDialog = onDismissOutlineColorDialog
    )

    AudioSettingsDialogs(
        showAudioLanguageDialog = showAudioLanguageDialog,
        showSecondaryAudioLanguageDialog = showSecondaryAudioLanguageDialog,
        showAudioOutputChannelsDialog = showAudioOutputChannelsDialog,
        showDecoderPriorityDialog = showDecoderPriorityDialog,
        showMpvHardwareDecodeModeDialog = showMpvHardwareDecodeModeDialog,
        showDv7HandlingModeDialog = showDv7HandlingModeDialog,
        selectedLanguage = playerSettings.preferredAudioLanguage,
        selectedSecondaryLanguage = playerSettings.secondaryPreferredAudioLanguage,
        selectedAudioOutputChannels = playerSettings.audioOutputChannels,
        selectedPriority = playerSettings.decoderPriority,
        selectedMpvHardwareDecodeMode = playerSettings.mpvHardwareDecodeMode,
        selectedDv7HandlingMode = playerSettings.dv7HandlingMode,
        onSetPreferredAudioLanguage = onSetPreferredAudioLanguage,
        onSetSecondaryPreferredAudioLanguage = onSetSecondaryPreferredAudioLanguage,
        onSetAudioOutputChannels = onSetAudioOutputChannels,
        onSetDecoderPriority = onSetDecoderPriority,
        onSetMpvHardwareDecodeMode = onSetMpvHardwareDecodeMode,
        onSetDv7HandlingMode = onSetDv7HandlingMode,
        onDismissAudioLanguageDialog = onDismissAudioLanguageDialog,
        onDismissSecondaryAudioLanguageDialog = onDismissSecondaryAudioLanguageDialog,
        onDismissAudioOutputChannelsDialog = onDismissAudioOutputChannelsDialog,
        onDismissDecoderPriorityDialog = onDismissDecoderPriorityDialog,
        onDismissMpvHardwareDecodeModeDialog = onDismissMpvHardwareDecodeModeDialog,
        onDismissDv7HandlingModeDialog = onDismissDv7HandlingModeDialog
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
    val options = listOf(
        SettingsPickerOption(PlayerPreference.INTERNAL, stringResource(R.string.playback_player_internal), stringResource(R.string.playback_player_internal_desc)),
        SettingsPickerOption(PlayerPreference.EXTERNAL, stringResource(R.string.playback_player_external), stringResource(R.string.playback_player_external_desc)),
        SettingsPickerOption(PlayerPreference.ASK_EVERY_TIME, stringResource(R.string.playback_player_ask), stringResource(R.string.playback_player_ask_desc))
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.playback_player),
        options = options,
        selectedValue = currentPreference,
        onOptionSelected = onPreferenceSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun InternalPlayerEngineDialog(
    currentEngine: InternalPlayerEngine,
    onEngineSelected: (InternalPlayerEngine) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            InternalPlayerEngine.EXOPLAYER,
            stringResource(R.string.playback_engine_exoplayer),
            stringResource(R.string.playback_engine_exoplayer_desc)
        ),
        SettingsPickerOption(
            InternalPlayerEngine.MVP_PLAYER,
            stringResource(R.string.playback_engine_mvplayer),
            stringResource(R.string.playback_engine_mvplayer_desc)
        ),
        SettingsPickerOption(
            InternalPlayerEngine.AUTO,
            stringResource(R.string.playback_player_auto),
            stringResource(R.string.playback_player_auto_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.playback_internal_player_engine),
        options = options,
        selectedValue = currentEngine,
        onOptionSelected = onEngineSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

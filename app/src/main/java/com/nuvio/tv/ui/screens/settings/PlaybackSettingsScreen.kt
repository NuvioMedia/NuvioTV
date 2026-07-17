@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.R
import android.view.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AVAILABLE_TMDB_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.P2pConsentDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image

@Composable
fun PlaybackSettingsScreen(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.playback_title),
        subtitle = stringResource(R.string.playback_subtitle)
    ) {
        PlaybackSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun PlaybackSettingsContent(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val torrentSettings by viewModel.torrentSettingsFlow.collectAsStateWithLifecycle(
        initialValue = com.nuvio.tv.core.torrent.TorrentSettingsData()
    )
    val installedAddonNames by viewModel.installedAddonNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledPluginNames by viewModel.enabledPluginNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var memoryUsageTrigger by remember { mutableStateOf(0) }
    var showMemoryUsage by remember { mutableStateOf(false) }

    // Dialog states
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSecondaryLanguageDialog by remember { mutableStateOf(false) }
    var showSubtitleStartupModeDialog by remember { mutableStateOf(false) }
    var showTextColorDialog by remember { mutableStateOf(false) }
    var showBackgroundColorDialog by remember { mutableStateOf(false) }
    var showOutlineColorDialog by remember { mutableStateOf(false) }
    var showAudioLanguageDialog by remember { mutableStateOf(false) }
    var showDv7HandlingModeDialog by remember { mutableStateOf(false) }
    var showSecondaryAudioLanguageDialog by remember { mutableStateOf(false) }
    var showAudioOutputChannelsDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    var showMpvHardwareDecodeModeDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlayModeDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlaySourceDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlayAddonSelectionDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlayPluginSelectionDialog by remember { mutableStateOf(false) }
    var showStreamRegexDialog by remember { mutableStateOf(false) }
    var showNextEpisodeThresholdModeDialog by remember { mutableStateOf(false) }
    var showReuseLastLinkCacheDialog by remember { mutableStateOf(false) }
    var showPlayerPreferenceDialog by remember { mutableStateOf(false) }
    var showInternalPlayerEngineDialog by remember { mutableStateOf(false) }
    var showP2pConsentDialog by remember { mutableStateOf(false) }

    fun dismissAllDialogs() {
        showLanguageDialog = false
        showSecondaryLanguageDialog = false
        showSubtitleStartupModeDialog = false
        showTextColorDialog = false
        showBackgroundColorDialog = false
        showOutlineColorDialog = false
        showAudioLanguageDialog = false
        showSecondaryAudioLanguageDialog = false
        showAudioOutputChannelsDialog = false
        showDecoderPriorityDialog = false
        showMpvHardwareDecodeModeDialog = false
        showDv7HandlingModeDialog = false
        showStreamAutoPlayModeDialog = false
        showStreamAutoPlaySourceDialog = false
        showStreamAutoPlayAddonSelectionDialog = false
        showStreamAutoPlayPluginSelectionDialog = false
        showStreamRegexDialog = false
        showNextEpisodeThresholdModeDialog = false
        showReuseLastLinkCacheDialog = false
        showPlayerPreferenceDialog = false
        showInternalPlayerEngineDialog = false
        showP2pConsentDialog = false
    }

    fun openDialog(setter: () -> Unit) {
        dismissAllDialogs()
        setter()
    }

    LaunchedEffect(memoryUsageTrigger) {
        if (memoryUsageTrigger == 0) return@LaunchedEffect
        showMemoryUsage = true
        kotlinx.coroutines.delay(2200)
        showMemoryUsage = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.playback_title),
            subtitle = stringResource(R.string.playback_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            PlaybackSettingsSections(
                initialFocusRequester = initialFocusRequester,
                playerSettings = playerSettings,
                onShowPlayerPreferenceDialog = { openDialog { showPlayerPreferenceDialog = true } },
                onShowInternalPlayerEngineDialog = { openDialog { showInternalPlayerEngineDialog = true } },
                onShowAudioLanguageDialog = { openDialog { showAudioLanguageDialog = true } },
                onShowSecondaryAudioLanguageDialog = { openDialog { showSecondaryAudioLanguageDialog = true } },
                onShowAudioOutputChannelsDialog = { openDialog { showAudioOutputChannelsDialog = true } },
                onShowDecoderPriorityDialog = { openDialog { showDecoderPriorityDialog = true } },
                onShowMpvHardwareDecodeModeDialog = { openDialog { showMpvHardwareDecodeModeDialog = true } },
                onShowLanguageDialog = { openDialog { showLanguageDialog = true } },
                onShowSecondaryLanguageDialog = { openDialog { showSecondaryLanguageDialog = true } },
                onShowSubtitleStartupModeDialog = { openDialog { showSubtitleStartupModeDialog = true } },
                onShowTextColorDialog = { openDialog { showTextColorDialog = true } },
                onShowBackgroundColorDialog = { openDialog { showBackgroundColorDialog = true } },
                onShowOutlineColorDialog = { openDialog { showOutlineColorDialog = true } },
                onShowStreamAutoPlayModeDialog = { openDialog { showStreamAutoPlayModeDialog = true } },
                onShowStreamAutoPlaySourceDialog = { openDialog { showStreamAutoPlaySourceDialog = true } },
                onShowStreamAutoPlayAddonSelectionDialog = { openDialog { showStreamAutoPlayAddonSelectionDialog = true } },
                onShowStreamAutoPlayPluginSelectionDialog = { openDialog { showStreamAutoPlayPluginSelectionDialog = true } },
                onShowStreamRegexDialog = { openDialog { showStreamRegexDialog = true } },
                onShowNextEpisodeThresholdModeDialog = { openDialog { showNextEpisodeThresholdModeDialog = true } },
                onShowReuseLastLinkCacheDialog = { openDialog { showReuseLastLinkCacheDialog = true } },
                onSetStreamAutoPlayNextEpisodeEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setStreamAutoPlayNextEpisodeEnabled(enabled) }
                },
                onSetStreamAutoPlayPreferBingeGroupForNextEpisode = { enabled ->
                    coroutineScope.launch {
                        viewModel.setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled)
                    }
                },
                onSetStreamAutoPlayReuseBingeGroup = { enabled ->
                    coroutineScope.launch {
                        viewModel.setStreamAutoPlayReuseBingeGroup(enabled)
                    }
                },
                onSetAutoSwitchInternalPlayerOnError = { enabled ->
                    coroutineScope.launch { viewModel.setAutoSwitchInternalPlayerOnError(enabled) }
                },
                onSetExternalPlayerForwardSubtitles = { enabled ->
                    coroutineScope.launch { viewModel.setExternalPlayerForwardSubtitles(enabled) }
                },
                onSetExternalPlayerSendSkipSegments = { enabled ->
                    coroutineScope.launch { viewModel.setExternalPlayerSendSkipSegments(enabled) }
                },
                onSetNextEpisodeThresholdPercent = { percent ->
                    coroutineScope.launch { viewModel.setNextEpisodeThresholdPercent(percent) }
                },
                onSetNextEpisodeThresholdMinutesBeforeEnd = { minutes ->
                    coroutineScope.launch { viewModel.setNextEpisodeThresholdMinutesBeforeEnd(minutes) }
                },
                onSetStreamAutoPlayTimeoutSeconds = { seconds ->
                    coroutineScope.launch { viewModel.setStreamAutoPlayTimeoutSeconds(seconds) }
                },
                onSetReuseLastLinkEnabled = { enabled -> coroutineScope.launch { viewModel.setStreamReuseLastLinkEnabled(enabled) } },
                onSetStillWatchingEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setStillWatchingEnabled(enabled) }
                },
                onSetStillWatchingEpisodeThreshold = { threshold ->
                    coroutineScope.launch { viewModel.setStillWatchingEpisodeThreshold(threshold) }
                },
                onSetShowPlayerLoadingStatus = { enabled -> coroutineScope.launch { viewModel.setShowPlayerLoadingStatus(enabled) } },
                onSetLoadingOverlayEnabled = { enabled -> coroutineScope.launch { viewModel.setLoadingOverlayEnabled(enabled) } },
                onSetPauseOverlayEnabled = { enabled -> coroutineScope.launch { viewModel.setPauseOverlayEnabled(enabled) } },
                onSetOsdClockEnabled = { enabled -> coroutineScope.launch { viewModel.setOsdClockEnabled(enabled) } },
                onSetSkipIntroEnabled = { enabled -> coroutineScope.launch { viewModel.setSkipIntroEnabled(enabled) } },
                onSetParentalGuideEnabled = { enabled -> coroutineScope.launch { viewModel.setParentalGuideEnabled(enabled) } },
                onSetAutoSkipSegmentTypeEnabled = { segmentType, enabled ->
                    coroutineScope.launch { viewModel.setAutoSkipSegmentTypeEnabled(segmentType, enabled) }
                },
                onSetFrameRateMatchingMode = { mode -> coroutineScope.launch { viewModel.setFrameRateMatchingMode(mode) } },
                onSetResolutionMatchingEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setResolutionMatchingEnabled(enabled) }
                },
                onDisableAfrAndResolution = { coroutineScope.launch { viewModel.disableAfrAndResolution() } },
                onDisableAfrOnly = {
                    coroutineScope.launch {
                        viewModel.setFrameRateMatchingMode(com.nuvio.tv.data.local.FrameRateMatchingMode.OFF)
                    }
                },
                onDisableResolutionOnly = {
                    coroutineScope.launch { viewModel.setResolutionMatchingEnabled(false) }
                },
                onSetDownmixEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setDownmixEnabled(enabled) }
                },
                onSetMaintainOriginalAudioOnDownmix = { enabled ->
                    coroutineScope.launch { viewModel.setMaintainOriginalAudioOnDownmix(enabled) }
                },
                onSetSkipSilence = { enabled -> coroutineScope.launch { viewModel.setSkipSilence(enabled) } },
                onSetRememberAudioDelayPerDevice = { enabled ->
                    coroutineScope.launch { viewModel.setRememberAudioDelayPerDevice(enabled) }
                },
                onSetTunnelingEnabled = { enabled -> coroutineScope.launch { viewModel.setTunnelingEnabled(enabled) } },
                onSetForceOpticalPassthrough = { enabled -> coroutineScope.launch { viewModel.setForceOpticalPassthrough(enabled) } },
                onShowDv7HandlingModeDialog = { openDialog { showDv7HandlingModeDialog = true } },
                onSetDv5ToDv81Enabled = { enabled ->
                    coroutineScope.launch { viewModel.setDv5ToDv81Enabled(enabled) }
                },
                onSetDv7ToDv81PreserveMappingEnabled = { enabled ->
                    coroutineScope.launch {
                        viewModel.setDv7ToDv81PreserveMappingEnabled(enabled)
                    }
                },
                onSetNuvioPerformanceModeEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setNuvioPerformanceModeEnabled(enabled) }
                    memoryUsageTrigger++
                },
                onSetStripHdr10PlusSei = { enabled ->
                    coroutineScope.launch { viewModel.setStripHdr10PlusSei(enabled) }
                },
                onSetBufferEngineEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setBufferEngineEnabled(enabled) }
                    if (enabled) memoryUsageTrigger++
                },
                onSetParallelNetworkEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setParallelNetworkEnabled(enabled) }
                },
                onSetSubtitleSize = { newSize -> coroutineScope.launch { viewModel.setSubtitleSize(newSize) } },
                onSetSubtitleVerticalOffset = { newOffset -> coroutineScope.launch { viewModel.setSubtitleVerticalOffset(newOffset) } },
                onSetSubtitleBold = { bold -> coroutineScope.launch { viewModel.setSubtitleBold(bold) } },
                onSetUseForcedSubtitles = { enabled -> coroutineScope.launch { viewModel.setUseForcedSubtitles(enabled) } },
                onSetSubtitleShowOnlyPreferredLanguages = { enabled ->
                    coroutineScope.launch { viewModel.setSubtitleShowOnlyPreferredLanguages(enabled) }
                },
                onSetSubtitleOutlineEnabled = { enabled -> coroutineScope.launch { viewModel.setSubtitleOutlineEnabled(enabled) } },
                onSetUseLibass = { enabled -> coroutineScope.launch { viewModel.setUseLibass(enabled) } },
                onSetLibassRenderType = { renderType -> coroutineScope.launch { viewModel.setLibassRenderType(renderType) } },
                p2pEnabled = torrentSettings.p2pEnabled,
                onSetP2pEnabled = { enabled ->
                    if (enabled && !torrentSettings.p2pEnabled) {
                        openDialog { showP2pConsentDialog = true }
                    } else {
                        viewModel.setP2pEnabled(enabled)
                    }
                },
                hideTorrentStats = torrentSettings.hideTorrentStats,
                onSetHideTorrentStats = { enabled -> viewModel.setHideTorrentStats(enabled) },
                onSetUseParallelConnections = { enabled ->
                    coroutineScope.launch { viewModel.setUseParallelConnections(enabled) }
                    memoryUsageTrigger++
                },
                onSetParallelConnectionCount = { count ->
                    coroutineScope.launch { viewModel.setParallelConnectionCount(count) }
                    memoryUsageTrigger++
                },
                onSetParallelChunkSizeKb = { kb ->
                    coroutineScope.launch { viewModel.setParallelChunkSizeKb(kb) }
                    memoryUsageTrigger++
                },
                onSetBufferMinBufferMs = { ms ->
                    coroutineScope.launch { viewModel.setBufferMinBufferMs(ms) }
                },
                onSetBufferMaxBufferMs = { ms ->
                    coroutineScope.launch { viewModel.setBufferMaxBufferMs(ms) }
                },
                onSetBufferForPlaybackMs = { ms ->
                    coroutineScope.launch { viewModel.setBufferForPlaybackMs(ms) }
                },
                onSetBufferForPlaybackAfterRebufferMs = { ms ->
                    coroutineScope.launch { viewModel.setBufferForPlaybackAfterRebufferMs(ms) }
                },
                onSetBufferTargetSizeMb = { mb ->
                    coroutineScope.launch { viewModel.setBufferTargetSizeMb(mb) }
                    memoryUsageTrigger++
                },
                onSetBufferBackBufferDurationMs = { ms ->
                    coroutineScope.launch { viewModel.setBufferBackBufferDurationMs(ms) }
                },
                onSetAllowLargeTargetBuffer = { enabled ->
                    coroutineScope.launch { viewModel.setAllowLargeTargetBuffer(enabled) }
                    memoryUsageTrigger++
                },
                onSetBufferBudgetManaged = { enabled ->
                    coroutineScope.launch { viewModel.setBufferBudgetManaged(enabled) }
                    memoryUsageTrigger++
                },
                onSetVodCacheEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setVodCacheEnabled(enabled) }
                },
                onSetVodCacheSizeMode = { mode ->
                    coroutineScope.launch { viewModel.setVodCacheSizeMode(mode) }
                },
                onSetVodCacheSizeMb = { mb ->
                    coroutineScope.launch { viewModel.setVodCacheSizeMb(mb) }
                },
                onResetBufferSettingsToDefaults = {
                    coroutineScope.launch { viewModel.resetBufferSettingsToDefaults() }
                    memoryUsageTrigger++
                },
                onResetNetworkSettingsToDefaults = {
                    coroutineScope.launch { viewModel.resetNetworkSettingsToDefaults() }
                    memoryUsageTrigger++
                },
                onSetEnableHttp2 = { enabled ->
                    coroutineScope.launch { viewModel.setEnableHttp2(enabled) }
                    memoryUsageTrigger++
                }
            )
        }

        AnimatedVisibility(
            visible = showMemoryUsage &&
                    (playerSettings.bufferEngineEnabled || playerSettings.parallelNetworkEnabled || playerSettings.nuvioPerformanceModeEnabled),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val context = LocalContext.current
            val isNativeAutoMode = playerSettings.nuvioPerformanceModeEnabled && !playerSettings.bufferEngineEnabled
            
            // Buffer engine off: only parallel overhead counts. On: managed uses the device cap,
            // otherwise the user's target size.
            val effectiveBufferMb = when {
                playerSettings.nuvioPerformanceModeEnabled -> {
                    if (playerSettings.bufferEngineEnabled && !playerSettings.bufferBudgetManaged) {
                        MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                    } else {
                        NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
                    }
                }
                playerSettings.bufferEngineEnabled -> {
                    if (playerSettings.bufferBudgetManaged) MemoryBudget.budgetMb
                    else MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                }
                else -> MemoryBudget.defaultBufferSizeMb
            }
            val totalUsageMb = MemoryBudget.totalUsageMb(
                effectiveBufferMb,
                playerSettings.parallelConnectionCount,
                Math.ceil(playerSettings.parallelChunkSizeKb / 1024.0).toInt(),
                playerSettings.useParallelConnections && playerSettings.parallelNetworkEnabled
            )

            val safeLimitMb = if (playerSettings.nuvioPerformanceModeEnabled) {
                NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
            } else {
                MemoryBudget.budgetMb
            }

            val warningLimitMb = if (playerSettings.nuvioPerformanceModeEnabled) {
                NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
            } else {
                (MemoryBudget.budgetMb * 1.25f).toInt()
            }

            val usageStatus = MemoryBudget.getUsageStatus(totalUsageMb, safeLimitMb, warningLimitMb)
            val usageColor = when (usageStatus) {
                MemoryUsageStatus.DANGER -> Color(0xFFF44336)
                MemoryUsageStatus.WARNING -> Color(0xFFFF9800)
                MemoryUsageStatus.SAFE -> Color(0xFF4CAF50)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioTheme.colors.BackgroundCard,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .border(NuvioTheme.spacing.hairline, usageColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.playback_estimated_memory_usage, totalUsageMb, warningLimitMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = usageColor
                )
            }
        }
    }

    PlaybackSettingsDialogsHost(
        playerSettings = playerSettings,
        installedAddonNames = installedAddonNames,
        enabledPluginNames = enabledPluginNames,
        showPlayerPreferenceDialog = showPlayerPreferenceDialog,
        showInternalPlayerEngineDialog = showInternalPlayerEngineDialog,
        showLanguageDialog = showLanguageDialog,
        showSecondaryLanguageDialog = showSecondaryLanguageDialog,
        showSubtitleStartupModeDialog = showSubtitleStartupModeDialog,
        showTextColorDialog = showTextColorDialog,
        showBackgroundColorDialog = showBackgroundColorDialog,
        showOutlineColorDialog = showOutlineColorDialog,
        showAudioLanguageDialog = showAudioLanguageDialog,
        showSecondaryAudioLanguageDialog = showSecondaryAudioLanguageDialog,
        showAudioOutputChannelsDialog = showAudioOutputChannelsDialog,
        showDecoderPriorityDialog = showDecoderPriorityDialog,
        showMpvHardwareDecodeModeDialog = showMpvHardwareDecodeModeDialog,
        showDv7HandlingModeDialog = showDv7HandlingModeDialog,
        showStreamAutoPlayModeDialog = showStreamAutoPlayModeDialog,
        showStreamAutoPlaySourceDialog = showStreamAutoPlaySourceDialog,
        showStreamAutoPlayAddonSelectionDialog = showStreamAutoPlayAddonSelectionDialog,
        showStreamAutoPlayPluginSelectionDialog = showStreamAutoPlayPluginSelectionDialog,
        showStreamRegexDialog = showStreamRegexDialog,
        showNextEpisodeThresholdModeDialog = showNextEpisodeThresholdModeDialog,
        showReuseLastLinkCacheDialog = showReuseLastLinkCacheDialog,
        onSetPlayerPreference = { preference ->
            coroutineScope.launch { viewModel.setPlayerPreference(preference) }
        },
        onDismissPlayerPreferenceDialog = ::dismissAllDialogs,
        onSetInternalPlayerEngine = { engine ->
            coroutineScope.launch { viewModel.setInternalPlayerEngine(engine) }
        },
        onDismissInternalPlayerEngineDialog = ::dismissAllDialogs,
        onSetSubtitlePreferredLanguage = { language ->
            coroutineScope.launch { viewModel.setSubtitlePreferredLanguage(language ?: "none") }
        },
        onSetSubtitleSecondaryLanguage = { language ->
            coroutineScope.launch { viewModel.setSubtitleSecondaryLanguage(language) }
        },
        onSetAddonSubtitleStartupMode = { mode ->
            coroutineScope.launch { viewModel.setAddonSubtitleStartupMode(mode) }
        },
        onSetSubtitleTextColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleTextColor(color.toArgb()) }
        },
        onSetSubtitleBackgroundColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleBackgroundColor(color.toArgb()) }
        },
        onSetSubtitleOutlineColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleOutlineColor(color.toArgb()) }
        },
        onSetPreferredAudioLanguage = { language ->
            coroutineScope.launch { viewModel.setPreferredAudioLanguage(language) }
        },
        onSetSecondaryPreferredAudioLanguage = { language ->
            coroutineScope.launch { viewModel.setSecondaryPreferredAudioLanguage(language) }
        },
        onSetAudioOutputChannels = { channels ->
            coroutineScope.launch { viewModel.setAudioOutputChannels(channels) }
        },
        onSetDecoderPriority = { priority ->
            coroutineScope.launch { viewModel.setDecoderPriority(priority) }
        },
        onSetMpvHardwareDecodeMode = { mode ->
            coroutineScope.launch { viewModel.setMpvHardwareDecodeMode(mode) }
        },
        onSetDv7HandlingMode = { mode ->
            coroutineScope.launch { viewModel.setDv7HandlingMode(mode) }
        },
        onSetStreamAutoPlayMode = { mode ->
            coroutineScope.launch { viewModel.setStreamAutoPlayMode(mode) }
        },
        onSetStreamAutoPlaySource = { source ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySource(source) }
        },
        onSetNextEpisodeThresholdMode = { mode ->
            coroutineScope.launch { viewModel.setNextEpisodeThresholdMode(mode) }
        },
        onSetStreamAutoPlayRegex = { regex ->
            coroutineScope.launch { viewModel.setStreamAutoPlayRegex(regex) }
        },
        onSetStreamAutoPlaySelectedAddons = { selected ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySelectedAddons(selected) }
        },
        onSetStreamAutoPlaySelectedPlugins = { selected ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySelectedPlugins(selected) }
        },
        onSetReuseLastLinkCacheHours = { hours ->
            coroutineScope.launch { viewModel.setStreamReuseLastLinkCacheHours(hours) }
        },
        onDismissLanguageDialog = ::dismissAllDialogs,
        onDismissSecondaryLanguageDialog = ::dismissAllDialogs,
        onDismissSubtitleStartupModeDialog = ::dismissAllDialogs,
        onDismissTextColorDialog = ::dismissAllDialogs,
        onDismissBackgroundColorDialog = ::dismissAllDialogs,
        onDismissOutlineColorDialog = ::dismissAllDialogs,
        onDismissAudioLanguageDialog = ::dismissAllDialogs,
        onDismissSecondaryAudioLanguageDialog = ::dismissAllDialogs,
        onDismissAudioOutputChannelsDialog = ::dismissAllDialogs,
        onDismissDecoderPriorityDialog = ::dismissAllDialogs,
        onDismissMpvHardwareDecodeModeDialog = ::dismissAllDialogs,
        onDismissDv7HandlingModeDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlayModeDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlaySourceDialog = ::dismissAllDialogs,
        onDismissStreamRegexDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlayAddonSelectionDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlayPluginSelectionDialog = ::dismissAllDialogs,
        onDismissNextEpisodeThresholdModeDialog = ::dismissAllDialogs,
        onDismissReuseLastLinkCacheDialog = ::dismissAllDialogs
    )

    if (showP2pConsentDialog) {
        P2pConsentDialog(
            onEnableP2p = {
                viewModel.setP2pEnabled(true)
                showP2pConsentDialog = false
            },
            onDismiss = { showP2pConsentDialog = false }
        )
    }
}

@Composable
internal fun ToggleSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = NuvioTheme.colors.TextPrimary
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onCheckedChange(!isChecked) },
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
                border = BorderStroke(NuvioTheme.spacing.xxs, if (enabled) NuvioTheme.colors.FocusRing else NuvioTheme.colors.FocusRing.copy(alpha = 0.3f)),
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (isFocused && enabled) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary).copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (titleTrailingIcon != null) {
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Icon(
                            imageVector = titleTrailingIcon,
                            contentDescription = null,
                            tint = titleTrailingIconTint.copy(alpha = contentAlpha),
                            modifier = Modifier.size(NuvioTheme.spacing.lg)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            Switch(
                checked = isChecked,
                onCheckedChange = null, // Handled by Card onClick
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NuvioTheme.colors.Secondary.copy(alpha = contentAlpha),
                    checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.35f * contentAlpha),
                    uncheckedThumbColor = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                    uncheckedTrackColor = NuvioTheme.colors.Border
                )
            )
        }
    }
}

@Composable
internal fun RenderTypeSettingsItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
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
            containerColor = if (isSelected) {
                NuvioTheme.colors.Primary.copy(alpha = 0.15f * contentAlpha)
            } else {
                NuvioTheme.colors.BackgroundCard
            },
            focusedContainerColor = if (isSelected) {
                NuvioTheme.colors.Primary.copy(alpha = 0.15f * contentAlpha)
            } else {
                NuvioTheme.colors.BackgroundCard
            }
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ),
            border = if (isSelected) Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Primary.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = (if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary).copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Clip
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha)
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NuvioTheme.colors.Primary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(NuvioTheme.spacing.xl)
                )
            }
        }
    }
}

@Composable
internal fun NavigationSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
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
                border = BorderStroke(NuvioTheme.spacing.xxs, if (enabled) NuvioTheme.colors.FocusRing else NuvioTheme.colors.FocusRing.copy(alpha = 0.3f)),
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (isFocused && enabled) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary).copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun SliderSettingsItem(
    icon: ImageVector?,
    title: String,
    value: Int,
    valueText: String,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val span = (maxValue - minValue).toFloat()
    val progress = if (span > 0f) (value - minValue).toFloat() / span else 0f

    SliderSettingsItemLayout(
        icon = icon,
        title = title,
        valueText = valueText,
        subtitle = subtitle,
        enabled = enabled,
        progressFraction = progress,
        onDecrease = {
            val newValue = (value - step).coerceAtLeast(minValue)
            if (newValue != value) onValueChange(newValue)
        },
        onIncrease = {
            val newValue = (value + step).coerceAtMost(maxValue)
            if (newValue != value) onValueChange(newValue)
        },
        onFocused = onFocused,
        modifier = modifier,
    )
}

@Composable
internal fun SliderSettingsItem(
    icon: ImageVector?,
    title: String,
    values: List<Int>,
    selected: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    require(values.isNotEmpty()) { "SliderSettingsItem.values must not be empty" }

    val index = values.indexOf(selected).coerceAtLeast(0)
    val lastIndex = values.lastIndex
    val progress = if (lastIndex > 0) index.toFloat() / lastIndex.toFloat() else 0f

    SliderSettingsItemLayout(
        icon = icon,
        title = title,
        valueText = valueText,
        subtitle = subtitle,
        enabled = enabled,
        progressFraction = progress,
        onDecrease = {
            val newIndex = (index - 1).coerceAtLeast(0)
            val newValue = values[newIndex]
            if (newValue != selected) onValueChange(newValue)
        },
        onIncrease = {
            val newIndex = (index + 1).coerceAtMost(lastIndex)
            val newValue = values[newIndex]
            if (newValue != selected) onValueChange(newValue)
        },
        onFocused = onFocused,
        modifier = modifier,
    )
}

@Composable
private fun SliderSettingsItemLayout(
    icon: ImageVector?,
    title: String,
    valueText: String,
    subtitle: String?,
    enabled: Boolean,
    progressFraction: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onDecrease()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onIncrease()
                        true
                    }
                    else -> false
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, if (enabled) NuvioTheme.colors.FocusRing else NuvioTheme.colors.FocusRing.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = (if (isFocused && enabled) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary).copy(alpha = contentAlpha),
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.Primary.copy(alpha = contentAlpha)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                var decreaseFocused by remember { mutableStateOf(false) }
                Card(
                    onClick = { if (enabled) onDecrease() },
                    modifier = Modifier
                        .onFocusChanged { state ->
                            val nowFocused = state.isFocused
                            if (decreaseFocused != nowFocused) {
                                decreaseFocused = nowFocused
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
                            shape = CircleShape
                        )
                    ),
                    shape = CardDefaults.shape(shape = CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = stringResource(R.string.cd_decrease),
                            tint = (if (decreaseFocused) NuvioTheme.colors.OnPrimary else NuvioTheme.colors.TextPrimary).copy(alpha = contentAlpha),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(NuvioTheme.spacing.sm)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(NuvioTheme.colors.BackgroundElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .height(NuvioTheme.spacing.sm)
                            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                            .background(NuvioTheme.colors.Primary.copy(alpha = contentAlpha))
                    )
                }

                var increaseFocused by remember { mutableStateOf(false) }
                Card(
                    onClick = { if (enabled) onIncrease() },
                    modifier = Modifier
                        .onFocusChanged { state ->
                            val nowFocused = state.isFocused
                            if (increaseFocused != nowFocused) {
                                increaseFocused = nowFocused
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
                            shape = CircleShape
                        )
                    ),
                    shape = CardDefaults.shape(shape = CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_increase),
                            tint = (if (increaseFocused) NuvioTheme.colors.OnPrimary else NuvioTheme.colors.TextPrimary).copy(alpha = contentAlpha),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColorSettingsItem(
    icon: ImageVector,
    title: String,
    currentColor: Color,
    showTransparent: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
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
                border = BorderStroke(NuvioTheme.spacing.xxs, if (enabled) NuvioTheme.colors.FocusRing else NuvioTheme.colors.FocusRing.copy(alpha = 0.3f)),
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (isFocused && enabled) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary).copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Color preview
            if (showTransparent || currentColor.alpha == 0f) {
                // Transparent indicator (checkered pattern simulation)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(NuvioTheme.spacing.xxs, NuvioTheme.colors.Border, CircleShape)
                ) {
                    // Diagonal line to indicate transparency
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(Color.White, Color.Gray, Color.White)
                                )
                            )
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(NuvioTheme.spacing.xxs, NuvioTheme.colors.Border, CircleShape)
                )
            }
        }
    }
}

@Composable
internal fun LanguageSelectionDialog(
    title: String,
    selectedLanguage: String?,
    showNoneOption: Boolean,
    extraOptions: List<Pair<String, String>> = emptyList(),
    onLanguageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val tmdbTitle = stringResource(R.string.tmdb_language_dialog_title)
    val sortedLanguages = remember {
        val baseList = if (title == tmdbTitle) AVAILABLE_TMDB_LANGUAGES else AVAILABLE_SUBTITLE_LANGUAGES
        baseList.sortedBy { it.displayName.lowercase() }
    }
    val languageOptions: List<SettingsPickerOption<String?>> = buildList {
        if (showNoneOption) {
            add(SettingsPickerOption(null, stringResource(R.string.action_none)))
        }
        extraOptions.forEach { (code, name) ->
            add(SettingsPickerOption(code, name, trailing = code.uppercase()))
        }
        sortedLanguages.forEach { language ->
            add(SettingsPickerOption(language.code, language.displayName, trailing = language.code.uppercase()))
        }
    }

    SettingsSingleChoiceDialog(
        title = title,
        options = languageOptions,
        selectedValue = selectedLanguage,
        onOptionSelected = onLanguageSelected,
        onDismiss = onDismiss,
        width = 400.dp,
        maxHeight = 320.dp
    )
}

@Composable
internal fun ColorSelectionDialog(
    title: String,
    colors: List<Color>,
    selectedColor: Color,
    showTransparentOption: Boolean = false,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    val initialChip = colors.find { it.toArgb() == selectedColor.toArgb() }
        ?: colors.find { it.copy(alpha = 1f).toArgb() == selectedColor.copy(alpha = 1f).toArgb() }
        ?: colors.firstOrNull()
        ?: selectedColor
    val focusedColorIndex = colors.indexOfFirst { it.toArgb() == initialChip.toArgb() }
        .let { if (it >= 0) it else 0 }
    val colorListState = rememberLazyListState(initialFirstVisibleItemIndex = focusedColorIndex)
    var currentChipColor by remember { mutableStateOf(initialChip) }
    var alphaPercent by remember { mutableIntStateOf((selectedColor.alpha * 100f).roundToInt().coerceIn(0, 100)) }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
        ) {
            // Color grid using LazyRow for proper TV focus
            LazyRow(
                state = colorListState,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    count = colors.size,
                    key = { index -> colors[index].toArgb() }
                ) { index ->
                    val color = colors[index]
                    ColorOption(
                        color = color,
                        isSelected = color.toArgb() == currentChipColor.toArgb(),
                        isTransparent = color.alpha == 0f,
                        onClick = {
                            currentChipColor = color
                            if (color.alpha < 1f) {
                                alphaPercent = (color.alpha * 100f).roundToInt().coerceIn(0, 100)
                            }
                        },
                        modifier = if (index == focusedColorIndex) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            // Opacity stepper
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.sub_opacity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.width(70.dp)
                )
                Card(
                    onClick = { alphaPercent = (alphaPercent - 10).coerceAtLeast(0) },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm))
                ) {
                    Text(
                        text = "−",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                }
                // Progress bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(NuvioTheme.spacing.sm)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(NuvioTheme.colors.BackgroundElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(alphaPercent / 100f)
                            .height(NuvioTheme.spacing.sm)
                            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                            .background(NuvioTheme.colors.Primary)
                    )
                }
                Text(
                    text = "$alphaPercent%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Card(
                    onClick = { alphaPercent = (alphaPercent + 10).coerceAtMost(100) },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm))
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            // Cancel / Apply buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                Card(
                    onClick = onDismiss,
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier
                            .padding(NuvioTheme.spacing.md)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                Card(
                    onClick = { onColorSelected(currentChipColor.copy(alpha = alphaPercent / 100f)) },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.action_apply),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier
                            .padding(NuvioTheme.spacing.md)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    LaunchedEffect(focusedColorIndex) {
        focusRequester.requestFocusAfterFrames()
    }
}

@Composable
private fun ColorOption(
    color: Color,
    isSelected: Boolean,
    isTransparent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .size(NuvioTheme.spacing.xxxl)
            .then(modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, NuvioTheme.colors.FocusRing),
                shape = CircleShape
            ),
            border = if (isSelected) Border(
                border = BorderStroke(3.dp, NuvioTheme.colors.Primary),
                shape = CircleShape
            ) else Border.None
        ),
        shape = CardDefaults.shape(shape = CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.15f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isTransparent) {
                // Checkered pattern for transparent
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, CircleShape)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = if (color == Color.White || color == Color.Yellow) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

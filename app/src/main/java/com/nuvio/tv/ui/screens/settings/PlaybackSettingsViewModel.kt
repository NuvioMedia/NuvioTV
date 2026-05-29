package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleOrganizationMode
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val torrentSettings: TorrentSettings
) : ViewModel() {

    val playerSettings: Flow<PlayerSettings> = playerSettingsDataStore.playerSettings
    val trailerSettings: Flow<TrailerSettings> = trailerSettingsDataStore.settings
    val torrentSettingsFlow: Flow<TorrentSettingsData> = torrentSettings.settings

    fun setP2pEnabled(enabled: Boolean) = torrentSettings.setP2pEnabled(enabled)
    fun setHideTorrentStats(enabled: Boolean) = torrentSettings.setHideTorrentStats(enabled)

    val lastPlaybackDiagnostics: Flow<LastPlaybackDiagnostics> = playerSettingsDataStore.lastPlaybackDiagnostics
    val installedAddonNames: Flow<List<String>> = addonRepository.getInstalledAddons().map { addons ->
        addons
            .enabledAddons()
            .filter { addon ->
                addon.resources.any { resource ->
                    resource.name.equals("stream", ignoreCase = true)
                }
            }
            .map { it.displayName }
            .distinct()
            .sorted()
    }
    val enabledPluginNames: Flow<List<String>> = combine(
        pluginManager.pluginsEnabled,
        pluginManager.scrapers
    ) { pluginsEnabled, scrapers ->
        if (!pluginsEnabled) {
            emptyList()
        } else {
            scrapers.filter { it.enabled }.map { it.name }.distinct().sorted()
        }
    }

    suspend fun setPlayerPreference(preference: PlayerPreference) {
        playerSettingsDataStore.setPlayerPreference(preference)
    }

    suspend fun setInternalPlayerEngine(engine: InternalPlayerEngine) {
        playerSettingsDataStore.setInternalPlayerEngine(engine)
    }

    suspend fun setAutoSwitchInternalPlayerOnError(enabled: Boolean) {
        playerSettingsDataStore.setAutoSwitchInternalPlayerOnError(enabled)
    }

    suspend fun setExternalPlayerForwardSubtitles(enabled: Boolean) {
        playerSettingsDataStore.setExternalPlayerForwardSubtitles(enabled)
    }

    suspend fun setTrailerEnabled(enabled: Boolean) {
        trailerSettingsDataStore.setEnabled(enabled)
    }

    suspend fun setTrailerDelaySeconds(seconds: Int) {
        trailerSettingsDataStore.setDelaySeconds(seconds)
    }

    // Audio settings

    suspend fun setDecoderPriority(priority: Int) {
        playerSettingsDataStore.setDecoderPriority(priority)
    }

    suspend fun setDownmixEnabled(enabled: Boolean) {
        playerSettingsDataStore.setDownmixEnabled(enabled)
    }

    suspend fun setAudioOutputChannels(channels: AudioOutputChannels) {
        playerSettingsDataStore.setAudioOutputChannels(channels)
    }

    suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean) {
        playerSettingsDataStore.setMaintainOriginalAudioOnDownmix(enabled)
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setTunnelingEnabled(enabled)
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        playerSettingsDataStore.setSkipSilence(enabled)
    }

    suspend fun setRememberAudioDelayPerDevice(enabled: Boolean) {
        playerSettingsDataStore.setRememberAudioDelayPerDevice(enabled)
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        playerSettingsDataStore.setPreferredAudioLanguage(language)
    }

    suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        playerSettingsDataStore.setSecondaryPreferredAudioLanguage(language)
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setLoadingOverlayEnabled(enabled)
    }

    suspend fun setShowPlayerLoadingStatus(enabled: Boolean) {
        playerSettingsDataStore.setShowPlayerLoadingStatus(enabled)
    }

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setPauseOverlayEnabled(enabled)
    }

    suspend fun setOsdClockEnabled(enabled: Boolean) {
        playerSettingsDataStore.setOsdClockEnabled(enabled)
    }

    suspend fun setSkipIntroEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSkipIntroEnabled(enabled)
    }

    suspend fun setParentalGuideEnabled(enabled: Boolean) {
        playerSettingsDataStore.setParentalGuideEnabled(enabled)
    }

    suspend fun setAutoSkipSegmentTypeEnabled(segmentType: AutoSkipSegmentType, enabled: Boolean) {
        playerSettingsDataStore.setAutoSkipSegmentTypeEnabled(segmentType, enabled)
    }

    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        playerSettingsDataStore.setFrameRateMatchingMode(mode)
    }

    suspend fun setResolutionMatchingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setResolutionMatchingEnabled(enabled)
    }

    suspend fun disableAfrAndResolution() {
        playerSettingsDataStore.setFrameRateMatchingMode(FrameRateMatchingMode.OFF)
        playerSettingsDataStore.setResolutionMatchingEnabled(false)
    }

    suspend fun setMpvHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        playerSettingsDataStore.setMpvHardwareDecodeMode(mode)
    }


    suspend fun setDv5ToDv81Enabled(enabled: Boolean) {
        playerSettingsDataStore.setDv5ToDv81Enabled(enabled)
    }

    suspend fun setDv7ToDv81PreserveMappingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setDv7ToDv81PreserveMappingEnabled(enabled)
    }

    suspend fun setDv7HandlingMode(mode: Dv7HandlingMode) {
        playerSettingsDataStore.setDv7HandlingMode(mode)
        // The conversion-mode override only applies when handling is Convert to
        // DV8.1. Any other mode clears the override back to None so a stale forced
        // mode can't linger (disabled in UI but still stored).
        if (mode != Dv7HandlingMode.DV81_LIBDOVI) {
            playerSettingsDataStore.setDv7LibdoviModeOverride(-1)
        }
    }

    suspend fun setDv7LibdoviModeOverride(mode: Int) {
        playerSettingsDataStore.setDv7LibdoviModeOverride(mode)
    }

    suspend fun setUseLibass(enabled: Boolean) {
        playerSettingsDataStore.setUseLibass(enabled)
    }

    suspend fun setLibassRenderType(renderType: LibassRenderType) {
        playerSettingsDataStore.setLibassRenderType(renderType)
    }

    suspend fun setSubtitlePreferredLanguage(language: String) {
        playerSettingsDataStore.setSubtitlePreferredLanguage(language)
    }

    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        playerSettingsDataStore.setSubtitleSecondaryLanguage(language)
    }

    suspend fun setUseForcedSubtitles(enabled: Boolean) {
        playerSettingsDataStore.setUseForcedSubtitles(enabled)
    }

    suspend fun setSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleShowOnlyPreferredLanguages(enabled)
    }

    suspend fun setSubtitleSize(size: Int) {
        playerSettingsDataStore.setSubtitleSize(size)
    }

    suspend fun setSubtitleVerticalOffset(offset: Int) {
        playerSettingsDataStore.setSubtitleVerticalOffset(offset)
    }

    suspend fun setSubtitleBold(bold: Boolean) {
        playerSettingsDataStore.setSubtitleBold(bold)
    }

    suspend fun setSubtitleTextColor(color: Int) {
        playerSettingsDataStore.setSubtitleTextColor(color)
    }

    suspend fun setSubtitleBackgroundColor(color: Int) {
        playerSettingsDataStore.setSubtitleBackgroundColor(color)
    }

    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleOutlineEnabled(enabled)
    }

    suspend fun setSubtitleOutlineColor(color: Int) {
        playerSettingsDataStore.setSubtitleOutlineColor(color)
    }

    suspend fun setSubtitleOutlineWidth(width: Int) {
        playerSettingsDataStore.setSubtitleOutlineWidth(width)
    }

    suspend fun setSubtitleOrganizationMode(mode: SubtitleOrganizationMode) {
        playerSettingsDataStore.setSubtitleOrganizationMode(mode)
    }

    suspend fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        playerSettingsDataStore.setAddonSubtitleStartupMode(mode)
    }

    // Buffer settings functions

    suspend fun setBufferMinBufferMs(ms: Int) {
        playerSettingsDataStore.setBufferMinBufferMs(ms)
    }

    suspend fun setBufferMaxBufferMs(ms: Int) {
        playerSettingsDataStore.setBufferMaxBufferMs(ms)
    }

    suspend fun setBufferForPlaybackMs(ms: Int) {
        playerSettingsDataStore.setBufferForPlaybackMs(ms)
    }

    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) {
        playerSettingsDataStore.setBufferForPlaybackAfterRebufferMs(ms)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun setBufferTargetSizeMb(mb: Int) {
        val current = playerSettings.first()
        if (!current.useParallelConnections) {
            playerSettingsDataStore.setBufferTargetSizeMb(mb)
            return
        }
        val (adjBuffer, adjChunk) = MemoryBudget.enforce(
            mb,
            current.parallelChunkSizeMb,
            current.parallelConnectionCount
        )
        if (adjBuffer == mb && adjChunk == current.parallelChunkSizeMb) {
            playerSettingsDataStore.setBufferTargetSizeMb(mb)
        } else {
            playerSettingsDataStore.updateMemorySettings(
                targetBufferSizeMb = adjBuffer,
                parallelChunkSizeMb = adjChunk
            )
        }
    }

    suspend fun setBufferBackBufferDurationMs(ms: Int) {
        playerSettingsDataStore.setBufferBackBufferDurationMs(ms)
    }

    suspend fun setBufferRetainBackBufferFromKeyframe(retain: Boolean) {
        playerSettingsDataStore.setBufferRetainBackBufferFromKeyframe(retain)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun resetBufferSettingsToDefaults() {
        playerSettingsDataStore.resetBufferSettingsToDefaults()
        val current = playerSettings.first()
        if (!current.useParallelConnections) return

        val (adjBuffer, adjChunk) = MemoryBudget.enforce(
            MemoryBudget.defaultBufferSizeMb,
            current.parallelChunkSizeMb,
            current.parallelConnectionCount
        )
        if (adjChunk != current.parallelChunkSizeMb ||
            adjBuffer != MemoryBudget.defaultBufferSizeMb
        ) {
            playerSettingsDataStore.updateMemorySettings(
                targetBufferSizeMb = adjBuffer,
                parallelChunkSizeMb = adjChunk
            )
        }
    }

    suspend fun resetNetworkSettingsToDefaults() {
        playerSettingsDataStore.resetNetworkSettingsToDefaults()
    }

    suspend fun setVodCacheEnabled(enabled: Boolean) {
        playerSettingsDataStore.setVodCacheEnabled(enabled)
    }
    suspend fun setBufferEngineEnabled(enabled: Boolean) {
        playerSettingsDataStore.setBufferEngineEnabled(enabled)
    }

    suspend fun setParallelNetworkEnabled(enabled: Boolean) {
        playerSettingsDataStore.setParallelNetworkEnabled(enabled)
    }

    suspend fun setAllowLargeTargetBuffer(enabled: Boolean) {
        playerSettingsDataStore.setAllowLargeTargetBuffer(enabled)
    }
    suspend fun setBufferBudgetManaged(enabled: Boolean) {
        playerSettingsDataStore.setBufferBudgetManaged(enabled)
    }
    suspend fun setUseParallelConnections(enabled: Boolean) {
        if (!enabled) {
            playerSettingsDataStore.setUseParallelConnections(false)
            return
        }
        val current = playerSettings.first()
        val bufferMb = MemoryBudget.effectiveBufferMb(current.bufferSettings.targetBufferSizeMb)
        val (adjBuffer, adjChunk) = MemoryBudget.enforce(
            bufferMb,
            current.parallelChunkSizeMb,
            current.parallelConnectionCount
        )
        if (adjBuffer == bufferMb && adjChunk == current.parallelChunkSizeMb) {
            playerSettingsDataStore.setUseParallelConnections(true)
        } else {
            playerSettingsDataStore.updateMemorySettings(
                useParallelConnections = true,
                targetBufferSizeMb = if (adjBuffer != bufferMb) adjBuffer else null,
                parallelChunkSizeMb = adjChunk
            )
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun setParallelConnectionCount(count: Int) {
        val current = playerSettings.first()
        if (count <= current.parallelConnectionCount) {
            playerSettingsDataStore.setParallelConnectionCount(count)
        } else {
            val bufferMb = MemoryBudget.effectiveBufferMb(current.bufferSettings.targetBufferSizeMb)
            val maxChunk = MemoryBudget.maxChunkMb(bufferMb, count)
            val newChunkMb = current.parallelChunkSizeMb.coerceAtMost(maxChunk)
            if (newChunkMb == current.parallelChunkSizeMb) {
                playerSettingsDataStore.setParallelConnectionCount(count)
            } else {
                playerSettingsDataStore.updateMemorySettings(
                    parallelConnectionCount = count,
                    parallelChunkSizeMb = newChunkMb
                )
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun setParallelChunkSizeMb(mb: Int) {
        val current = playerSettings.first()
        val bufferMb = MemoryBudget.effectiveBufferMb(current.bufferSettings.targetBufferSizeMb)
        val maxChunk = MemoryBudget.maxChunkMb(bufferMb, current.parallelConnectionCount)
        val clampedChunk = mb.coerceAtMost(maxChunk)
        playerSettingsDataStore.setParallelChunkSizeMb(clampedChunk)
    }

    suspend fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        playerSettingsDataStore.setStreamAutoPlayMode(mode)
    }

    suspend fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        playerSettingsDataStore.setStreamAutoPlaySource(source)
    }

    suspend fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        playerSettingsDataStore.setStreamAutoPlaySelectedAddons(addons)
    }

    suspend fun setStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        playerSettingsDataStore.setStreamAutoPlaySelectedPlugins(plugins)
    }

    suspend fun setStreamAutoPlayRegex(regex: String) {
        playerSettingsDataStore.setStreamAutoPlayRegex(regex)
    }

    suspend fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(enabled)
    }

    suspend fun setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled)
    }

    suspend fun setStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        playerSettingsDataStore.setStreamAutoPlayReuseBingeGroup(enabled)
    }

    suspend fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        playerSettingsDataStore.setStreamAutoPlayTimeoutSeconds(seconds)
    }

    suspend fun setStillWatchingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStillWatchingEnabled(enabled)
    }

    suspend fun setStillWatchingEpisodeThreshold(threshold: Int) {
        playerSettingsDataStore.setStillWatchingEpisodeThreshold(threshold)
    }

    suspend fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        playerSettingsDataStore.setNextEpisodeThresholdMode(mode)
    }

    suspend fun setNextEpisodeThresholdPercent(percent: Float) {
        playerSettingsDataStore.setNextEpisodeThresholdPercent(percent)
    }

    suspend fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        playerSettingsDataStore.setNextEpisodeThresholdMinutesBeforeEnd(minutes)
    }

    suspend fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        playerSettingsDataStore.setStreamReuseLastLinkEnabled(enabled)
    }

    suspend fun setStreamReuseLastLinkCacheHours(hours: Int) {
        playerSettingsDataStore.setStreamReuseLastLinkCacheHours(hours)
    }

    suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
        playerSettingsDataStore.setVodCacheSizeMode(mode)
    }

    suspend fun setVodCacheSizeMb(mb: Int) {
        playerSettingsDataStore.setVodCacheSizeMb(mb)
    }
}
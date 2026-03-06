package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.SubtitleOrganizationMode
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.VodCacheSizeMode
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
    private val pluginManager: PluginManager
) : ViewModel() {

    val playerSettings: Flow<PlayerSettings> = playerSettingsDataStore.playerSettings
    val trailerSettings: Flow<TrailerSettings> = trailerSettingsDataStore.settings
    val installedAddonNames: Flow<List<String>> = addonRepository.getInstalledAddons().map { addons ->
        addons
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

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setTunnelingEnabled(enabled)
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        playerSettingsDataStore.setSkipSilence(enabled)
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

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setPauseOverlayEnabled(enabled)
    }

    suspend fun setOsdClockEnabled(enabled: Boolean) {
        playerSettingsDataStore.setOsdClockEnabled(enabled)
    }

    suspend fun setSkipIntroEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSkipIntroEnabled(enabled)
    }

    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        playerSettingsDataStore.setFrameRateMatchingMode(mode)
    }

    suspend fun setResolutionMatchingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setResolutionMatchingEnabled(enabled)
    }

    suspend fun setMapDV7ToHevc(enabled: Boolean) {
        playerSettingsDataStore.setMapDV7ToHevc(enabled)
    }

    suspend fun setExperimentalDv7ToDv81Enabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv7ToDv81Enabled(enabled)
    }

    suspend fun setExperimentalDtsIecPassthroughEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDtsIecPassthroughEnabled(enabled)
    }

    suspend fun setExperimentalDv5ToDv81Enabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv5ToDv81Enabled(enabled)
    }

    suspend fun setExperimentalDv7ToDv81PreserveMappingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setExperimentalDv7ToDv81PreserveMappingEnabled(enabled)
    }

    /**
     * Set whether to use libass for ASS/SSA subtitle rendering
     */
    suspend fun setUseLibass(enabled: Boolean) {
        playerSettingsDataStore.setUseLibass(enabled)
    }

    /**
     * Set the libass render type
     */
    suspend fun setLibassRenderType(renderType: LibassRenderType) {
        playerSettingsDataStore.setLibassRenderType(renderType)
    }

    // Subtitle style settings functions

    suspend fun setSubtitlePreferredLanguage(language: String) {
        playerSettingsDataStore.setSubtitlePreferredLanguage(language)
    }

    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        playerSettingsDataStore.setSubtitleSecondaryLanguage(language)
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

    @androidx.annotation.OptIn(UnstableApi::class)
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
        val clamped = mb.coerceAtMost(maxChunk)
        playerSettingsDataStore.setParallelChunkSizeMb(clamped)
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

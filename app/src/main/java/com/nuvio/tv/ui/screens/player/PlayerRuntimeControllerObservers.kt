package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    val id = contentId ?: return
    val type = contentType ?: return
    
    scope.launch {
        _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }
        
        try {
            
            val videoId = if (type == "series" && currentSeason != null && currentEpisode != null) {
                "${id.split(":").firstOrNull() ?: id}:$currentSeason:$currentEpisode"
            } else {
                null
            }
            
            val subtitles = subtitleRepository.getSubtitles(
                type = type,
                id = id.split(":").firstOrNull() ?: id, 
                videoId = videoId
            )
            
            _uiState.update { 
                it.copy(
                    addonSubtitles = subtitles,
                    isLoadingAddonSubtitles = false
                ) 
            }
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (e: Exception) {
            _uiState.update { 
                it.copy(
                    isLoadingAddonSubtitles = false,
                    addonSubtitlesError = e.message
                ) 
            }
        }
    }
}

internal fun PlayerRuntimeController.refreshSubtitlesForCurrentEpisode() {
    autoSubtitleSelected = false
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = true,
            addonSubtitlesError = null
        )
    }
    fetchAddonSubtitles()
}

internal fun PlayerRuntimeController.observeBlurUnwatchedEpisodes() {
    scope.launch {
        layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
            _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
        }
    }
}

internal fun PlayerRuntimeController.observeEpisodeWatchProgress() {
    val id = contentId ?: return
    val type = contentType ?: return
    if (type.lowercase() != "series") return
    val baseId = id.split(":").firstOrNull() ?: id
    scope.launch {
        watchProgressRepository.getAllEpisodeProgress(baseId).collectLatest { progressMap ->
            _uiState.update { it.copy(episodeWatchProgressMap = progressMap) }
        }
    }
    scope.launch {
        watchedItemsPreferences.getWatchedEpisodesForContent(baseId).collectLatest { watchedSet ->
            _uiState.update { it.copy(watchedEpisodeKeys = watchedSet) }
        }
    }
}

internal fun PlayerRuntimeController.observeSubtitleSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collect { settings ->
            val wasFrameRateMatchingEnabled =
                _uiState.value.frameRateMatchingMode != FrameRateMatchingMode.OFF
            _uiState.update { state ->
                val shouldShowOverlay = if (settings.loadingOverlayEnabled && !hasRenderedFirstFrame) {
                    true
                } else if (!settings.loadingOverlayEnabled) {
                    false
                } else {
                    state.showLoadingOverlay
                }

                state.copy(
                    subtitleStyle = settings.subtitleStyle,
                    subtitleOrganizationMode = settings.subtitleOrganizationMode,
                    loadingOverlayEnabled = settings.loadingOverlayEnabled,
                    showLoadingOverlay = shouldShowOverlay,
                    pauseOverlayEnabled = settings.pauseOverlayEnabled,
                    osdClockEnabled = settings.osdClockEnabled,
                    frameRateMatchingMode = settings.frameRateMatchingMode
                )
            }
            if (!wasFrameRateMatchingEnabled &&
                settings.frameRateMatchingMode != FrameRateMatchingMode.OFF &&
                _uiState.value.detectedFrameRate <= 0f &&
                currentStreamUrl.isNotBlank()
            ) {
                startFrameRateProbe(currentStreamUrl, currentHeaders, true)
            }
            if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
                frameRateProbeJob?.cancel()
                _uiState.update {
                    it.copy(
                        detectedFrameRateRaw = 0f,
                        detectedFrameRate = 0f,
                        detectedFrameRateSource = null
                    )
                }
            }

            if (!settings.pauseOverlayEnabled) {
                cancelPauseOverlay()
            } else if (!_uiState.value.isPlaying &&
                !_uiState.value.showPauseOverlay && pauseOverlayJob == null &&
                userPausedManually && hasRenderedFirstFrame
            ) {
                schedulePauseOverlay()
            }
            streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled
            streamAutoPlayModeSetting = settings.streamAutoPlayMode
            streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
            streamAutoPlayPreferBingeGroupForNextEpisodeSetting =
                settings.streamAutoPlayPreferBingeGroupForNextEpisode
            nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
            nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
            nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd

            applySubtitlePreferences(
                settings.subtitleStyle.preferredLanguage,
                settings.subtitleStyle.secondaryPreferredLanguage
            )
            val subtitlePreferenceChanged =
                lastSubtitlePreferredLanguage != settings.subtitleStyle.preferredLanguage ||
                    lastSubtitleSecondaryLanguage != settings.subtitleStyle.secondaryPreferredLanguage
            if (subtitlePreferenceChanged) {
                autoSubtitleSelected = false
                lastSubtitlePreferredLanguage = settings.subtitleStyle.preferredLanguage
                lastSubtitleSecondaryLanguage = settings.subtitleStyle.secondaryPreferredLanguage
                tryAutoSelectPreferredSubtitleFromAvailableTracks()
            }

            val wasEnabled = skipIntroEnabled
            skipIntroEnabled = settings.skipIntroEnabled
            if (!skipIntroEnabled) {
                if (skipIntervals.isNotEmpty() || _uiState.value.activeSkipInterval != null) {
                    skipIntervals = emptyList()
                    skipIntroFetchedKey = null
                    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
                }
            } else {
                if (!wasEnabled || skipIntroFetchedKey == null) {
                    _uiState.update { it.copy(skipIntervalDismissed = false) }
                    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.loadSavedProgressFor(season: Int?, episode: Int?) {
    if (contentId == null) return
    
    scope.launch {
        pendingResumeProgress = null
        val progress = if (season != null && episode != null) {
            watchProgressRepository.getEpisodeProgress(contentId, season, episode).firstOrNull()
        } else {
            watchProgressRepository.getProgress(contentId).firstOrNull()
        }
        
        progress?.let { saved ->
            
            if (saved.isInProgress()) {
                pendingResumeProgress = saved
                _exoPlayer?.let { player ->
                    if (player.playbackState == Player.STATE_READY) {
                        tryApplyPendingResumeProgress(player)
                    }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    if (id.isNullOrBlank()) return
    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return
    if (season == null || episode == null) return

    val key = "$imdbId:$season:$episode"
    if (skipIntroFetchedKey == key) return
    skipIntroFetchedKey = key

    scope.launch {
        val intervals = skipIntroRepository.getSkipIntervals(imdbId, season, episode)
        skipIntervals = intervals
    }
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgress(player: Player) {
    val saved = pendingResumeProgress ?: return
    if (!player.isCurrentMediaItemSeekable) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = null) }
        return
    }
    val duration = player.duration
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }

    if (target > 0L) {
        player.seekTo(target)
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamFromStartAfter416() {
    if (hasRetriedCurrentStreamAfter416) return
    hasRetriedCurrentStreamAfter416 = true
    pendingResumeProgress = null
    _uiState.update {
        it.copy(
            pendingSeekPosition = null,
            error = null,
            showLoadingOverlay = it.loadingOverlayEnabled
        )
    }
    _exoPlayer?.let { player ->
        runCatching {
            player.stop()
            player.clearMediaItems()
            player.setMediaSource(mediaSourceFactory.createMediaSource(currentStreamUrl, currentHeaders))
            player.seekTo(0L)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    error = e.message ?: "Playback error",
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
}

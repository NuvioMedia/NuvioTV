package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.OpenSubtitlesHasher
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SubtitleFetchRequest(
    val type: String,
    val id: String,
    val videoId: String?
)

internal fun PlayerRuntimeController.buildSubtitleFetchRequest(): SubtitleFetchRequest? {
    val id = contentId ?: return null
    val type = contentType ?: return null
    return SubtitleFetchRequest(
        type = type.lowercase(),
        id = id,
        videoId = currentVideoId
    )
}

internal suspend fun PlayerRuntimeController.fetchAddonSubtitlesNow(
    onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null
): List<Subtitle> {
    val request = buildSubtitleFetchRequest() ?: return emptyList()
    val installedAddonOrder = addonRepository.getInstalledAddons().firstOrNull()
        ?.map { it.displayName }
        .orEmpty()
    _uiState.update { it.copy(installedSubtitleAddonOrder = installedAddonOrder) }

    // Compute hash lazily for providers that support OpenSubtitles-style matching.
    if (currentVideoHash == null && currentStreamUrl.isNotBlank()) {
        val result = OpenSubtitlesHasher.compute(currentStreamUrl, currentHeaders)
        if (result != null) {
            currentVideoHash = result.hash
            if (currentVideoSize == null) currentVideoSize = result.fileSize
            // Update cache now that we have the computed hash
            val key = streamCacheKey
            val url = currentStreamUrl.takeIf { it.isNotBlank() }
            if (key != null && url != null) {
                val state = _uiState.value
                streamLinkCacheDataStore.save(
                    contentKey = key,
                    url = url,
                    streamName = state.currentStreamName ?: title,
                    headers = currentHeaders,
                    filename = currentFilename,
                    videoHash = currentVideoHash,
                    videoSize = currentVideoSize
                )
            }
        }
    }

    return subtitleRepository.getSubtitles(
        type = request.type,
        id = request.id,
        videoId = request.videoId,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        filename = currentFilename,
        onProgress = onProgress
    )
}

internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    if (buildSubtitleFetchRequest() == null) return
    
    scope.launch {
        _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }
        
        try {
            val subtitles = fetchAddonSubtitlesNow()
            Log.d(PlayerRuntimeController.TAG, "fetchAddonSubtitles done: ${subtitles.size} subs, persistedPref=${persistedTrackPreference?.subtitle?.javaClass?.simpleName}")
            _uiState.update { 
                it.copy(
                    addonSubtitles = subtitles,
                    isLoadingAddonSubtitles = false
                ) 
            }
            val pendingAddon = pendingRestoredAddonSubtitle
            if (pendingAddon != null) {
                val match = subtitles.firstOrNull { it.id == pendingAddon.id }
                    ?: subtitles.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.lang, pendingAddon.lang) }
                if (match != null) {
                    Log.d(PlayerRuntimeController.TAG, "fetchAddonSubtitles: re-applying restored addon id=${match.id}")
                    autoSubtitleSelected = true
                    selectAddonSubtitle(match)
                    _uiState.update { it.copy(selectedAddonSubtitle = match, selectedSubtitleTrackIndex = -1) }
                    return@launch
                }
            }
            applyPersistedTrackPreference(
                audioTracks = _uiState.value.audioTracks,
                subtitleTracks = _uiState.value.subtitleTracks
            )
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
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()
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
            val currentState = _uiState.value
            val resolvedInternalPlayerEngine =
                runtimeInternalPlayerEngineOverride ?: settings.internalPlayerEngine
            val resolvedAudioAmplificationDb = when {
                !hasInitializedAudioAmplificationForSession -> {
                    hasInitializedAudioAmplificationForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.audioAmplificationDb
                    } else {
                        AUDIO_AMPLIFICATION_MIN_DB
                    }
                }
                settings.persistAudioAmplification -> settings.audioAmplificationDb
                else -> currentState.audioAmplificationDb
            }

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
                    loadingOverlayEnabled = settings.loadingOverlayEnabled,
                    showLoadingOverlay = shouldShowOverlay,
                    pauseOverlayEnabled = settings.pauseOverlayEnabled,
                    osdClockEnabled = settings.osdClockEnabled,
                    internalPlayerEngine = resolvedInternalPlayerEngine,
                    frameRateMatchingMode = settings.frameRateMatchingMode,
                    tunnelingEnabled = settings.tunnelingEnabled,
                    persistAudioAmplification = settings.persistAudioAmplification,
                    audioAmplificationDb = resolvedAudioAmplificationDb
                )
            }

            if (resolvedAudioAmplificationDb != currentState.audioAmplificationDb) {
                applyAudioAmplification(resolvedAudioAmplificationDb)
            }

            if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
                frameRateProbeJob?.cancel()
                _uiState.update {
                    it.copy(
                        detectedFrameRateRaw = 0f,
                        detectedFrameRate = 0f,
                        detectedFrameRateSource = null,
                        afrProbeRunning = false
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
            autoSwitchInternalPlayerOnErrorEnabled = settings.autoSwitchInternalPlayerOnError
            currentInternalPlayerEngine = resolvedInternalPlayerEngine
            streamAutoPlayModeSetting = settings.streamAutoPlayMode
            _uiState.update { it.copy(streamAutoPlayMode = settings.streamAutoPlayMode) }
            streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
            streamAutoPlayPreferBingeGroupForNextEpisodeSetting =
                settings.streamAutoPlayPreferBingeGroupForNextEpisode
            nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
            nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
            nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd

            val resolvedAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = settings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages()
            )
            if (resolvedAudioLanguages != mpvPreferredAudioLanguages) {
                mpvPreferredAudioLanguages = resolvedAudioLanguages
                if (isUsingMpvEngine()) {
                    mpvView?.applyAudioLanguagePreferences(resolvedAudioLanguages)
                    updateMpvAvailableTracks()
                }
            }

            applySubtitlePreferences(
                settings.subtitleStyle.preferredLanguage,
                settings.subtitleStyle.secondaryPreferredLanguage
            )
            val subtitlePreferenceChanged =
                lastSubtitlePreferredLanguage != settings.subtitleStyle.preferredLanguage ||
                    lastSubtitleSecondaryLanguage != settings.subtitleStyle.secondaryPreferredLanguage
            if (subtitlePreferenceChanged) {
                if (!subtitleDisabledByPersistedPreference && !subtitleAddonRestoredByPersistedPreference) autoSubtitleSelected = false
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
                if (isUsingMpvEngine()) {
                    _uiState.update { it.copy(pendingSeekPosition = null) }
                    mpvView?.let { view ->
                        applyPendingMpvSeekIfNeeded(view)
                    }
                } else {
                    _exoPlayer?.let { player ->
                        if (player.playbackState == Player.STATE_READY) {
                            tryApplyPendingResumeProgress(player)
                        }
                    }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    if (id.isNullOrBlank()) return

    // Prefer videoId over contentId — videoId carries the season/episode-specific ID
    val effectiveId = currentVideoId?.takeIf { it.isNotBlank() } ?: id

    // MAL ID format: "mal:57658:1" (malId:episode)
    if (effectiveId.startsWith("mal:")) {
        val parts = effectiveId.split(":")
        val malId = parts.getOrNull(1) ?: return
        val malEpisode = parts.getOrNull(2)?.toIntOrNull() ?: episode ?: return
        val key = "mal:$malId:$malEpisode"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        scope.launch {
            skipIntervals = skipIntroRepository.getSkipIntervalsForMal(malId, malEpisode)
        }
        return
    }

    // Kitsu ID format: "kitsu:12345:1" (kitsuId:episode)
    if (effectiveId.startsWith("kitsu:")) {
        val parts = effectiveId.split(":")
        val kitsuId = parts.getOrNull(1) ?: return
        val kitsuEpisode = parts.getOrNull(2)?.toIntOrNull() ?: episode ?: return
        val key = "kitsu:$kitsuId:$kitsuEpisode"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        scope.launch {
            skipIntervals = skipIntroRepository.getSkipIntervalsForKitsu(kitsuId, kitsuEpisode)
        }
        return
    }

    val imdbId = effectiveId.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return
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
            player.setMediaSource(
                mediaSourceFactory.createMediaSource(
                    url = currentStreamUrl,
                    headers = currentHeaders,
                    mimeTypeOverride = currentStreamMimeType
                )
            )
            player.seekTo(0L)
            player.playWhenReady = true
            player.prepare()
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

package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal const val AUDIO_AMPLIFICATION_MIN_DB = 0
internal const val AUDIO_AMPLIFICATION_MAX_DB = 10
internal const val CENTER_MIX_LEVEL_MIN_DB = -10
internal const val CENTER_MIX_LEVEL_MAX_DB = 30
internal const val AUDIO_DELAY_MIN_MS = -3000
internal const val AUDIO_DELAY_MAX_MS = 3000
internal const val AUDIO_DELAY_STEP_MS = 25

internal fun PlayerRuntimeController.applyAudioDelay(
    delayMs: Int,
    persistForCurrentRoute: Boolean = true
) {
    val clampedDelayMs = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    audioDelayUs.set(clampedDelayMs.toLong() * 1000L)
    _uiState.update { it.copy(audioDelayMs = clampedDelayMs) }
    if (persistForCurrentRoute) {
        persistAudioDelayForCurrentRoute(clampedDelayMs)
    }
}

internal fun PlayerRuntimeController.skipActiveInterval(): Boolean {
    return skipInterval(_uiState.value.activeSkipInterval ?: return false)
}

internal fun PlayerRuntimeController.skipInterval(interval: SkipInterval): Boolean {
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val seekMs = if (interval.endTime == Double.MAX_VALUE) {
        duration
    } else {
        (interval.endTime * 1000).toLong()
    }
    seekPlaybackTo(seekMs.coerceAtMost(duration))
    scheduleProgressSyncAfterSeek()
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    return true
}

internal fun PlayerRuntimeController.applyAudioAmplification(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val isAudioAmplificationAvailable = isUsingMpvEngine() || _exoPlayer != null
    val wasActive = gainAudioProcessor.isGainEnabled()
    gainAudioProcessor.setGainDb(if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    val isActiveNow = gainAudioProcessor.isGainEnabled()

    if (wasActive != isActiveNow && !isUsingMpvEngine()) {
        playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
        _exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        }
    }

    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(clampedDb)
    }
    _uiState.update {
        it.copy(
            audioAmplificationDb = clampedDb,
            isAudioAmplificationAvailable = isAudioAmplificationAvailable
        )
    }
}

internal fun PlayerRuntimeController.applyCenterMixLevel(db: Int) {
    val clampedDb = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    ffmpegAudioRenderer?.setCenterMixLevelDb(clampedDb)
    _uiState.update { state ->
        state.copy(centerMixLevelDb = clampedDb)
    }
}

internal fun PlayerRuntimeController.updateAudioControlAvailability(
    audioTracks: List<TrackInfo> = _uiState.value.audioTracks,
    selectedAudioIndex: Int = _uiState.value.selectedAudioTrackIndex
) {
    val selectedTrack = audioTracks.getOrNull(selectedAudioIndex)
    val isAudioAmplificationAvailable = isUsingMpvEngine() || _exoPlayer != null
    val isCenterMixAvailable =
        ffmpegAudioRenderer?.isCenterMixActive() == true && (selectedTrack?.channelCount ?: 0) > 2
    val clampedDb = _uiState.value.audioAmplificationDb
        .coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    gainAudioProcessor.setGainDb(
        if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB
    )
    _uiState.update { state ->
        state.copy(
            isAudioAmplificationAvailable = isAudioAmplificationAvailable,
            isCenterMixAvailable = isCenterMixAvailable
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayStateAfterPlaybackEnded() {
    if (!shouldResetPostPlayStateAfterPlaybackEnded(
            state = _uiState.value,
            hasInFlightNextEpisodeAutoPlay = nextEpisodeAutoPlayJob?.isActive == true
        )
    ) {
        return
    }
    resetPostPlayOverlayState(clearEpisode = false)
}

internal fun shouldResetPostPlayStateAfterPlaybackEnded(
    state: PlayerUiState,
    hasInFlightNextEpisodeAutoPlay: Boolean
): Boolean {
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return false
    if (hasInFlightNextEpisodeAutoPlay) return false
    return true
}

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            if (isUsingMpvEngine()) {
                val view = mpvView
                if (view != null) {
                    val pos = view.currentPositionMs().coerceAtLeast(0L)
                    val playerDuration = view.durationMs().coerceAtLeast(0L)
                    applyPendingMpvSeekIfNeeded(
                        view = view,
                        currentPositionMs = pos,
                        durationMs = playerDuration
                    )
                    val playingNow = view.isPlayingNow()
                    val cacheBuffering = view.isPausedForCacheNow() || view.isCoreIdleNow()
                    var firstFrameReady = hasRenderedFirstFrame
                    if (!firstFrameReady) {
                        firstFrameReady = pos > 0L || (playingNow && !cacheBuffering && playerDuration > 0L)
                        if (firstFrameReady) {
                            hasRenderedFirstFrame = true
                        }
                    }
                    if (playerDuration > lastKnownDuration) {
                        lastKnownDuration = playerDuration
                    }
                    val displayPosition = pendingPreviewSeekPosition ?: pos
                    updatePlaybackTimeline(
                        currentPosition = displayPosition,
                        duration = playerDuration
                    )
                    val ended = playerDuration > 0L && pos >= (playerDuration - 500L)
                    val wasEnded = _uiState.value.playbackEnded
                    _uiState.update { state ->
                        state.copy(
                            isPlaying = playingNow,
                            isBuffering = !firstFrameReady || cacheBuffering,
                            showLoadingOverlay = if (state.loadingOverlayEnabled) !firstFrameReady else false,
                            // Snap the loading-logo fill to 100% once playback is
                            // ready so the logo finishes filling on dismissal.
                            loadingProgress = if (firstFrameReady && state.loadingProgress != null) 1f else state.loadingProgress,
                            playbackEnded = ended
                        )
                    }
                    updateMpvAvailableTracks()
                    tryAutoSelectPreferredSubtitleFromAvailableTracks()
                    updateActiveSkipInterval(pos)
                    evaluatePostPlayOverlayVisibility(
                        positionMs = pos,
                        durationMs = playerDuration
                    )
                    if (ended && !wasEnded) {
                        emitCompletionScrobbleStop(progressPercent = 99.5f)
                        saveWatchProgress()
                        resetPostPlayStateAfterPlaybackEnded()
                    }
                }
                delay(500)
                continue
            }

            _exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playerDuration = player.duration
                if (playerDuration > lastKnownDuration) {
                    lastKnownDuration = playerDuration
                }
                val displayPosition = pendingPreviewSeekPosition ?: pos
                updatePlaybackTimeline(
                    currentPosition = displayPosition,
                    duration = playerDuration.coerceAtLeast(0L)
                )
                // Update torrent rebuffer progress from ExoPlayer's buffer state
                if (isTorrentStream && _uiState.value.isBuffering && hasRenderedFirstFrame) {
                    val bufferedAheadMs = (player.bufferedPosition - pos).coerceAtLeast(0)
                    val bufferedSec = bufferedAheadMs / 1000f
                    val statsHidden = _uiState.value.hideTorrentStats
                    val message = if (statsHidden) {
                        null
                    } else {
                        val speed = formatTorrentSpeed(_uiState.value.torrentDownloadSpeed)
                        val peerInfo = "${_uiState.value.torrentSeeds} seeds \u00B7 ${_uiState.value.torrentPeers} peers"
                        val bufLabel = String.format("%.0fs", bufferedSec)
                        "$bufLabel buffered \u00B7 $peerInfo \u00B7 $speed"
                    }
                    val progress = (bufferedSec / 10f).coerceIn(0f, 1f)
                    _uiState.update {
                        it.copy(
                            torrentBufferingMessage = message,
                            torrentBufferingProgress = progress
                        )
                    }
                }
                updateActiveSkipInterval(pos)
                evaluatePostPlayOverlayVisibility(
                    positionMs = pos,
                    durationMs = playerDuration.coerceAtLeast(0L)
                )

                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastBufferLogTimeMs >= 10_000) {
                        lastBufferLogTimeMs = now
                        val bufAhead = (player.bufferedPosition - player.currentPosition) / 1000
                        val loading = player.isLoading
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        val maxMb = runtime.maxMemory() / (1024 * 1024)
                        Log.d(PlayerRuntimeController.TAG, "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=$usedMb/${maxMb}MB, pos=${pos / 1000}s")
                    }
                }
            }
            delay(500)
        }
    }
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = scope.launch {
        while (isActive) {
            delay(10000)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    // Don't save progress for very short streams (< 2:01) — these are
    // typically error/warning messages or "stream not ready" placeholders that
    // would incorrectly mark content as watched when the user exits.
    if (isShortPlaceholderDuration(duration)) return

    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (isShortPlaceholderDuration(duration)) return
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = currentPlaybackDurationMs()
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = if (isUsingMpvEngine()) {
        position >= (effectiveDuration - 500L)
    } else {
        _exoPlayer?.playbackState == Player.STATE_ENDED
    }
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

private fun isShortPlaceholderDuration(duration: Long) = duration in 1..120999

private fun PlayerRuntimeController.isShortPlaceholderStream(): Boolean {
    val position = currentPlaybackPositionMs() ?: return false
    return isShortPlaceholderDuration(getEffectiveDuration(position))
}

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    if (contentId.isNullOrEmpty() || contentType.isNullOrEmpty()) return

    if (position < 1000) return

    val fallbackPercent = if (duration <= 0L) 5f else null

    val progress = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = contentName ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = currentVideoId ?: contentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = position,
        duration = duration,
        lastWatched = System.currentTimeMillis(),
        progressPercent = fallbackPercent
    )

    scope.launch(kotlinx.coroutines.NonCancellable) {
        watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)
    }
}

internal fun PlayerRuntimeController.currentPlaybackProgressPercent(): Float {
    if (!hasRenderedFirstFrame) return 0f
    val position = currentPlaybackPositionMs() ?: return 0f
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: lastKnownDuration
    if (duration <= 0L) return 0f
    return ((position.toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
}

internal fun PlayerRuntimeController.refreshScrobbleItem() {
    currentScrobbleItem = buildScrobbleItem()
    hasSentScrobbleStartForCurrentItem = false
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration++
    hasSentCompletionScrobbleForCurrentItem = false
}

internal fun PlayerRuntimeController.buildScrobbleItem(): TraktScrobbleItem? {
    val rawContentId = contentId ?: return null
    val parsedIds = parseContentIds(rawContentId)
    val ids = toTraktIds(parsedIds)
    val parsedYear = extractYear(year)
    val normalizedType = contentType?.lowercase()
    val currentMappingKey = currentEpisodeMappingCacheKey()
    val mappedEpisode = if (currentTraktEpisodeMappingKey == currentMappingKey) {
        currentTraktEpisodeMapping
    } else {
        null
    }
    val effectiveSeason = mappedEpisode?.season ?: currentSeason
    val effectiveEpisode = mappedEpisode?.episode ?: currentEpisode

    val isEpisode = normalizedType in listOf("series", "tv") &&
        effectiveSeason != null && effectiveEpisode != null

    val item = if (isEpisode) {
        TraktScrobbleItem.Episode(
            showTitle = contentName ?: title,
            showYear = parsedYear,
            showIds = ids,
            season = effectiveSeason ?: return null,
            number = effectiveEpisode ?: return null,
            episodeTitle = currentEpisodeTitle
        )
    } else {
        TraktScrobbleItem.Movie(
            title = contentName ?: title,
            year = parsedYear,
            ids = ids
        )
    }
    return item
}

internal fun PlayerRuntimeController.emitScrobbleStart() {
    if (isShortPlaceholderStream()) return
    if (hasRequestedScrobbleStartForCurrentItem) return

    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = ++scrobbleStartRequestGeneration
    scope.launch {
        // Wait for the episode mapping to finish (with its own timeout) so that
        // the scrobble start is sent with the correct season/episode number.
        traktMappingJob?.join()
        refreshScrobbleItem()
        val item = currentScrobbleItem ?: return@launch
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) return@launch
        val progressPercent = currentPlaybackProgressPercent()
        traktScrobbleService.scrobbleStart(
            item = item,
            progressPercent = progressPercent
        )
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) return@launch
        hasSentScrobbleStartForCurrentItem = true
    }
}

internal fun PlayerRuntimeController.emitScrobbleStop(progressPercent: Float? = null) {
    if (isShortPlaceholderStream()) return
    val item = currentScrobbleItem
    if (item == null) return

    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

    val percent = provided ?: currentPlaybackProgressPercent()
    scope.launch(kotlinx.coroutines.NonCancellable) {
        traktScrobbleService.scrobbleStop(
            item = item,
            progressPercent = percent
        )
    }
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitPauseScrobbleStop(progressPercent: Float) {
    if (progressPercent < 1f || progressPercent >= 80f) return
    if (isShortPlaceholderStream()) return
    val item = currentScrobbleItem
    if (item == null) return
    if (!hasRequestedScrobbleStartForCurrentItem) return

    scope.launch(kotlinx.coroutines.NonCancellable) {
        traktScrobbleService.scrobbleStop(
            item = item,
            progressPercent = progressPercent
        )
    }
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress() {
    val progressPercent = currentPlaybackProgressPercent()
    emitPauseScrobbleStop(progressPercent = progressPercent)
    emitCompletionScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    emitStopScrobbleForCurrentProgress()
    saveWatchProgress()
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()

        val progressPercent = currentPlaybackProgressPercent()
        emitPauseScrobbleStop(progressPercent = progressPercent)

        if (isPlaybackCurrentlyPlaying() && progressPercent >= 1f && progressPercent < 80f) {
            emitScrobbleStart()
        }
    }
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        delay(3000)
        if (_uiState.value.isPlaying && !_uiState.value.showAudioOverlay &&
            !_uiState.value.showSubtitleOverlay && !_uiState.value.showSubtitleStylePanel &&
            !_uiState.value.showSpeedDialog && !_uiState.value.showMoreDialog &&
            !_uiState.value.showSubtitleDelayOverlay &&
            !_uiState.value.showSubtitleTimingDialog &&
            !_uiState.value.showEpisodesPanel && !_uiState.value.showSourcesPanel &&
            !_uiState.value.showStreamInfoOverlay) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    adjustSubtitleDelay(deltaMs = deltaMs, showOverlay = true)
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int, showOverlay: Boolean) {
    val currentState = _uiState.value
    val currentDelayMs = currentState.subtitleDelayMs
    val newDelayMs = (currentDelayMs + deltaMs).coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )
    val keepInlineInSubtitleOverlay = showOverlay && currentState.showSubtitleOverlay

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(newDelayMs)
    }
    if (showOverlay) {
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showControls = if (keepInlineInSubtitleOverlay) it.showControls else false,
                showSubtitleDelayOverlay = if (keepInlineInSubtitleOverlay) false else true
            )
        }
    } else {
        hideSubtitleDelayOverlayJob?.cancel()
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showSubtitleDelayOverlay = false,
                showControls = true
            )
        }
    }

    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .build()
    }
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()

    if (!showOverlay || keepInlineInSubtitleOverlay) {
        hideSubtitleDelayOverlayJob?.cancel()
        hideSubtitleDelayOverlayJob = null
    } else {
        scheduleHideSubtitleDelayOverlay()
    }
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    pauseOverlayJob?.cancel()

    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val s = _uiState.value
        val anyPanelOpen = s.showSubtitleOverlay || s.showSubtitleStylePanel ||
            s.showSpeedDialog || s.showMoreDialog || s.showEpisodesPanel ||
            s.showSourcesPanel || s.showAudioOverlay || s.showStreamInfoOverlay ||
            s.showSubtitleTimingDialog
        if (!s.isPlaying && s.pauseOverlayEnabled && s.error == null && !anyPanelOpen) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
}

fun PlayerRuntimeController.onUserInteraction() {
    if (_uiState.value.showPauseOverlay) {
        cancelPauseOverlay()
        showControlsTemporarily()
    } else if (pauseOverlayJob != null && !_uiState.value.isPlaying && userPausedManually) {
        schedulePauseOverlay()
    }
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false, showMoreDialog = false) }
}

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    onUserInteraction()
    when (event) {
        PlayerEvent.OnPlayPause -> {
            if (isUsingMpvEngine()) {
                val playing = isPlaybackCurrentlyPlaying()
                if (playing) {
                    userPausedManually = true
                    setPlaybackPaused(true)
                    stopProgressUpdates()
                    stopWatchProgressSaving()
                    emitStopScrobbleForCurrentProgress()
                    schedulePauseOverlay()
                } else {
                    userPausedManually = false
                    cancelPauseOverlay()
                    setPlaybackPaused(false)
                    startProgressUpdates()
                    startWatchProgressSaving()
                    scheduleHideControls()
                    emitScrobbleStart()
                }
            } else {
                _exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        userPausedManually = true
                        player.pause()
                        schedulePauseOverlay()
                    } else {
                        userPausedManually = false
                        cancelPauseOverlay()
                        player.play()
                    }
                }
            }
            showControlsTemporarily()
        }
        PlayerEvent.OnSeekForward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = 10_000L))
        }
        PlayerEvent.OnSeekBackward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = -10_000L))
        }
        is PlayerEvent.OnSeekBy -> {
            pendingPreviewSeekPosition = null
            val current = currentPlaybackPositionMs() ?: 0L
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val target = (current + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            seekPlaybackTo(target)
            updatePlaybackTimeline(currentPosition = target)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnPreviewSeekBy -> {
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val basePosition = pendingPreviewSeekPosition ?: currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
            val target = (basePosition + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            pendingPreviewSeekPosition = target
            updatePlaybackTimeline(currentPosition = target)
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            val target = pendingPreviewSeekPosition
            if (target != null) {
                seekPlaybackTo(target)
                updatePlaybackTimeline(currentPosition = target)
                pendingPreviewSeekPosition = null
                scheduleProgressSyncAfterSeek()
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSeekTo -> {
            pendingPreviewSeekPosition = null
            seekPlaybackTo(event.position)
            updatePlaybackTimeline(currentPosition = event.position)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            logSwitchTrace(
                stage = "event-select-audio",
                message = "index=${event.index}"
            )
            rememberAudioSelection(event.index)
            selectAudioTrack(event.index)
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleDelayOverlay = false,
                    showSubtitleTimingDialog = false
                )
            }
        }
        is PlayerEvent.OnSetAudioDelayMs -> {
            applyAudioDelay(event.delayMs)
        }
        is PlayerEvent.OnSetAudioAmplificationDb -> {
            val clampedDb = event.db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
            applyAudioAmplification(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setAudioAmplificationDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSetPersistAudioAmplification -> {
            val currentDb = _uiState.value.audioAmplificationDb
            val currentCenterMixDb = _uiState.value.centerMixLevelDb
            _uiState.update { it.copy(persistAudioAmplification = event.enabled) }
            scope.launch {
                playerSettingsDataStore.setPersistAudioAmplification(
                    enabled = event.enabled,
                    dbToPersist = if (event.enabled) currentDb else null,
                    centerMixDbToPersist = if (event.enabled) currentCenterMixDb else null
                )
            }
        }
        is PlayerEvent.OnSetCenterMixLevelDb -> {
            val clampedDb = event.db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
            applyCenterMixLevel(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setCenterMixLevelDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSelectSubtitleTrack -> {
            logSwitchTrace(
                stage = "event-select-subtitle-internal",
                message = "index=${event.index}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberInternalSubtitleSelection(event.index)
            selectSubtitleTrack(event.index)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null
                )
            }
        }
        PlayerEvent.OnDisableSubtitles -> {
            logSwitchTrace(
                stage = "event-disable-subtitles",
                message = "selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberSubtitleDisabled()
            disableSubtitles()
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                )
            }
        }
        is PlayerEvent.OnSelectAddonSubtitle -> {
            logSwitchTrace(
                stage = "event-select-subtitle-addon",
                message = "addonId=${event.subtitle.id} addonLang=${event.subtitle.lang} addonName=${event.subtitle.addonName}"
            )
            autoSubtitleSelected = true
            rememberAddonSubtitleSelection(event.subtitle)
            selectAddonSubtitle(event.subtitle)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
            if (isUsingMpvEngine()) {
                setPlaybackSpeedInternal(event.speed)
            } else {
                _exoPlayer?.let { player ->
                    player.setPlaybackSpeed(event.speed)
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .build()
                }
            }
            _uiState.update {
                it.copy(
                    playbackSpeed = event.speed,
                    showSpeedDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
        }
        PlayerEvent.OnToggleControls -> {
            if (_uiState.value.showSubtitleTimingDialog) {
                dismissSubtitleTimingDialog()
            }
            if (_uiState.value.showSubtitleDelayOverlay) {
                hideSubtitleDelayOverlay()
            }
            val shouldShowControls = !_uiState.value.showControls
            _uiState.update {
                it.copy(
                    showControls = shouldShowControls,
                    showSeekOverlay = false,
                    showMoreDialog = if (shouldShowControls) it.showMoreDialog else false
                )
            }
            if (shouldShowControls) {
                scheduleHideControls()
            }
        }
        PlayerEvent.OnShowAudioOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = true,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleOverlay -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showAudioOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnOpenSubtitleStylePanel -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = true,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissSubtitleStylePanel -> {
            _uiState.update { it.copy(showSubtitleStylePanel = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowSubtitleTimingDialog -> {
            showSubtitleTimingDialog()
        }
        PlayerEvent.OnDismissSubtitleTimingDialog -> {
            dismissSubtitleTimingDialog()
        }
        PlayerEvent.OnCaptureSubtitleAutoSyncTime -> {
            captureSubtitleAutoSyncTime()
        }
        is PlayerEvent.OnApplySubtitleAutoSyncCue -> {
            applySubtitleAutoSyncCue(event.cueStartTimeMs)
        }
        PlayerEvent.OnReloadSubtitleAutoSyncCues -> {
            reloadSubtitleAutoSyncCues()
        }
        PlayerEvent.OnShowSubtitleDelayOverlay -> {
            showSubtitleDelayOverlay()
        }
        PlayerEvent.OnHideSubtitleDelayOverlay -> {
            hideSubtitleDelayOverlay()
        }
        is PlayerEvent.OnAdjustSubtitleDelay -> {
            adjustSubtitleDelay(event.deltaMs, event.showOverlay)
        }
        PlayerEvent.OnShowSpeedDialog -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            _uiState.update {
                it.copy(
                    showSpeedDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowMoreDialog -> {
            _uiState.update {
                it.copy(
                    showMoreDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showSpeedDialog = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> {
            showEpisodesPanel()
        }
        PlayerEvent.OnDismissEpisodesPanel -> {
            dismissEpisodesPanel()
        }
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false
                )
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> {
            selectEpisodesSeason(event.season)
        }
        is PlayerEvent.OnEpisodeSelected -> {
            loadStreamsForEpisode(event.video)
        }
        PlayerEvent.OnReloadEpisodeStreams -> {
            reloadEpisodeStreams()
        }
        is PlayerEvent.OnEpisodeAddonFilterSelected -> {
            filterEpisodeStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnEpisodeStreamSelected -> {
            switchToEpisodeStream(event.stream)
        }
        PlayerEvent.OnShowSourcesPanel -> {
            showSourcesPanel()
        }
        PlayerEvent.OnDismissSourcesPanel -> {
            dismissSourcesPanel()
        }
        PlayerEvent.OnReloadSourceStreams -> {
            loadSourceStreams(forceRefresh = true)
        }
        is PlayerEvent.OnSourceAddonFilterSelected -> {
            filterSourceStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnSourceStreamSelected -> {
            switchToSourceStream(event.stream)
        }
        PlayerEvent.OnDismissTransientOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false,
                    showMoreDialog = false
                )
            }
            scheduleHideControls()
        }
        PlayerEvent.OnRetry -> {
            hasRenderedFirstFrame = false
            hasRetriedCurrentStreamAfter416 = false
            resetErrorRetryState()
            clearPendingEngineSwitchTrackPreference()
            resetPostPlayOverlayState(clearEpisode = false)
            _uiState.update { state ->
                state.copy(
                    error = null,
                    showLoadingOverlay = state.loadingOverlayEnabled,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
            if (isTorrentStream && currentInfoHash != null) {
                releasePlayer()
                stopTorrentStream()
                launchTorrentSourceStream(
                    stream = com.nuvio.tv.domain.model.Stream(
                        name = _uiState.value.currentStreamName,
                        title = null,
                        description = null,
                        url = null,
                        ytId = null,
                        infoHash = currentInfoHash,
                        fileIdx = currentFileIdx,
                        externalUrl = null,
                        behaviorHints = null,
                        addonName = currentAddonName ?: "",
                        addonLogo = currentAddonLogo
                    ),
                    infoHash = currentInfoHash!!,
                    loadSavedProgress = true
                )
            } else {
                releasePlayer()
                initializePlayer(currentStreamUrl, currentHeaders)
            }
        }
        PlayerEvent.OnParentalGuideHide -> {
            _uiState.update { it.copy(showParentalGuide = false) }
        }
        PlayerEvent.OnToggleTorrentStats -> {
            _uiState.update { it.copy(showTorrentStats = !it.showTorrentStats) }
        }
        is PlayerEvent.OnShowDisplayModeInfo -> {
            _uiState.update {
                it.copy(
                    displayModeInfo = event.info,
                    showDisplayModeInfo = true
                )
            }
        }
        PlayerEvent.OnHideDisplayModeInfo -> {
            _uiState.update { it.copy(showDisplayModeInfo = false) }
        }
        PlayerEvent.OnDismissPauseOverlay -> {
            cancelPauseOverlay()
        }
        PlayerEvent.OnSkipIntro -> {
            skipActiveInterval()
        }
        PlayerEvent.OnDismissSkipIntro -> {
            _uiState.update { it.copy(skipIntervalDismissed = true) }
        }
        PlayerEvent.OnPlayNextEpisode -> {
            playNextEpisode(userInitiated = true)
        }
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
        }
        PlayerEvent.OnStillWatchingContinue -> onStillWatchingContinue()
        PlayerEvent.OnDismissStillWatchingPrompt -> onDismissStillWatchingPrompt()
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleTextColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleTextColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        }
        is PlayerEvent.OnSetSubtitleOutlineEnabled -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineEnabled(event.enabled) }
        }
        is PlayerEvent.OnSetSubtitleOutlineColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> {
            scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        }
        PlayerEvent.OnResetSubtitleDefaults -> {
            scope.launch {
                val defaults = SubtitleStyleSettings()
                playerSettingsDataStore.setSubtitleSize(defaults.size)
                playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
                playerSettingsDataStore.setSubtitleBold(defaults.bold)
                playerSettingsDataStore.setSubtitleOutlineEnabled(defaults.outlineEnabled)
                playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
                playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
                playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
                playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            val newMode = nextAspectMode(state.aspectMode)
            val label = aspectModeLabel(newMode, context::getString)
            Log.d(PlayerRuntimeController.TAG, "Aspect mode toggled by user: ${state.aspectMode} -> $newMode ($label)")
            _uiState.update {
                it.copy(
                    aspectMode = newMode,
                    showAspectRatioIndicator = true,
                    aspectRatioIndicatorText = label
                )
            }
            scope.launch {
                Log.d(PlayerRuntimeController.TAG, "Persisting aspect mode: $newMode")
                deviceLocalPlayerPreferences.setAspectMode(newMode)
            }
            hideAspectRatioIndicatorJob?.cancel()
            hideAspectRatioIndicatorJob = scope.launch {
                delay(1500)
                _uiState.update { it.copy(showAspectRatioIndicator = false) }
            }
        }
        PlayerEvent.OnSwitchInternalPlayerEngine -> {
            logSwitchTrace(
                stage = "event-switch-engine",
                message = "requestedByUser=true"
            )
            switchInternalPlayerEngineManually()
        }
        PlayerEvent.OnShowStreamInfo -> {
            val info = buildStreamInfoData()
            _uiState.update {
                it.copy(
                    showStreamInfoOverlay = true,
                    streamInfoData = info,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissStreamInfo -> {
            _uiState.update { it.copy(showStreamInfoOverlay = false) }
        }
    }
}

internal fun PlayerRuntimeController.buildStreamInfoData(): StreamInfoData {
    val state = _uiState.value
    val selectedAudio = state.audioTracks.firstOrNull { it.isSelected }
    val selectedSubtitle = state.subtitleTracks.firstOrNull { it.isSelected }
    val addonSub = state.selectedAddonSubtitle

    return StreamInfoData(
        addonName = currentAddonName,
        addonLogo = currentAddonLogo,
        streamName = state.currentStreamName,
        streamDescription = currentStreamDescription,
        filename = currentFilename,
        fileSize = currentVideoSize,
        videoCodec = currentVideoCodec,
        videoWidth = currentVideoWidth,
        videoHeight = currentVideoHeight,
        videoFrameRate = state.detectedFrameRate.takeIf { it > 0f },
        videoBitrate = currentVideoBitrate,
        audioCodec = selectedAudio?.codec,
        audioChannels = selectedAudio?.channelCount?.let {
            CustomDefaultTrackNameProvider.getChannelLayoutName(it)
        },
        audioSampleRate = selectedAudio?.sampleRate,
        audioLanguage = selectedAudio?.language,
        subtitleName = selectedSubtitle?.name ?: addonSub?.lang,
        subtitleCodec = selectedSubtitle?.codec,
        subtitleLanguage = selectedSubtitle?.language ?: addonSub?.lang,
        subtitleSource = when {
            addonSub != null -> context.getString(R.string.stream_info_subtitle_source_addon)
            selectedSubtitle != null -> context.getString(R.string.stream_info_subtitle_source_embedded)
            else -> null
        },
        playerEngine = when (currentInternalPlayerEngine) {
            com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
            com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
            com.nuvio.tv.data.local.InternalPlayerEngine.AUTO -> null
        }
    )
}

private fun formatTorrentSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}

internal fun PlayerRuntimeController.triggerLateAfr(fps: Float, width: Int? = null, height: Int? = null) {
    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Called - afrPendingNativeFallback=$afrPendingNativeFallback, fps=$fps, lateAfrInProgress=$lateAfrInProgress")
    if (!afrPendingNativeFallback || fps <= 0f) {
        Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Skipped - afrPendingNativeFallback=$afrPendingNativeFallback, fps=$fps")
        return
    }
    if (lateAfrInProgress) {
        Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Skipped - already in progress")
        return
    }
    val currentDetection = _uiState.value.detectedFrameRate
    if (currentDetection > 0f) {
        if (kotlin.math.abs(currentDetection - fps) < 0.1f) {
            Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Skipped - FPS already matched (current=$currentDetection, new=$fps)")
            return
        }
    }

    scope.launch {
        lateAfrInProgress = true
        try {
            afrPendingNativeFallback = false
            Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Starting coroutine - fps=$fps, size=${width}x${height}")
            val activity = currentHostActivity() ?: return@launch
            val settings = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                playerSettingsDataStore.playerSettings.first()
            }
            if (settings == null) {
                Log.w(PlayerRuntimeController.TAG, "[AFR LATE] Failed to retrieve settings within timeout")
                return@launch
            }
            if (settings.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.OFF) {
                Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Skipped - AFR disabled in settings")
                return@launch
            }

            Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Starting Safe Late-AFR sequence for ${fps}fps size=${width}x${height}")

            // Update UI to show display mode switching
            _uiState.update {
                it.copy(
                    loadingMessage = activity.getString(R.string.obtaining_frame_rate),
                    loadingProgress = 0.6f
                )
            }

            val wasPlaying = isPlaybackCurrentlyPlaying()
            val resumePos = currentPlaybackPositionMs() ?: 0L
            var switchedDisplayMode = false

            try {
                // 1. PAUSE: Freeze the buffer and decoder to prevent crashes during mode change
                Log.d(PlayerRuntimeController.TAG, "[AFR LATE] PAUSE - wasPlaying=$wasPlaying, resumePos=$resumePos")
                setPlaybackPaused(true)

                // TRACK KILLER: Disable video track to force MediaCodecVideoRenderer destruction
                // This completely destroys the MediaCodecVideoRenderer safely, keeping the network buffer intact.
                if (currentInternalPlayerEngine == InternalPlayerEngine.EXOPLAYER) {
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] TRACK KILLER - Disabling video track")
                    val currentParams = _exoPlayer?.trackSelectionParameters
                    if (currentParams != null) {
                        _exoPlayer?.trackSelectionParameters = currentParams
                            .buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            .build()
                    } else {
                        Log.w(PlayerRuntimeController.TAG, "[AFR LATE] TRACK KILLER - trackSelectionParameters is null, skipping")
                    }
                }

                // Capture display mode BEFORE the switch to detect if it actually changed
                val initialDisplayModeId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    withContext(Dispatchers.Main) {
                        activity.window?.decorView?.display?.mode?.modeId
                    }
                } else {
                    null
                }
                Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Initial display mode ID: $initialDisplayModeId")

                val prefer23976Near24 = fps in 23.95f..24.12f
                val targetFrameRate = com.nuvio.tv.core.player.FrameRateUtils.refineFrameRateForDisplay(
                    activity = activity,
                    detectedFps = com.nuvio.tv.core.player.FrameRateUtils.snapToStandardRate(fps),
                    prefer23976Near24 = (prefer23976ProbeBias ?: prefer23976Near24)
                )
                Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Target frame rate: $targetFrameRate (prefer23976=$prefer23976Near24)")

                // 2. SWITCH: Execute HDMI handshake
                Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Calling matchFrameRateAndWait")
                val result = com.nuvio.tv.core.player.FrameRateUtils.matchFrameRateAndWait(
                    activity = activity,
                    frameRate = targetFrameRate,
                    videoWidth = width,
                    videoHeight = height,
                    resolutionMatchingEnabled = settings.resolutionMatchingEnabled
                )

                // 3. RECOVERY: Delay for HDMI sync
                if (result != null) {
                    switchedDisplayMode = initialDisplayModeId != null &&
                        initialDisplayModeId != result.appliedMode.modeId
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Display mode switched=$switchedDisplayMode, new mode ID: ${result.appliedMode.modeId}")

                    if (switchedDisplayMode) {
                        delay(1000) // Protective delay for HDMI sync
                    }

                    // TRACK KILLER: Re-enable video track to force MediaCodec recreation with new refresh rate
                    // Re-enabling the video track forces ExoPlayer to allocate a BRAND NEW MediaCodec.
                    // Since the display is now at the correct target Hz, the DV handshake will succeed.
                    if (currentInternalPlayerEngine == InternalPlayerEngine.EXOPLAYER) {
                        Log.d(PlayerRuntimeController.TAG, "[AFR LATE] TRACK KILLER - Re-enabling video track")
                        delay(200) // Brief stabilization wait
                        val currentParams = _exoPlayer?.trackSelectionParameters
                        if (currentParams != null) {
                            _exoPlayer?.trackSelectionParameters = currentParams
                                .buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                .build()
                        } else {
                            Log.w(PlayerRuntimeController.TAG, "[AFR LATE] TRACK KILLER - trackSelectionParameters is null, skipping re-enable")
                        }
                    }

                    _uiState.update {
                        it.copy(
                            detectedFrameRateRaw = fps,
                            detectedFrameRate = targetFrameRate,
                            detectedFrameRateSource = FrameRateSource.TRACK,
                            displayModeInfo = DisplayModeInfo(
                                width = result.appliedMode.physicalWidth,
                                height = result.appliedMode.physicalHeight,
                                refreshRate = result.appliedMode.refreshRate
                            ),
                            showDisplayModeInfo = true
                        )
                    }
                } else {
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] matchFrameRateAndWait returned null")
                }
            } finally {
                // FINALLY BLOCK: Guaranteed to run even if coroutine is cancelled or fails

                // 1. Re-enable Video Track (Triggers DV Handshake)
                if (currentInternalPlayerEngine == InternalPlayerEngine.EXOPLAYER) {
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - Re-enabling video track")
                    val currentParams = _exoPlayer?.trackSelectionParameters
                    if (currentParams != null) {
                        _exoPlayer?.trackSelectionParameters = currentParams
                            .buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                            .build()
                    }
                }

                // 2. Restore Audio (always restore to 1.0 since ExoPlayer volume is always at max)
                Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - Restoring audio volume to 1.0")
                _exoPlayer?.volume = 1f

                // 3. Seek and Resume (if player is still valid and mode actually changed)
                if (switchedDisplayMode) {
                    val duration = currentPlaybackDurationMs()
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - Duration=$duration, will seek=$duration>0")
                    if (duration > 0L) {
                        Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - SEEK to ${maxOf(0L, resumePos - 500L)}")
                        seekPlaybackTo(maxOf(0L, resumePos - 500L))
                    } else {
                        Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - SKIP seek (live stream or unknown duration)")
                    }
                } else {
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - No display mode change, skipping seek")
                }

                if (wasPlaying) {
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - RESUME playback")
                    setPlaybackPaused(false)
                } else {
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - Not resuming (was not playing)")
                }

                // 4. Hide Loading Overlay (inside finally to ensure it happens after state restoration)
                if (waitingForLateAfrSwitch) {
                    waitingForLateAfrSwitch = false
                    Log.d(PlayerRuntimeController.TAG, "[AFR LATE] FINALLY - Hiding loading overlay after display switch")
                    _uiState.update {
                        it.copy(
                            showLoadingOverlay = false,
                            loadingMessage = null,
                            loadingProgress = if (it.loadingProgress != null) 1f else null
                        )
                    }
                }
            }
        } finally {
            lateAfrInProgress = false
            // Restore audio if Late AFR was skipped
            _exoPlayer?.volume = 1f
            // Reset waiting flag if Late AFR failed or was skipped
            if (waitingForLateAfrSwitch) {
                waitingForLateAfrSwitch = false
                Log.w(PlayerRuntimeController.TAG, "[AFR LATE] Late AFR failed/skipped, hiding loading overlay")
                _uiState.update {
                    it.copy(
                        showLoadingOverlay = false,
                        loadingMessage = null,
                        loadingProgress = if (it.loadingProgress != null) 1f else null
                    )
                }
            }
            Log.d(PlayerRuntimeController.TAG, "[AFR LATE] Completed")
        }
    }
}

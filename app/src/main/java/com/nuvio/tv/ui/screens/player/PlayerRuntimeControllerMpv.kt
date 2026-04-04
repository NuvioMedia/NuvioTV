package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MPV_RESUME_SEEK_TOLERANCE_MS = 1500L

internal fun PlayerRuntimeController.attachMpvView(view: NuvioMpvSurfaceView?) {
    if (mpvView === view) return
    mpvView = view

    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (mpvInitializationInProgress) return

    runCatching {
        performPendingMpvHardRestartIfNeeded(view)
        view.setMedia(currentStreamUrl, currentHeaders)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        view.setPaused(false)
        applyPendingMpvSeekIfNeeded(view)
        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure {
        val detailedError = it.message ?: "Failed to initialize libmpv surface"
        if (
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = true
            )
        ) {
            return@onFailure
        }
        _uiState.update { state ->
            state.copy(
                error = detailedError,
                showLoadingOverlay = false
            )
        }
    }
}

internal fun PlayerRuntimeController.initializeMpvPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true
) {
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    try {
        currentMediaSession?.release()
    } catch (_: Exception) {
    }
    currentMediaSession = null
    notifyAudioSessionUpdate(false)

    val view = mpvView
    if (view == null) {
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        performPendingMpvHardRestartIfNeeded(view)
        view.setMedia(url, headers)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        view.setPaused(false)
        applyPendingMpvSeekIfNeeded(view)

        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "libmpv initialize failed: ${error.message}", error)
        val detailedError = error.message ?: "Failed to initialize libmpv playback"
        if (
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = allowEngineFailover
            )
        ) {
            return@onFailure
        }
        _uiState.update {
            it.copy(
                error = detailedError,
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseMpvPlayer() {
    runCatching { mpvView?.releasePlayer() }
}

internal fun PlayerRuntimeController.pauseForLifecycle() {
    if (isUsingMpvEngine()) {
        mpvView?.setPaused(true)
        stopWatchProgressSaving()
        stopProgressUpdates()
        _uiState.update { it.copy(isPlaying = false) }
        return
    }
    _exoPlayer?.pause()
}

internal fun PlayerRuntimeController.updateMpvAvailableTracks() {
    if (!isUsingMpvEngine()) return
    val snapshot = mpvView?.readTrackSnapshot() ?: return

    val audioTracks = snapshot.audioTracks
        .mapIndexed { index, track ->
            val codecSuffix = buildList {
                track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            }.joinToString(" ")
            val displayName = if (codecSuffix.isBlank()) {
                track.name
            } else {
                "${track.name} ($codecSuffix)"
            }
            TrackInfo(
                index = index,
                name = displayName,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                channelCount = track.channelCount,
                isSelected = track.isSelected
            )
        }

    val internalSubtitleTracks = snapshot.subtitleTracks
        .filterNot { it.isExternal }
        .mapIndexed { index, track ->
            TrackInfo(
                index = index,
                name = track.name,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                isForced = track.isForced,
                isSelected = track.isSelected
            )
        }

    val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }
    val selectedSubtitleIndex = internalSubtitleTracks.indexOfFirst { it.isSelected }
    val selectedExternalSubtitleTrack = snapshot.subtitleTracks.firstOrNull { it.isExternal && it.isSelected }
    val selectedExternalSubtitle = selectedExternalSubtitleTrack != null

    hasScannedTextTracksOnce = true
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)

    _uiState.update { state ->
        val selectedAddonFromMpvTrack = selectedExternalSubtitleTrack?.let { track ->
            state.addonSubtitles.firstOrNull { subtitle ->
                buildAddonSubtitleTrackId(subtitle).equals(track.name, ignoreCase = true)
            }
        }

        val addonSelection = when {
            selectedAddonFromMpvTrack != null -> selectedAddonFromMpvTrack
            selectedExternalSubtitle -> null
            selectedSubtitleIndex >= 0 -> null
            else -> state.selectedAddonSubtitle
        }
        val normalizedSelectedSubtitleIndex = if (selectedExternalSubtitle) {
            -1
        } else {
            selectedSubtitleIndex
        }

        if (
            state.audioTracks == audioTracks &&
            state.subtitleTracks == internalSubtitleTracks &&
            state.selectedAudioTrackIndex == selectedAudioIndex &&
            state.selectedSubtitleTrackIndex == normalizedSelectedSubtitleIndex &&
            state.selectedAddonSubtitle == addonSelection
        ) {
            state
        } else {
            state.copy(
                audioTracks = audioTracks,
                subtitleTracks = internalSubtitleTracks,
                selectedAudioTrackIndex = selectedAudioIndex,
                selectedSubtitleTrackIndex = normalizedSelectedSubtitleIndex,
                selectedAddonSubtitle = addonSelection
            )
        }
    }
    applyPersistedTrackPreference(
        audioTracks = audioTracks,
        subtitleTracks = internalSubtitleTracks
    )
}

private fun PlayerRuntimeController.performPendingMpvHardRestartIfNeeded(view: NuvioMpvSurfaceView): Boolean {
    if (!pendingMpvHardRestartOnNextAttach) return false
    pendingMpvHardRestartOnNextAttach = false
    runCatching {
        Log.d(PlayerRuntimeController.TAG, "Applying MPV hard restart for startup engine failover")
        view.releasePlayer()
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "MPV hard restart release failed: ${it.message}")
    }
    return true
}

internal fun PlayerRuntimeController.applyPendingMpvSeekIfNeeded(
    view: NuvioMpvSurfaceView,
    currentPositionMs: Long = view.currentPositionMs().coerceAtLeast(0L),
    durationMs: Long = view.durationMs().coerceAtLeast(0L)
) {
    if (!isUsingMpvEngine()) return
    if (delayMpvResumeSeekUntilVideoTrack) {
        if (!view.hasVideoTrackSelectedNow()) return
        delayMpvResumeSeekUntilVideoTrack = false
    }

    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val queuedPosition = state.pendingSeekPosition ?: savedResume?.position
    if (queuedPosition == null) return
    if (queuedPosition <= 0L && savedResume == null) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePosition(durationMs).coerceAtLeast(0L)
        }
        savedResume != null -> {
            val requiresDurationForPercentResume = savedResume.progressPercent != null &&
                savedResume.duration <= 0L
            if (requiresDurationForPercentResume) {
                return
            }
            savedResume.position.coerceAtLeast(0L)
        }
        else -> queuedPosition.coerceAtLeast(0L)
    }

    if (target <= 0L) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val isAlreadyAtTarget = currentPositionMs >= target ||
        abs(currentPositionMs - target) <= MPV_RESUME_SEEK_TOLERANCE_MS
    if (isAlreadyAtTarget) {
        if (state.pendingSeekPosition != null || savedResume != null) {
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    view.seekToMs(target)
    if (state.pendingSeekPosition != target) {
        _uiState.update { it.copy(pendingSeekPosition = target) }
    }
}

internal fun PlayerRuntimeController.isUsingMpvEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
}

internal fun PlayerRuntimeController.currentPlaybackPositionMs(): Long? {
    return if (isUsingMpvEngine()) {
        mpvView?.currentPositionMs()
    } else {
        _exoPlayer?.currentPosition
    }
}

internal fun PlayerRuntimeController.currentPlaybackDurationMs(): Long {
    return if (isUsingMpvEngine()) {
        mpvView?.durationMs() ?: 0L
    } else {
        _exoPlayer?.duration ?: 0L
    }
}

internal fun PlayerRuntimeController.isPlaybackCurrentlyPlaying(): Boolean {
    return if (isUsingMpvEngine()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.isPlaying == true
    }
}

internal fun PlayerRuntimeController.seekPlaybackTo(positionMs: Long) {
    if (isUsingMpvEngine()) {
        mpvView?.let { view ->
            view.seekToMs(positionMs)
            // Keep subtitle delay sticky during FF/RW seeks.
            view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        }
    } else {
        _exoPlayer?.seekTo(positionMs)
    }
}

internal fun PlayerRuntimeController.setPlaybackSpeedInternal(speed: Float) {
    if (isUsingMpvEngine()) {
        mpvView?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.setPlaybackPaused(paused: Boolean) {
    if (isUsingMpvEngine()) {
        mpvView?.setPaused(paused)
        _uiState.update { it.copy(isPlaying = !paused) }
    } else {
        _exoPlayer?.let { player ->
            if (paused) player.pause() else player.play()
        }
    }
}

internal fun PlayerRuntimeController.keepMpvPlayingIfNeeded(wasPlaying: Boolean) {
    if (!wasPlaying || !isUsingMpvEngine()) return
    scope.launch {
        // If track switch forces a pause, nudge playback back only when needed.
        repeat(6) {
            if (!isUsingMpvEngine()) return@launch
            val view = mpvView ?: return@launch
            val pausedByCache = view.isPausedForCacheNow()
            val coreIdle = view.isCoreIdleNow()
            if (view.isPlayingNow() && !pausedByCache && !coreIdle) {
                _uiState.update { state ->
                    if (state.isPlaying) state else state.copy(isPlaying = true, isBuffering = false)
                }
                return@launch
            }
            view.setPaused(false)
            _uiState.update { it.copy(isPlaying = true, isBuffering = false) }
            delay(120L)
        }
    }
}

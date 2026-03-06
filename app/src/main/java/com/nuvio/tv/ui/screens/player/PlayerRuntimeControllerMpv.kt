package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.attachMpvView(view: NuvioMpvSurfaceView?) {
    if (mpvView === view) return
    mpvView = view

    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (mpvInitializationInProgress) return

    runCatching {
        view.setMedia(currentStreamUrl, currentHeaders)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setPaused(false)
        val pendingSeek = _uiState.value.pendingSeekPosition
            ?: pendingResumeProgress?.position
        if (pendingSeek != null && pendingSeek > 0L) {
            view.seekToMs(pendingSeek)
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }
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
        _uiState.update { state ->
            state.copy(
                error = it.message ?: "Failed to initialize libmpv surface",
                showLoadingOverlay = false
            )
        }
    }
}

internal fun PlayerRuntimeController.initializeMpvPlayer(url: String, headers: Map<String, String>) {
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    try {
        loudnessEnhancer?.release()
    } catch (_: Exception) {
    }
    loudnessEnhancer = null
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
        view.setMedia(url, headers)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setPaused(false)
        val pendingSeek = _uiState.value.pendingSeekPosition
            ?: pendingResumeProgress?.position
        if (pendingSeek != null && pendingSeek > 0L) {
            view.seekToMs(pendingSeek)
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }

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
        _uiState.update {
            it.copy(
                error = error.message ?: "Failed to initialize libmpv playback",
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
    maybeApplyRememberedAudioSelection(audioTracks)
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

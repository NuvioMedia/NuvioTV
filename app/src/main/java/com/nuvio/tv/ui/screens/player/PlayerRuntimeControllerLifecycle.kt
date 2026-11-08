package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.releasePlayer() {
    releasePlayer(flushPlaybackState = true)
}

// A rebuild on the same stream passes keepTorrentStream: currentStreamUrl is then the torrent
// engine's local url, which stops serving once the torrent stream is stopped.
internal fun PlayerRuntimeController.releasePlayer(
    flushPlaybackState: Boolean,
    keepTorrentStream: Boolean = false
) {
    logScrobbleDiagnostic("release_player", "flushPlaybackState=$flushPlaybackState")

    // Preserve current subtitle selection across any rebuild. When the user explicitly
    // selected a track, persistedTrackPreference was nulled and the choice lives only
    // in the UI state. Capture it so applyPersistedTrackPreference restores it.
    if (persistedTrackPreference == null) {
        val currentSubIdx = _uiState.value.selectedSubtitleTrackIndex
        val currentAddon = _uiState.value.selectedAddonSubtitle
        val currentTracks = _uiState.value.subtitleTracks
        val currentSubTrack = currentTracks.getOrNull(currentSubIdx)
        if (currentSubTrack != null) {
            persistedTrackPreference = PlayerRuntimeController.TrackPreference(
                subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                    track = PlayerRuntimeController.RememberedTrackSelection(
                        language = currentSubTrack.language,
                        name = currentSubTrack.name,
                        indexHint = currentSubIdx,
                        isForcedHint = currentSubTrack.isForced
                    )
                )
            )
        } else if (currentAddon != null) {
            persistedTrackPreference = PlayerRuntimeController.TrackPreference(
                subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                    id = currentAddon.id,
                    url = currentAddon.url,
                    language = currentAddon.lang,
                    addonName = currentAddon.addonName
                )
            )
        }
    }

    isReleasingPlayer = true
    com.nuvio.tv.core.recommendations.TvRecommendationManager.isPlaybackActive.value = false
    if (flushPlaybackState) {
        if (!keepTorrentStream) stopTorrentStream()
        flushPlaybackSnapshotForSwitchOrExit()
    }

    notifyAudioSessionUpdate(false)
    unregisterAudioDelayRouteCallback()
    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = null

    releaseMediaSession()
    progressJob?.cancel()
    mpvTrackRefreshJob?.cancel()
    mpvTrackRefreshJob = null
    mpvTrackRefreshInProgress = false
    hideControlsJob?.cancel()
    watchProgressSaveJob?.cancel()
    seekProgressSyncJob?.cancel()
    seekSourceLogJob?.cancel()
    seekSourceLogJob = null
    frameRateProbeJob?.cancel()
    cancelAfrPreflight()
    hideStreamSourceIndicatorJob?.cancel()
    hideStreamSourceIndicatorJob = null
    _uiState.update { it.copy(showStreamSourceIndicator = false) }
    hidePlayerEngineSwitchInfoJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    subtitleAutoSyncLoadJob?.cancel()
    cancelAutomaticSubtitleSync() // AutoSync hook
    stopSidecarAddonSubtitle(clearView = true)
    subtitleTimingRefreshJob?.cancel()
    subtitleTimingRefreshJob = null
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    traktMappingJob?.cancel()
    traktMappingJob = null
    delayMpvResumeSeekUntilVideoTrack = false
    mpvMediaLoadPrepared = false
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    cancelNextEpisodePreload()
    debridResolveJob?.cancel()
    debridResolveJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    errorRetryJob?.cancel()
    errorRetryJob = null
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
    mpvStableProgressResetJob?.cancel()
    mpvStableProgressResetJob = null
    releaseMpvPlayer()
    _exoPlayer?.let { player ->
        runCatching { player.playWhenReady = false }
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.releaseAssHandlerCompat() }
        runCatching { player.release() }
    }
    _exoPlayer = null
    activeAssVideoEffect = null
    _loadControl = null
    currentBitrateAwareLoadControl = null
    currentParallelChunkOverheadMb = 0
    ffmpegAudioRenderer = null
    updateAudioControlAvailability()
    playbackSpeedAwareAudioSink = null
    resetPlaybackTimeline()
    isReleasingPlayer = false
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    _exoPlayer?.let { player ->
        try {
            val intent = Intent(
                if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            )
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}

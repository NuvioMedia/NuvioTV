package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.releasePlayer() {
    releasePlayer(flushPlaybackState = true)
}

internal fun PlayerRuntimeController.releasePlayer(flushPlaybackState: Boolean) {
    isReleasingPlayer = true
    com.nuvio.tv.core.recommendations.TvRecommendationManager.isPlaybackActive.value = false
    if (flushPlaybackState) {
        stopTorrentStream()
        flushPlaybackSnapshotForSwitchOrExit()
    }

    notifyAudioSessionUpdate(false)
    unregisterAudioDelayRouteCallback()

    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    // Playback-session jobs: cancel before releasing ExoPlayer so delayed work
    // cannot touch a dead/rebuilt instance.
    progressJob?.cancel()
    progressJob = null
    hideControlsJob?.cancel()
    hideControlsJob = null
    hideSeekOverlayJob?.cancel()
    hideSeekOverlayJob = null
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = null
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = null
    frameRateProbeJob?.cancel()
    frameRateProbeJob = null
    hideStreamSourceIndicatorJob?.cancel()
    hideStreamSourceIndicatorJob = null
    _uiState.update { it.copy(showStreamSourceIndicator = false) }
    hidePlayerEngineSwitchInfoJob?.cancel()
    hidePlayerEngineSwitchInfoJob = null
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    traktMappingJob?.cancel()
    traktMappingJob = null
    delayMpvResumeSeekUntilVideoTrack = false
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    debridResolveJob?.cancel()
    debridResolveJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    errorRetryJob?.cancel()
    errorRetryJob = null
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    seekBufferingUiJob?.cancel()
    seekBufferingUiJob = null
    bufferLogJob?.cancel()
    bufferLogJob = null
    // Delayed first-frame / stall jobs capture player-era state; drop them before
    // nulling _exoPlayer so they cannot act on a later rebuild.
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    releaseMpvPlayer()
    _exoPlayer?.let { player ->
        runCatching { player.playWhenReady = false }
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
    }
    _exoPlayer = null
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

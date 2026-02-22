package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect

internal fun PlayerRuntimeController.releasePlayer() {
    flushPlaybackSnapshotForSwitchOrExit()

    notifyAudioSessionUpdate(false)

    try {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    progressJob?.cancel()
    hideControlsJob?.cancel()
    watchProgressSaveJob?.cancel()
    seekProgressSyncJob?.cancel()
    frameRateProbeJob?.cancel()
    hideStreamSourceIndicatorJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    _exoPlayer?.release()
    _exoPlayer = null
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

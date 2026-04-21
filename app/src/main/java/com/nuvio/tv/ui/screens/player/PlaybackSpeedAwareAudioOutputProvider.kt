package com.nuvio.tv.ui.screens.player

internal class PlaybackSpeedAwareAudioOutputProvider(
) {

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = false

    fun updatePlaybackSpeed(speed: Float, selectedAudioRequiresPcmForSpeed: Boolean = false) {
        val normalizedSpeed = speed.takeIf { it > 0f } ?: 1f
        val wasForcingPcm = forcePcmForCurrentSession
        if (selectedAudioRequiresPcmForSpeed && normalizedSpeed != 1f) {
            forcePcmForCurrentSession = true
        }
        playbackSpeed = normalizedSpeed
        if (wasForcingPcm != forcePcmForCurrentSession) {
            
        }
    }
}

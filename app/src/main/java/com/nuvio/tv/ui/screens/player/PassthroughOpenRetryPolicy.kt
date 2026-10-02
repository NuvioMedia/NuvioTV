package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.audio.AudioSink

internal class PassthroughOpenRetryPolicy {

    private var streamUrl: String? = null
    private var attempts = 0

    fun nextDelayMs(streamUrl: String): Long? {
        if (this.streamUrl != streamUrl) {
            this.streamUrl = streamUrl
            attempts = 0
        }
        if (attempts >= MAX_ATTEMPTS) return null
        attempts++
        return RETRY_STEP_MS * attempts
    }

    fun onAudioTrackOpened() {
        attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val RETRY_STEP_MS = 400L

        fun isEligible(
            errorCode: Int,
            failedInputMime: String?,
            policyDenies: Boolean,
            pcmFallbackTried: Boolean
        ): Boolean {
            if (errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
            if (pcmFallbackTried || policyDenies) return false
            return PassthroughWaterLevelPacer.isPassthroughMime(failedInputMime)
        }
    }
}

internal fun failedAudioTrackInputFormat(error: Throwable?): Format? {
    var cause = error
    repeat(8) {
        val current = cause ?: return null
        if (current is AudioSink.InitializationException) return current.format
        cause = current.cause
    }
    return null
}

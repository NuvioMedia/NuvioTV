package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
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

    @androidx.annotation.OptIn(UnstableApi::class)
    companion object {
        const val MAX_ATTEMPTS = 2
        const val RETRY_STEP_MS = 400L
        const val OUTPUT_POLL_MS = 500L
        const val MAX_OUTPUT_WAIT_MS = 10 * 60_000L

        private val bitstreamEncodings = intArrayOf(
            C.ENCODING_AC3,
            C.ENCODING_E_AC3,
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_AC4,
            C.ENCODING_DOLBY_TRUEHD,
            C.ENCODING_DTS,
            C.ENCODING_DTS_HD,
            C.ENCODING_DTS_UHD_P2
        )

        /**
         * Whether the output is there for a retry: it takes a bitstream format, or media is
         * routed to HDMI. When the TV switches to another input, HDMI-CEC can
         * put the box to sleep and the HDMI audio device goes away; a retry in that window builds
         * the player for the built-in output and the title stays on PCM after the TV comes back.
         */
        fun outputReady(liveEncodings: Set<Int>, routeIsHdmi: Boolean): Boolean =
            liveEncodings.isNotEmpty() || routeIsHdmi

        fun mayRetryNow(outputReady: Boolean, waitedMs: Long): Boolean =
            outputReady || waitedMs >= MAX_OUTPUT_WAIT_MS

        fun liveBitstreamEncodings(context: Context): Set<Int> = runCatching {
            val capabilities = AudioCapabilities.getCapabilities(
                context,
                AudioOutputRouteDetector.mediaMovieAudioAttributes(),
                null
            )
            bitstreamEncodings.filterTo(mutableSetOf()) { capabilities.supportsEncoding(it) }
        }.getOrDefault(emptySet())

        fun isHdmiRoute(routeKey: String?): Boolean = routeKey?.startsWith("type:hdmi") == true

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

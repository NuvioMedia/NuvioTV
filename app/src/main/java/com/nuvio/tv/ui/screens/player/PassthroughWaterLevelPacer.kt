package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.math.min

internal class PassthroughWaterLevelPacer {
    private var playing: Boolean = false
    private var frozenPlayingMs: Long = 0L
    private var playStartedAtMs: Long = 0L
    private var firstPtsUs: Long = C.TIME_UNSET
    private var lastAcceptedPtsUs: Long = C.TIME_UNSET
    private var positionAnchorUs: Long = C.TIME_UNSET
    private var sampleMimeType: String? = null

    fun appliesTo(format: Format?): Boolean {
        return isPassthroughMime(format?.sampleMimeType)
    }

    fun onFormat(format: Format?) {
        sampleMimeType = format?.sampleMimeType
        onReset()
    }

    fun onPlay(nowMs: Long) {
        if (playing) return
        playing = true
        playStartedAtMs = nowMs
    }

    fun onPause(nowMs: Long) {
        if (!playing) return
        frozenPlayingMs = playingWallMs(nowMs)
        playing = false
    }

    fun onReset() {
        playing = false
        frozenPlayingMs = 0L
        playStartedAtMs = 0L
        firstPtsUs = C.TIME_UNSET
        lastAcceptedPtsUs = C.TIME_UNSET
        positionAnchorUs = C.TIME_UNSET
    }

    fun onTimelineReset(nowMs: Long) {
        firstPtsUs = C.TIME_UNSET
        lastAcceptedPtsUs = C.TIME_UNSET
        positionAnchorUs = C.TIME_UNSET
        frozenPlayingMs = 0L
        if (playing) {
            playStartedAtMs = nowMs
        }
    }

    fun shouldAcceptBuffer(presentationTimeUs: Long, nowMs: Long, playbackSpeed: Float): Boolean {
        if (presentationTimeUs == C.TIME_UNSET) return true
        if (firstPtsUs == C.TIME_UNSET) {
            firstPtsUs = presentationTimeUs
            return true
        }
        val speed = normalizeSpeed(playbackSpeed)
        val aheadUs = presentationTimeUs - firstPtsUs
        val allowedUs = playingWallUs(nowMs, speed) + writeAheadCeilingUs()
        return aheadUs <= allowedUs
    }

    fun onBufferAccepted(presentationTimeUs: Long) {
        if (presentationTimeUs == C.TIME_UNSET) return
        if (firstPtsUs == C.TIME_UNSET) {
            firstPtsUs = presentationTimeUs
        }
        lastAcceptedPtsUs = presentationTimeUs
    }

    fun clampPositionUs(sinkPositionUs: Long, nowMs: Long, playbackSpeed: Float): Long {
        if (sinkPositionUs == C.TIME_UNSET) return sinkPositionUs
        if (positionAnchorUs == C.TIME_UNSET) {
            positionAnchorUs = sinkPositionUs
            return sinkPositionUs
        }
        val speed = normalizeSpeed(playbackSpeed)
        val wallCapUs = positionAnchorUs + playingWallUs(nowMs, speed) + POSITION_LEAD_SLACK_US
        val writtenCapUs = if (lastAcceptedPtsUs != C.TIME_UNSET) {
            lastAcceptedPtsUs + POSITION_LEAD_SLACK_US
        } else {
            Long.MAX_VALUE
        }
        return min(sinkPositionUs, min(wallCapUs, writtenCapUs))
    }

    fun writeAheadCeilingUs(): Long {
        return if (sampleMimeType == MimeTypes.AUDIO_TRUEHD) {
            TRUEHD_WRITE_AHEAD_US
        } else {
            MAX_WATER_LEVEL_US
        }
    }

    private fun playingWallMs(nowMs: Long): Long {
        if (!playing) return frozenPlayingMs
        return frozenPlayingMs + (nowMs - playStartedAtMs).coerceAtLeast(0L)
    }

    private fun playingWallUs(nowMs: Long, speed: Float): Long {
        return (playingWallMs(nowMs) * speed * 1000.0).toLong()
    }

    companion object {
        const val MAX_WATER_LEVEL_US = 200_000L
        const val TRUEHD_WRITE_AHEAD_US = 800_000L
        const val POSITION_LEAD_SLACK_US = 100_000L

        fun isPassthroughMime(mimeType: String?): Boolean {
            if (mimeType == null) return false
            return mimeType == MimeTypes.AUDIO_AC3 ||
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                mimeType == MimeTypes.AUDIO_AC4 ||
                mimeType == MimeTypes.AUDIO_TRUEHD ||
                mimeType == MimeTypes.AUDIO_DTS ||
                mimeType == MimeTypes.AUDIO_DTS_HD ||
                mimeType == MimeTypes.AUDIO_DTS_EXPRESS ||
                mimeType == MimeTypes.AUDIO_DTS_X ||
                mimeType.startsWith("audio/vnd.dts")
        }

        private fun normalizeSpeed(speed: Float): Float {
            return speed.takeIf { it > 0f } ?: 1f
        }
    }
}

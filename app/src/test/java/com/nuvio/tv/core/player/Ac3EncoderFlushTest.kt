package com.nuvio.tv.core.player

import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

class Ac3EncoderFlushTest {

    @Test
    fun padsOnlyTheIncompleteAc3Frame() {
        val frame = 1536
        assertEquals(0, FfmpegAudioRenderer.ac3TrailingSilenceSamples(0, frame))
        assertEquals(0, FfmpegAudioRenderer.ac3TrailingSilenceSamples(frame, frame))
        assertEquals(0, FfmpegAudioRenderer.ac3TrailingSilenceSamples(frame * 2, frame))
        assertEquals(frame - 1, FfmpegAudioRenderer.ac3TrailingSilenceSamples(1, frame))
        assertEquals(1, FfmpegAudioRenderer.ac3TrailingSilenceSamples(frame - 1, frame))
        assertEquals(frame - 1, FfmpegAudioRenderer.ac3TrailingSilenceSamples(frame + 1, frame))
        assertEquals(100, FfmpegAudioRenderer.ac3TrailingSilenceSamples(1436, frame))
    }

    @Test
    fun ignoresAnUnopenedEncoder() {
        assertEquals(0, FfmpegAudioRenderer.ac3TrailingSilenceSamples(100, 0))
        assertEquals(0, FfmpegAudioRenderer.ac3TrailingSilenceSamples(100, -1))
        assertEquals(0, FfmpegAudioRenderer.ac3TrailingSilenceSamples(-5, 1536))
    }

    @Test
    fun oneFrameIsThirtyTwoMillisecondsAt48k() {
        assertEquals(32_000L, FfmpegAudioRenderer.ac3FrameDurationUs(1536, 48_000))
        assertEquals(1536L * 1_000_000L / 44_100L, FfmpegAudioRenderer.ac3FrameDurationUs(1536, 44_100))
        assertEquals(1536L * 1_000_000L / 32_000L, FfmpegAudioRenderer.ac3FrameDurationUs(1536, 32_000))
        assertEquals(0L, FfmpegAudioRenderer.ac3FrameDurationUs(0, 48_000))
        assertEquals(0L, FfmpegAudioRenderer.ac3FrameDurationUs(1536, 0))
    }
}

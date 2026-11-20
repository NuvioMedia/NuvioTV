package com.nuvio.tv.core.player

import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

class Ac3TranscodeSampleRateTest {

    @Test
    fun keepsTheThreeRatesAc3CanEncode() {
        assertEquals(32_000, FfmpegAudioRenderer.ac3SampleRate(32_000))
        assertEquals(44_100, FfmpegAudioRenderer.ac3SampleRate(44_100))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(48_000))
    }

    @Test
    fun mapsLosslessRatesTo48k() {
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(96_000))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(88_200))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(176_400))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(192_000))
    }

    @Test
    fun mapsMissingOrUnknownRatesTo48k() {
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(0))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(-1))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(22_050))
        assertEquals(48_000, FfmpegAudioRenderer.ac3SampleRate(16_000))
    }
}

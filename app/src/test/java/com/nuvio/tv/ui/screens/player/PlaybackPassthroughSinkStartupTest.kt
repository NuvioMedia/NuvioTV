package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackPassthroughSinkStartupTest {

    private lateinit var mockSink: AudioSink
    private lateinit var audioSink: PlaybackSpeedAwareAudioSink

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0

        mockSink = mockk(relaxed = true)
        audioSink = PlaybackSpeedAwareAudioSink(mockSink)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `passthrough configure arms startup resync on first play only`() {
        val trueHdFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(8)
            .setSampleRate(48000)
            .build()

        audioSink.configure(trueHdFormat, 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())

        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        // Subsequent play without pause must not resync again
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `ac3 passthrough format arms startup resync`() {
        val ac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.configure(ac3Format, 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())

        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `pause then play forces media time resync for passthrough`() {
        val eac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.configure(eac3Format, 0, null)
        audioSink.play() // startup resync
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.pause()
        audioSink.play()
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `rebuffer requestPassthroughResync applies discontinuity immediately`() {
        val eac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.configure(eac3Format, 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        // Rebuffer recovery does not re-enter play() — resync must be immediate
        audioSink.requestPassthroughResync("rebuffer_end")
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `armPassthroughResync also applies discontinuity immediately`() {
        val eac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.configure(eac3Format, 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.armPassthroughResync()
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `pcm format does not arm passthrough resync`() {
        val pcmFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()

        audioSink.configure(pcmFormat, 0, null)
        assertFalse(audioSink.isDirectPlaybackActive())

        audioSink.play()
        verify(exactly = 0) { mockSink.handleDiscontinuity() }

        audioSink.requestPassthroughResync("rebuffer_end")
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `flush on non-DefaultAudioSink delegate falls through to super`() {
        val ac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.configure(ac3Format, 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        // mockSink is not DefaultAudioSink → reuse path returns false → super.flush()
        audioSink.flush()
        verify(exactly = 1) { mockSink.flush() }
    }

    @Test
    fun `force pcm for speed rejects direct passthrough`() {
        val ac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.setInitialPlaybackSpeed(1.25f)
        audioSink.configure(ac3Format, 0, null)
        assertFalse(audioSink.isDirectPlaybackActive())
        assertTrue(audioSink.shouldForcePcmForFormat(ac3Format))
    }
}

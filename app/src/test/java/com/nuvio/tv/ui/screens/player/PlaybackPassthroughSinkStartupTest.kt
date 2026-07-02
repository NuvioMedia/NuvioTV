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
        every { Log.w(any(), any<Throwable>()) } returns 0

        mockSink = mockk(relaxed = true)
        audioSink = PlaybackSpeedAwareAudioSink(mockSink)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `test passthrough configuration arms initial startup compensation`() {
        val trueHdFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(8)
            .setSampleRate(48000)
            .build()

        audioSink.configure(trueHdFormat, 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())

        // Calling play() should trigger handleDiscontinuity on delegate to resync media time
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        // Subsequent play() without pause should NOT trigger handleDiscontinuity again
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `test ac3 passthrough format arms startup compensation`() {
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
    fun `test rebuffer recovery manual arming triggers resync on play`() {
        val eac3Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        audioSink.configure(eac3Format, 0, null)
        audioSink.play() // initial startup resync
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        // Arm resync on rebuffer end
        audioSink.armPassthroughResync()
        audioSink.play()
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }
}

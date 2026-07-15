package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Behaviour contract for [PlaybackSpeedAwareAudioSink] before device QA.
 *
 * Covers: startup/pause/rebuffer clock resync, PCM force for speed, flush fallback
 * when delegate is not DefaultAudioSink, and bitstream format matrix.
 * (Real AudioTrack reuse needs DefaultAudioSink + reflection — exercised on device.)
 */
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

    private fun bitstream(mime: String, channels: Int = 6): Format {
        return Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(channels)
            .setSampleRate(48000)
            .build()
    }

    private fun pcmStereo(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()
    }

    // ── startup ──────────────────────────────────────────────────────

    @Test
    fun `passthrough configure arms startup resync on first play only`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_TRUEHD, 8), 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())

        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
        verify(exactly = 2) { mockSink.play() }
    }

    @Test
    fun `ac3 passthrough format arms startup resync`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_AC3), 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `all primary bitstream mimes arm direct playback`() {
        val mimes = listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS
        )
        mimes.forEach { mime ->
            val sink = PlaybackSpeedAwareAudioSink(mockk(relaxed = true))
            sink.configure(bitstream(mime), 0, null)
            assertTrue("direct expected for $mime", sink.isDirectPlaybackActive())
        }
    }

    // ── pause / resume (HDMI buffer compensation) ────────────────────

    @Test
    fun `rebuffer style pause play does not force discontinuity`() {
        // Exo pauses the sink on rebuffer; that must not arm HDMI resume resync.
        audioSink.configure(bitstream(MimeTypes.AUDIO_E_AC3), 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.pause()
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
        verifyOrder {
            mockSink.play()
            mockSink.pause()
            mockSink.play()
        }
    }

    @Test
    fun `user pause arm then play forces media time resync`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_E_AC3), 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.armPassthroughResyncForNextPlay()
        audioSink.pause()
        audioSink.play()
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `pcm pause play does not force discontinuity`() {
        audioSink.configure(pcmStereo(), 0, null)
        audioSink.play()
        audioSink.pause()
        audioSink.play()
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
    }

    // ── explicit resync ──────────────────────────────────────────────

    @Test
    fun `requestPassthroughResync applies discontinuity immediately`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_E_AC3), 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.requestPassthroughResync("manual")
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `armPassthroughResyncForNextPlay only fires on subsequent play`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_E_AC3), 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.armPassthroughResyncForNextPlay()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
        audioSink.play()
        verify(exactly = 2) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `requestPassthroughResync no-ops for pcm`() {
        audioSink.configure(pcmStereo(), 0, null)
        audioSink.requestPassthroughResync("test")
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
    }

    // ── flush (seek) without real DefaultAudioSink ───────────────────

    @Test
    fun `flush on non-DefaultAudioSink delegate falls through to super`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_AC3), 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.flush()
        verify(exactly = 1) { mockSink.flush() }
    }

    @Test
    fun `flush clears pending pause compensation flags`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_AC3), 0, null)
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }

        audioSink.pause()
        audioSink.flush()
        audioSink.play()
        // pause flag cleared by flush → no resume discontinuity
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
        verify(exactly = 1) { mockSink.flush() }
    }

    @Test
    fun `flush after startup does not re-arm startup compensation`() {
        audioSink.configure(bitstream(MimeTypes.AUDIO_TRUEHD, 8), 0, null)
        audioSink.flush() // before first play — clears startup arm
        audioSink.play()
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
    }

    // ── speed → PCM ──────────────────────────────────────────────────

    @Test
    fun `force pcm for speed rejects direct passthrough`() {
        audioSink.setInitialPlaybackSpeed(1.25f)
        audioSink.configure(bitstream(MimeTypes.AUDIO_AC3), 0, null)
        assertFalse(audioSink.isDirectPlaybackActive())
        assertTrue(audioSink.shouldForcePcmForFormat(bitstream(MimeTypes.AUDIO_AC3)))
    }

    @Test
    fun `speed change mid session forces pcm and notifies capabilities`() {
        val listener = mockk<AudioSink.Listener>(relaxed = true)
        audioSink.setListener(listener)
        audioSink.configure(bitstream(MimeTypes.AUDIO_E_AC3), 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())

        audioSink.setPlaybackParameters(PlaybackParameters(1.5f))
        assertTrue(audioSink.shouldForcePcmForFormat(bitstream(MimeTypes.AUDIO_E_AC3)))
        verify(atLeast = 1) { listener.onAudioCapabilitiesChanged() }
    }

    @Test
    fun `getFormatSupport rejects direct when forcing pcm for speed`() {
        audioSink.setInitialPlaybackSpeed(1.5f)
        val format = bitstream(MimeTypes.AUDIO_AC3)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, audioSink.getFormatSupport(format))
    }

    @Test
    fun `pcm format does not arm passthrough resync`() {
        audioSink.configure(pcmStereo(), 0, null)
        assertFalse(audioSink.isDirectPlaybackActive())
        audioSink.play()
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
        audioSink.requestPassthroughResync("rebuffer_end")
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `initial force pcm constructor flag`() {
        val forced = PlaybackSpeedAwareAudioSink(mockSink, initialForcePcm = true)
        forced.configure(bitstream(MimeTypes.AUDIO_AC3), 0, null)
        assertFalse(forced.isDirectPlaybackActive())
        assertTrue(forced.shouldForcePcmForFormat(bitstream(MimeTypes.AUDIO_AC3)))
    }

    // ── codecs-only format ───────────────────────────────────────────

    @Test
    fun `codecs string without mime still marks direct eligible`() {
        val format = Format.Builder()
            .setCodecs("ec-3")
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()
        audioSink.configure(format, 0, null)
        assertTrue(audioSink.isDirectPlaybackActive())
        audioSink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
    }
}

/**
 * Parameterized matrix: each common bitstream mime should be direct at 1x and forced PCM at 1.25x.
 */
@RunWith(Parameterized::class)
class PlaybackPassthroughMimeMatrixTest(
    private val mime: String
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf(MimeTypes.AUDIO_AC3),
            arrayOf(MimeTypes.AUDIO_E_AC3),
            arrayOf(MimeTypes.AUDIO_TRUEHD),
            arrayOf(MimeTypes.AUDIO_DTS),
            arrayOf(MimeTypes.AUDIO_DTS_HD),
            arrayOf(MimeTypes.AUDIO_AC4)
        )
    }

    private lateinit var mockSink: AudioSink

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0
        mockSink = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `1x is direct and arms startup discontinuity`() {
        val sink = PlaybackSpeedAwareAudioSink(mockSink)
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()
        sink.configure(format, 0, null)
        assertTrue(sink.isDirectPlaybackActive())
        sink.play()
        verify(exactly = 1) { mockSink.handleDiscontinuity() }
    }

    @Test
    fun `1_25x is not direct`() {
        val sink = PlaybackSpeedAwareAudioSink(mockSink)
        sink.setInitialPlaybackSpeed(1.25f)
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()
        sink.configure(format, 0, null)
        assertFalse(sink.isDirectPlaybackActive())
        sink.play()
        verify(exactly = 0) { mockSink.handleDiscontinuity() }
    }
}

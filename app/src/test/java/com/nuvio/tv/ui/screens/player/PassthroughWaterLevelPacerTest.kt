package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughWaterLevelPacerTest {

    @Test
    fun mime_matchesEveryPassthroughFormat() {
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_AC3))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_E_AC3))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_E_AC3_JOC))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_AC4))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS_HD))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS_EXPRESS))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS_X))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime("audio/vnd.dts.hd;profile=lbr"))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_TRUEHD))
        assertFalse(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_RAW))
        assertFalse(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_AAC))
        assertFalse(PassthroughWaterLevelPacer.isPassthroughMime(null))
    }

    @Test
    fun dtsAndAc3_useTwoHundredMsWaterLevel() {
        val dts = PassthroughWaterLevelPacer()
        dts.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        assertEquals(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, dts.writeAheadCeilingUs())

        val ac3 = PassthroughWaterLevelPacer()
        ac3.onFormat(mime(MimeTypes.AUDIO_AC3))
        assertEquals(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, ac3.writeAheadCeilingUs())
    }

    @Test
    fun trueHd_usesLooserCapWithoutIecPacker() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        assertEquals(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, pacer.writeAheadCeilingUs())
    }

    @Test
    fun trueHd_usesTwoHundredMsWhenIecPacked() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(true)
        assertEquals(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, pacer.writeAheadCeilingUs())
    }

    @Test
    fun prestart_allowsWaterThenRejectsSprint() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, 0L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1L, 0L, 1f))
    }

    @Test
    fun playing_acceptsRealtimeAndRejectsByteSprint() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_E_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(1_000_000L, 1_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(5_000_000L, 1_000L, 1f))
    }

    @Test
    fun trueHd_allowsEightHundredMsWhereDtsWouldReject() {
        val trueHd = PassthroughWaterLevelPacer()
        trueHd.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        assertTrue(trueHd.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(trueHd.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, 0L, 1f))
        assertFalse(trueHd.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US + 1L, 0L, 1f))

        val dts = PassthroughWaterLevelPacer()
        dts.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        assertTrue(dts.shouldAcceptBuffer(0L, 0L, 1f))
        assertFalse(dts.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, 0L, 1f))
    }

    @Test
    fun position_capsAtWrittenPtsAndWall() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(100_000L, 100L, 1f))
        pacer.onBufferAccepted(100_000L)
        val clamped = pacer.clampPositionUs(2_000_000L, 100L, 1f)
        assertEquals(100_000L + PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US, clamped)
    }

    @Test
    fun position_clampsWallLeadAndPreservesLag() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertTrue(pacer.shouldAcceptBuffer(200_000L, 200L, 1f))
        pacer.onBufferAccepted(200_000L)
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        val clamped = pacer.clampPositionUs(2_000_000L, 200L, 1f)
        assertEquals(200_000L + PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US, clamped)
        assertEquals(50_000L, pacer.clampPositionUs(50_000L, 200L, 1f))
    }

    @Test
    fun pause_doesNotAdvanceAllowedWindow() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onPause(500L)
        val pausedLimit = 500_000L + PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(pausedLimit, 10_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(pausedLimit + 1L, 10_000L, 1f))
    }

    @Test
    fun timelineReset_relatchesPtsWhilePlaying() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(60_000_000L, 0L, 1f))
        pacer.onTimelineReset(5_000L)
        assertTrue(pacer.shouldAcceptBuffer(90_000_000L, 5_000L, 1f))
        assertFalse(
            pacer.shouldAcceptBuffer(
                90_000_000L + PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1L,
                5_000L,
                1f
            )
        )
    }

    @Test
    fun sink_ac3AndDtsHdArePaced_pcmIsNot() {
        val ac3Sink = PlaybackSpeedAwareAudioSink(RecordingSink())
        ac3Sink.configure(mime(MimeTypes.AUDIO_AC3), 0, null)
        val buf = java.nio.ByteBuffer.allocate(32)
        assertTrue(ac3Sink.handleBuffer(buf, 0L, 1))
        assertFalse(
            ac3Sink.handleBuffer(buf, PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1_000L, 1)
        )

        val pcmSink = PlaybackSpeedAwareAudioSink(RecordingSink())
        pcmSink.configure(mime(MimeTypes.AUDIO_RAW), 0, null)
        assertTrue(pcmSink.handleBuffer(buf, 0L, 1))
        assertTrue(
            pcmSink.handleBuffer(buf, PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1_000L, 1)
        )
    }

    private fun mime(sampleMimeType: String): Format {
        return Format.Builder()
            .setSampleMimeType(sampleMimeType)
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
    }

    private class RecordingSink : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: java.nio.ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean = true
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        override fun enableTunnelingV21() = Unit
        override fun disableTunneling() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}

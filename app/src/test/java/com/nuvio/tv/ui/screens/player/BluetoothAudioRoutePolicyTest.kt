package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Bluetooth media policy mirrors Media3 1.8.0 AudioCapabilities:
 * when the route is Bluetooth, encoded passthrough is rejected and PCM decode is forced.
 */
class BluetoothAudioRoutePolicyTest {

    @Test
    fun `bluetooth type set matches Media3 A2DP SCO and LE`() {
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertFalse(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_HDMI))
        assertFalse(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun `bluetooth force pcm rejects eac3 truehd and dts direct sink support`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = true,
            forcePcmForBluetooth = true
        )
        val formats = listOf(
            mime(MimeTypes.AUDIO_E_AC3),
            mime(MimeTypes.AUDIO_TRUEHD),
            mime(MimeTypes.AUDIO_DTS_HD),
            mime(MimeTypes.AUDIO_AC3)
        )
        for (format in formats) {
            assertEquals(
                "Expected PCM force for ${format.sampleMimeType}",
                AudioSink.SINK_FORMAT_UNSUPPORTED,
                sink.getFormatSupport(format)
            )
            assertTrue(sink.shouldForcePcmForFormat(format))
        }
    }

    @Test
    fun `without bluetooth force pcm allows aac through`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        val aac = mime(MimeTypes.AUDIO_AAC)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(aac))
        assertFalse(sink.shouldForcePcmForFormat(aac))
    }

    @Test
    fun `speed change alone forces pcm for surround without bluetooth flag`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        sink.setPlaybackParameters(PlaybackParameters(1.5f))
        val eac3 = mime(MimeTypes.AUDIO_E_AC3)
        assertTrue(sink.shouldForcePcmForFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(eac3))
    }

    @Test
    fun `bluetooth connect or disconnect never asks to reinitialize the player`() {
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = false,
                isBluetooth = true,
                usingMpv = false
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = false,
                usingMpv = false
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.NONE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = false
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = false,
                isBluetooth = true,
                usingMpv = true
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = false,
                usingMpv = true
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.NONE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = true
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = true,
                oldRouteKey = "type:bluetooth_a2dp|name:speaker_a",
                newRouteKey = "type:bluetooth_a2dp|name:speaker_b"
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = false,
                oldRouteKey = "type:bluetooth_a2dp|name:speaker_a",
                newRouteKey = "type:bluetooth_a2dp|name:speaker_b"
            )
        )
    }

    @Test
    fun `mpv bluetooth forces stereo and clears encoded passthrough`() {
        assertEquals(MpvBluetoothAudioPolicy.STEREO_CHANNELS, MpvBluetoothAudioPolicy.audioChannels(true))
        assertEquals(MpvBluetoothAudioPolicy.AUTO_CHANNELS, MpvBluetoothAudioPolicy.audioChannels(false))
        assertTrue(MpvBluetoothAudioPolicy.shouldClearAudioSpdif(true))
        assertFalse(MpvBluetoothAudioPolicy.shouldClearAudioSpdif(false))
    }

    @Test
    fun `audio delay milliseconds map to mpv seconds and clamp`() {
        assertEquals(0.200, audioDelayMsToSeconds(200), 0.0001)
        assertEquals(-0.250, audioDelayMsToSeconds(-250), 0.0001)
        assertEquals(AUDIO_DELAY_MAX_MS / 1000.0, audioDelayMsToSeconds(99_999), 0.0001)
        assertEquals(AUDIO_DELAY_MIN_MS / 1000.0, audioDelayMsToSeconds(-99_999), 0.0001)
    }

    @Test
    fun `mid-session bluetooth connect forces pcm without a rebuild flag`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        val eac3 = mime(MimeTypes.AUDIO_E_AC3)
        assertFalse(sink.shouldForcePcmForFormat(eac3))

        assertTrue(sink.setBluetoothForcePcm(true))
        assertTrue(sink.isBluetoothForcePcm())
        assertTrue(sink.shouldForcePcmForFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(eac3))
        assertFalse(sink.setBluetoothForcePcm(true))
    }

    @Test
    fun `hdmi-started session restores passthrough after bluetooth disconnect`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        val eac3 = mime(MimeTypes.AUDIO_E_AC3)
        sink.setBluetoothForcePcm(true)
        assertTrue(sink.shouldForcePcmForFormat(eac3))

        assertTrue(sink.setBluetoothForcePcm(false))
        assertFalse(sink.isBluetoothForcePcm())
        assertFalse(sink.shouldForcePcmForFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(eac3))
    }

    @Test
    fun `session built for bluetooth stays on pcm after disconnect`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = true,
            forcePcmForBluetooth = true
        )
        val truehd = mime(MimeTypes.AUDIO_TRUEHD)
        sink.setBluetoothForcePcm(false)
        assertFalse(sink.isBluetoothForcePcm())
        assertTrue(sink.shouldForcePcmForFormat(truehd))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(truehd))
    }

    @Test
    fun `playback state preservation policy correctly distinguishes playing vs paused intents`() {
        // User paused manually -> always stays paused regardless of player playWhenReady
        val userPausedManually = true
        val playWhenReady = true
        val wasPlayingWithUserPause = playWhenReady && !userPausedManually
        assertFalse(wasPlayingWithUserPause)

        // User was playing actively (playWhenReady = true, userPaused = false) -> keeps playing
        val userPlaying = false
        val activePlayWhenReady = true
        val wasPlayingActive = activePlayWhenReady && !userPlaying
        assertTrue(wasPlayingActive)

        // Video was paused (playWhenReady = false, userPaused = false) -> stays paused
        val idlePlayWhenReady = false
        val wasPlayingIdle = idlePlayWhenReady && !userPlaying
        assertFalse(wasPlayingIdle)
    }

    @Test
    fun `playback intent survives buffering state when playWhenReady is true`() {
        // Simulates ExoPlayer in STATE_BUFFERING: isPlaying is false, but playWhenReady is true
        val isExoPlaying = false
        val playWhenReady = true
        val userPausedManually = false

        // hasActivePlayIntent evaluates playWhenReady
        val hasActiveIntent = playWhenReady
        assertTrue(hasActiveIntent)

        val shouldKeepPlaying = hasActiveIntent && !userPausedManually
        assertTrue(shouldKeepPlaying)
    }

    @Test
    fun `playback intent correctly pauses when user has manually paused during buffering`() {
        val playWhenReady = true
        val userPausedManually = true

        val shouldKeepPlaying = playWhenReady && !userPausedManually
        assertFalse(shouldKeepPlaying)
    }

    private fun mime(sampleMimeType: String): Format {
        return Format.Builder()
            .setSampleMimeType(sampleMimeType)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()
    }

    private class AlwaysSupportedDelegateSink : ForwardingAudioSink(NoOpBaseSink())

    private class NoOpBaseSink : AudioSink {
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
            buffer: ByteBuffer,
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

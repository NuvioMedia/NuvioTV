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

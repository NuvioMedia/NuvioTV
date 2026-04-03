package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.util.concurrent.CopyOnWriteArraySet

internal class PlaybackSpeedAwareAudioOutputProvider(
    audioOutputProvider: AudioOutputProvider
) : ForwardingAudioOutputProvider(audioOutputProvider) {

    private val listeners = CopyOnWriteArraySet<AudioOutputProvider.Listener>()

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = false

    fun updatePlaybackSpeed(speed: Float, selectedAudioRequiresPcmForSpeed: Boolean = false) {
        val normalizedSpeed = speed.takeIf { it > 0f } ?: 1f
        val wasForcingPcm = forcePcmForCurrentSession
        if (selectedAudioRequiresPcmForSpeed && normalizedSpeed != 1f) {
            forcePcmForCurrentSession = true
        }
        playbackSpeed = normalizedSpeed
        val isForcingPcm = forcePcmForCurrentSession
        if (wasForcingPcm != isForcingPcm) {
            listeners.forEach(AudioOutputProvider.Listener::onFormatSupportChanged)
        }
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val support = super.getFormatSupport(formatConfig)
        if (!shouldForcePcm(formatConfig.format) || support.supportLevel != AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY) {
            return support
        }
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_UNSUPPORTED)
            .build()
    }

    private fun shouldForcePcm(format: Format): Boolean {
        if (!forcePcmForCurrentSession) {
            return false
        }
        val mimeType = format.sampleMimeType
        if (mimeType != null && (
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                mimeType == MimeTypes.AUDIO_AC3 ||
                mimeType == MimeTypes.AUDIO_AC4 ||
                mimeType == MimeTypes.AUDIO_TRUEHD ||
                mimeType == MimeTypes.AUDIO_DTS ||
                mimeType == MimeTypes.AUDIO_DTS_HD ||
                mimeType == MimeTypes.AUDIO_DTS_EXPRESS ||
                mimeType.startsWith("audio/vnd.dts")
            )
        ) {
            return true
        }
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ac-3", ignoreCase = true) ||
                    codecs.contains("ac-4", ignoreCase = true) ||
                    codecs.contains("ec-3", ignoreCase = true) ||
                    codecs.contains("dts", ignoreCase = true) ||
                    codecs.contains("truehd", ignoreCase = true) ||
                    codecs.contains("dtshd", ignoreCase = true)
        }
        return false
    }
}

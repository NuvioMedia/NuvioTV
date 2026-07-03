package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    initialForcePcm: Boolean = false,
    /**
     * Audio review F2: when Force AC-3 Transcoding is enabled, claim AC-3
     * support here regardless of what the HAL reports. The previous approach -
     * Builder.setAudioCapabilities(...) - is silently discarded whenever the
     * builder has a Context (the sink installs live AudioCapabilitiesReceiver
     * capabilities on first configure), so the "force" never reached the sink
     * and the toggle only worked on HALs that already reported AC-3. Claiming
     * support at the wrapper survives the dynamic-capabilities design. Scoped
     * to AC-3 <= 5.1 only: that is what S/PDIF can carry.
     */
    private val forceAc3Support: Boolean = false
) : ForwardingAudioSink(sink) {

    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        var shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        // Audio review F7: returning to 1.0x previously left forcePcm set for the
        // rest of the session - one visit to 1.25x silently killed TrueHD/DTS
        // passthrough until the next title. Clear it (unless PCM was forced at
        // construction as part of error recovery) and notify so the track
        // selector re-evaluates bypass; the selector is configured with
        // setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true).
        if (playbackSpeed == 1f && forcePcmForCurrentSession && !startedWithForcedPcm) {
            forcePcmForCurrentSession = false
            shouldNotify = true
        }
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) {
            listener?.onAudioCapabilitiesChanged()
        }
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldRejectDirectPlayback(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        if (forceAc3Support &&
            format.sampleMimeType == MimeTypes.AUDIO_AC3 &&
            format.channelCount <= 6 &&
            super.getFormatSupport(format) == AudioSink.SINK_FORMAT_UNSUPPORTED
        ) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (shouldRejectDirectPlayback(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    fun shouldForcePcmForFormat(format: Format): Boolean {
        return shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        return requiresPcmForSpeed(format) && (forcePcmForCurrentSession || playbackSpeed != 1f)
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || speed == 1f || !requiresPcmForSpeed(format)) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    private fun requiresPcmForSpeed(format: Format): Boolean {
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

package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

internal class PlaybackSpeedAwareAudioSink(
    private val delegate: AudioSink,
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
) : ForwardingAudioSink(delegate) {

    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    /**
     * Whether the current audio format is playing in passthrough mode (bitstream direct to
     * HDMI receiver). When true, pause/resume requires special handling because the receiver
     * has its own internal buffer that continues draining after Android's AudioTrack is paused.
     */
    @Volatile
    private var isCurrentlyPassthrough: Boolean = false

    /**
     * Set to true when pause() is called during passthrough playback.
     * On the next play() call, we force a media time resync to compensate for
     * audio the HDMI receiver played from its internal buffer during the pause.
     */
    @Volatile
    private var passthroughPauseCompensationPending: Boolean = false

    /**
     * Set to true when passthrough mode is configured initially.
     * On the first play() call, we force a media time resync to ensure
     * immediate hardware clock alignment for tunneled passthrough audio.
     */
    @Volatile
    private var passthroughStartupCompensationPending: Boolean = false

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
        // Detect if this format will play in passthrough mode (bitstream, not forced to PCM)
        val wasPassthrough = isCurrentlyPassthrough
        isCurrentlyPassthrough = isBitstreamFormat(inputFormat) && !shouldRejectDirectPlayback(inputFormat)
        if (isCurrentlyPassthrough && !wasPassthrough) {
            passthroughStartupCompensationPending = true
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun flush() {
        passthroughPauseCompensationPending = false
        passthroughStartupCompensationPending = false
        if (isCurrentlyPassthrough && tryReuseAudioTrackOnFlush()) {
            Log.i(TAG, "Reused AudioTrack on flush (avoided release/recreate handshake)")
            return
        }
        super.flush()
    }

    private fun tryReuseAudioTrackOnFlush(): Boolean {
        val defaultSink = delegate as? DefaultAudioSink ?: return false
        return try {
            val audioTrackField = DefaultAudioSink::class.java.getDeclaredField("audioTrack").apply { isAccessible = true }
            val audioTrack = audioTrackField.get(defaultSink) as? AudioTrack ?: return false

            val pendingConfigField = DefaultAudioSink::class.java.getDeclaredField("pendingConfiguration").apply { isAccessible = true }
            val pendingConfiguration = pendingConfigField.get(defaultSink)

            val configurationField = DefaultAudioSink::class.java.getDeclaredField("configuration").apply { isAccessible = true }
            val configuration = configurationField.get(defaultSink) ?: return false

            if (pendingConfiguration != null) {
                val canReuseMethod = configuration.javaClass.getDeclaredMethod("canReuseAudioTrack", pendingConfiguration.javaClass).apply { isAccessible = true }
                val canReuse = canReuseMethod.invoke(configuration, pendingConfiguration) as Boolean
                if (!canReuse) {
                    return false
                }
                // Update configuration to the pending one
                configurationField.set(defaultSink, pendingConfiguration)
                pendingConfigField.set(defaultSink, null)
            }

            val positionTrackerField = DefaultAudioSink::class.java.getDeclaredField("audioTrackPositionTracker").apply { isAccessible = true }
            val positionTracker = positionTrackerField.get(defaultSink) ?: return false

            val isPlayingMethod = positionTracker.javaClass.getDeclaredMethod("isPlaying").apply { isAccessible = true }
            val isPlaying = isPlayingMethod.invoke(positionTracker) as Boolean
            if (isPlaying) {
                audioTrack.pause()
            }

            val isOffloadedPlaybackMethod = DefaultAudioSink::class.java.getDeclaredMethod("isOffloadedPlayback", AudioTrack::class.java).apply { isAccessible = true }
            val isOffloaded = isOffloadedPlaybackMethod.invoke(null, audioTrack) as Boolean
            if (isOffloaded) {
                val offloadCallbackField = DefaultAudioSink::class.java.getDeclaredField("offloadStreamEventCallbackV29").apply { isAccessible = true }
                val offloadCallback = offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    val unregisterMethod = offloadCallback.javaClass.getDeclaredMethod("unregister", AudioTrack::class.java).apply { isAccessible = true }
                    unregisterMethod.invoke(offloadCallback, audioTrack)
                }
            }

            // Flush the native AudioTrack buffer
            audioTrack.flush()

            // Reset position tracker state, re-associating with the same AudioTrack
            val setAudioTrackMethod = positionTracker.javaClass.getDeclaredMethod(
                "setAudioTrack",
                AudioTrack::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val outputEncodingField = configuration.javaClass.getDeclaredField("outputEncoding").apply { isAccessible = true }
            val outputEncoding = outputEncodingField.get(configuration) as Int
            val outputPcmFrameSizeField = configuration.javaClass.getDeclaredField("outputPcmFrameSize").apply { isAccessible = true }
            val outputPcmFrameSize = outputPcmFrameSizeField.get(configuration) as Int
            val bufferSizeField = configuration.javaClass.getDeclaredField("bufferSize").apply { isAccessible = true }
            val bufferSize = bufferSizeField.get(configuration) as Int

            val enableOnAudioPositionAdvancingFixField = DefaultAudioSink::class.java.getDeclaredField("enableOnAudioPositionAdvancingFix").apply { isAccessible = true }
            val enableOnAudioPositionAdvancingFix = enableOnAudioPositionAdvancingFixField.get(defaultSink) as Boolean

            setAudioTrackMethod.invoke(
                positionTracker,
                audioTrack,
                true, // isPassthrough
                outputEncoding,
                outputPcmFrameSize,
                bufferSize,
                enableOnAudioPositionAdvancingFix
            )

            // Reset all internal default sink states for a clean flush
            val resetSinkStateForFlushMethod = DefaultAudioSink::class.java.getDeclaredMethod("resetSinkStateForFlush").apply { isAccessible = true }
            resetSinkStateForFlushMethod.invoke(defaultSink)

            // Clear exception holders
            val writeExceptionField = DefaultAudioSink::class.java.getDeclaredField("writeExceptionPendingExceptionHolder").apply { isAccessible = true }
            val writeExceptionHolder = writeExceptionField.get(defaultSink)
            val clearMethod = writeExceptionHolder.javaClass.getDeclaredMethod("clear").apply { isAccessible = true }
            clearMethod.invoke(writeExceptionHolder)

            val initExceptionField = DefaultAudioSink::class.java.getDeclaredField("initializationExceptionPendingExceptionHolder").apply { isAccessible = true }
            val initExceptionHolder = initExceptionField.get(defaultSink)
            clearMethod.invoke(initExceptionHolder)

            // Reset frame counts
            DefaultAudioSink::class.java.getDeclaredField("skippedOutputFrameCountAtLastPosition").apply { isAccessible = true }.set(defaultSink, 0L)
            DefaultAudioSink::class.java.getDeclaredField("accumulatedSkippedSilenceDurationUs").apply { isAccessible = true }.set(defaultSink, 0L)

            val reportSkippedSilenceHandlerField = DefaultAudioSink::class.java.getDeclaredField("reportSkippedSilenceHandler").apply { isAccessible = true }
            val reportSkippedSilenceHandler = reportSkippedSilenceHandlerField.get(defaultSink) as? android.os.Handler
            reportSkippedSilenceHandler?.removeCallbacksAndMessages(null)

            if (isOffloaded) {
                val offloadCallbackField = DefaultAudioSink::class.java.getDeclaredField("offloadStreamEventCallbackV29").apply { isAccessible = true }
                val offloadCallback = offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    val registerMethod = offloadCallback.javaClass.getDeclaredMethod("register", AudioTrack::class.java).apply { isAccessible = true }
                    registerMethod.invoke(offloadCallback, audioTrack)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reuse AudioTrack on flush, falling back to full recreation", e)
            false
        }
    }

    fun armPassthroughResync() {
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough resync manually armed for rebuffer/recovery")
        }
    }

    override fun pause() {
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough pause: compensation armed for ${currentInputFormat?.sampleMimeType}")
        }
        super.pause()
    }

    override fun play() {
        if (passthroughPauseCompensationPending || passthroughStartupCompensationPending) {
            val isStartup = passthroughStartupCompensationPending
            passthroughPauseCompensationPending = false
            passthroughStartupCompensationPending = false
            // Force DefaultAudioSink to resync startMediaTimeUs on the next handleBuffer() call.
            // This compensates for initial passthrough handshake or audio played from receiver buffer during pause.
            handleDiscontinuity()
            Log.d(TAG, "Passthrough ${if (isStartup) "startup" else "resume"}: forced media time resync via handleDiscontinuity()")
        }
        super.play()
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

    /** Returns true if audio is currently playing in direct passthrough mode. */
    fun isDirectPlaybackActive(): Boolean {
        val format = currentInputFormat ?: return false
        return isBitstreamFormat(format) && !shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        return isBitstreamFormat(format) && (forcePcmForCurrentSession || playbackSpeed != 1f)
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || speed == 1f || !isBitstreamFormat(format)) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    private fun isBitstreamFormat(format: Format): Boolean {
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

    companion object {
        private const val TAG = "PassthroughAudioSink"
    }
}

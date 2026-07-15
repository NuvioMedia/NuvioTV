package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Thin wrapper over [DefaultAudioSink].
 *
 * - speed != 1x → force PCM for bitstream (can't stretch TrueHD/etc).
 * - flush/seek: keep the AudioTrack for passthrough *or* tunnel sessions. Media3 always
 *   releases it; that's a slow handshake and the usual seek desync.
 * - pause/rebuffer on passthrough: [handleDiscontinuity] so the media clock re-anchors.
 *
 * Flush-reuse is reflection on Media3 1.8. If fields move we fall through to stock flush.
 */
internal class PlaybackSpeedAwareAudioSink(
    private val delegate: AudioSink,
    initialForcePcm: Boolean = false
) : ForwardingAudioSink(delegate) {

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    // Format looks like bitstream and we didn't force PCM. Updated from real sink outputMode
    // when we can read it after configure/flush.
    @Volatile
    private var isCurrentlyPassthrough: Boolean = false

    // Pause while passthrough is live → resync on the next play().
    @Volatile
    private var passthroughPauseCompensationPending: Boolean = false

    // First play after entering passthrough → one-shot clock nudge.
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
        val wasPassthrough = isCurrentlyPassthrough
        isCurrentlyPassthrough = isBitstreamFormat(inputFormat) && !shouldRejectDirectPlayback(inputFormat)
        if (isCurrentlyPassthrough && !wasPassthrough) {
            passthroughStartupCompensationPending = true
        }
        if (!isCurrentlyPassthrough) {
            passthroughStartupCompensationPending = false
            passthroughPauseCompensationPending = false
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        // Prefer what DefaultAudioSink actually picked (may stay PCM if device can't do direct).
        refreshPassthroughFromSinkConfiguration()
    }

    override fun flush() {
        passthroughPauseCompensationPending = false
        passthroughStartupCompensationPending = false
        when (val result = tryReuseAudioTrackOnFlush()) {
            TrackReuseResult.REUSED_PASSTHROUGH,
            TrackReuseResult.REUSED_TUNNEL -> {
                Log.i(TAG, "AudioTrack reused on flush (${result.name})")
                return
            }
            TrackReuseResult.SKIPPED_NOT_ELIGIBLE -> {
                // Normal PCM non-tunnel seek — stock flush is fine.
            }
            TrackReuseResult.SKIPPED_NO_TRACK,
            TrackReuseResult.SKIPPED_CONFIG_MISMATCH,
            TrackReuseResult.FAILED_REFLECTION -> {
                Log.w(TAG, "AudioTrack reuse skipped (${result.name}); Media3 release path")
            }
        }
        super.flush()
        refreshPassthroughFromSinkConfiguration()
    }

    /**
     * Media3 flush always releases the track. For passthrough *and* tunnel we keep it:
     * same steps as DefaultAudioSink.flush up to release, then re-bind like initializeAudioTrack.
     */
    private fun tryReuseAudioTrackOnFlush(): TrackReuseResult {
        val defaultSink = delegate as? DefaultAudioSink ?: return TrackReuseResult.SKIPPED_NOT_ELIGIBLE
        val accessors = DefaultAudioSinkAccessors.getOrNull()
            ?: return TrackReuseResult.FAILED_REFLECTION

        return try {
            val audioTrack = accessors.audioTrackField.get(defaultSink) as? AudioTrack
                ?: return TrackReuseResult.SKIPPED_NO_TRACK

            var configuration = accessors.configurationField.get(defaultSink)
                ?: return TrackReuseResult.SKIPPED_NO_TRACK
            val pendingConfiguration = accessors.pendingConfigurationField.get(defaultSink)

            if (pendingConfiguration != null) {
                val canReuse = accessors.canReuseAudioTrackMethod.invoke(
                    configuration,
                    pendingConfiguration
                ) as Boolean
                if (!canReuse) {
                    return TrackReuseResult.SKIPPED_CONFIG_MISMATCH
                }
            }

            val configForMode = pendingConfiguration ?: configuration
            val modeForReuse = accessors.outputModeField.get(configForMode) as Int
            val tunnelingForReuse = accessors.configurationTunnelingField.get(configForMode) as Boolean
            val passthrough = modeForReuse == OUTPUT_MODE_PASSTHROUGH
            if (!passthrough && !tunnelingForReuse) {
                return TrackReuseResult.SKIPPED_NOT_ELIGIBLE
            }

            val positionTracker = accessors.positionTrackerField.get(defaultSink)
                ?: return TrackReuseResult.SKIPPED_NO_TRACK

            accessors.resetSinkStateForFlushMethod.invoke(defaultSink)

            val trackerPlaying = accessors.positionTrackerIsPlayingMethod.invoke(positionTracker) as Boolean
            if (trackerPlaying) {
                audioTrack.pause()
            }

            val isOffloaded = accessors.isOffloadedPlaybackMethod.invoke(null, audioTrack) as Boolean
            if (isOffloaded) {
                val offloadCallback = accessors.offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    accessors.offloadUnregisterMethod.invoke(offloadCallback, audioTrack)
                }
            }

            if (pendingConfiguration != null) {
                accessors.configurationField.set(defaultSink, pendingConfiguration)
                accessors.pendingConfigurationField.set(defaultSink, null)
                configuration = pendingConfiguration
            }

            audioTrack.flush()

            val outputEncoding = accessors.outputEncodingField.get(configuration) as Int
            val outputPcmFrameSize = accessors.outputPcmFrameSizeField.get(configuration) as Int
            val bufferSize = accessors.bufferSizeField.get(configuration) as Int
            val enableOnAudioPositionAdvancingFix =
                accessors.enableOnAudioPositionAdvancingFixField.get(defaultSink) as Boolean
            val isPassthroughMode =
                (accessors.outputModeField.get(configuration) as Int) == OUTPUT_MODE_PASSTHROUGH

            accessors.positionTrackerSetAudioTrackMethod.invoke(
                positionTracker,
                audioTrack,
                isPassthroughMode,
                outputEncoding,
                outputPcmFrameSize,
                bufferSize,
                enableOnAudioPositionAdvancingFix
            )

            accessors.positionTrackerExpectRawHeadResetMethod?.invoke(positionTracker)

            // Match initializeAudioTrack so the next buffer re-anchors startMediaTimeUs.
            accessors.startMediaTimeUsNeedsInitField.set(defaultSink, true)
            accessors.startMediaTimeUsNeedsSyncField.set(defaultSink, false)

            accessors.lastTunnelingAvSyncPtsField?.set(defaultSink, C.TIME_UNSET)

            val writeExceptionHolder = accessors.writeExceptionHolderField.get(defaultSink)
            accessors.pendingExceptionClearMethod.invoke(writeExceptionHolder)
            val initExceptionHolder = accessors.initExceptionHolderField.get(defaultSink)
            accessors.pendingExceptionClearMethod.invoke(initExceptionHolder)
            accessors.skippedOutputFrameCountField.set(defaultSink, 0L)
            accessors.accumulatedSkippedSilenceField.set(defaultSink, 0L)
            (accessors.reportSkippedSilenceHandlerField.get(defaultSink) as? Handler)
                ?.removeCallbacksAndMessages(null)

            if (isOffloaded) {
                val offloadCallback = accessors.offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    accessors.offloadRegisterMethod.invoke(offloadCallback, audioTrack)
                }
            }

            if (isPassthroughMode) {
                isCurrentlyPassthrough = true
                TrackReuseResult.REUSED_PASSTHROUGH
            } else {
                TrackReuseResult.REUSED_TUNNEL
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack reuse threw; Media3 release path", e)
            TrackReuseResult.FAILED_REFLECTION
        }
    }

    private fun refreshPassthroughFromSinkConfiguration() {
        val mode = readSinkOutputMode() ?: return
        val direct = mode == OUTPUT_MODE_PASSTHROUGH
        if (!direct) {
            isCurrentlyPassthrough = false
            passthroughStartupCompensationPending = false
            passthroughPauseCompensationPending = false
        } else if (!isCurrentlyPassthrough) {
            isCurrentlyPassthrough = true
            passthroughStartupCompensationPending = true
        }
    }

    private fun readSinkOutputMode(): Int? {
        val defaultSink = delegate as? DefaultAudioSink ?: return null
        val accessors = DefaultAudioSinkAccessors.getOrNull() ?: return null
        return try {
            val configuration = accessors.configurationField.get(defaultSink) ?: return null
            accessors.outputModeField.get(configuration) as Int
        } catch (_: Exception) {
            null
        }
    }

    /** Rebuffer ends without a new play() when playWhenReady stayed true — resync now. */
    fun requestPassthroughResync(reason: String = "manual") {
        if (!isCurrentlyPassthrough && !isSinkTunneling()) return
        handleDiscontinuity()
        Log.d(TAG, "Audio clock resync ($reason)")
    }

    fun armPassthroughResync() {
        if (!isCurrentlyPassthrough && !isSinkTunneling()) return
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
        }
        handleDiscontinuity()
        Log.d(TAG, "Audio clock resync armed")
    }

    private fun isSinkTunneling(): Boolean {
        val defaultSink = delegate as? DefaultAudioSink ?: return false
        val accessors = DefaultAudioSinkAccessors.getOrNull() ?: return false
        return try {
            val configuration = accessors.configurationField.get(defaultSink) ?: return false
            accessors.configurationTunnelingField.get(configuration) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    override fun pause() {
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough pause → resume resync armed (${currentInputFormat?.sampleMimeType})")
        }
        super.pause()
    }

    override fun play() {
        if (passthroughPauseCompensationPending || passthroughStartupCompensationPending) {
            val isStartup = passthroughStartupCompensationPending
            passthroughPauseCompensationPending = false
            passthroughStartupCompensationPending = false
            // Sets startMediaTimeUsNeedsSync on DefaultAudioSink.
            handleDiscontinuity()
            Log.d(TAG, "Passthrough ${if (isStartup) "startup" else "resume"} resync")
        }
        super.play()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        val shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
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

    private enum class TrackReuseResult {
        REUSED_PASSTHROUGH,
        REUSED_TUNNEL,
        SKIPPED_NOT_ELIGIBLE,
        SKIPPED_NO_TRACK,
        SKIPPED_CONFIG_MISMATCH,
        FAILED_REFLECTION
    }

    companion object {
        private const val TAG = "PassthroughAudioSink"
        // DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH (package-private).
        private const val OUTPUT_MODE_PASSTHROUGH = 2
    }

    /** One-time lookup of Media3 1.8 flush/init bits we need. */
    private class DefaultAudioSinkAccessors private constructor(
        val audioTrackField: Field,
        val configurationField: Field,
        val pendingConfigurationField: Field,
        val positionTrackerField: Field,
        val offloadCallbackField: Field,
        val enableOnAudioPositionAdvancingFixField: Field,
        val startMediaTimeUsNeedsInitField: Field,
        val startMediaTimeUsNeedsSyncField: Field,
        val writeExceptionHolderField: Field,
        val initExceptionHolderField: Field,
        val skippedOutputFrameCountField: Field,
        val accumulatedSkippedSilenceField: Field,
        val reportSkippedSilenceHandlerField: Field,
        val outputModeField: Field,
        val configurationTunnelingField: Field,
        val outputEncodingField: Field,
        val outputPcmFrameSizeField: Field,
        val bufferSizeField: Field,
        val lastTunnelingAvSyncPtsField: Field?,
        val resetSinkStateForFlushMethod: Method,
        val isOffloadedPlaybackMethod: Method,
        val canReuseAudioTrackMethod: Method,
        val positionTrackerIsPlayingMethod: Method,
        val positionTrackerSetAudioTrackMethod: Method,
        val positionTrackerExpectRawHeadResetMethod: Method?,
        val offloadUnregisterMethod: Method,
        val offloadRegisterMethod: Method,
        val pendingExceptionClearMethod: Method
    ) {
        companion object {
            @Volatile
            private var cached: DefaultAudioSinkAccessors? = null

            @Volatile
            private var failed: Boolean = false

            fun getOrNull(): DefaultAudioSinkAccessors? {
                cached?.let { return it }
                if (failed) return null
                return synchronized(this) {
                    cached?.let { return it }
                    if (failed) return null
                    try {
                        build().also { cached = it }
                    } catch (e: Exception) {
                        failed = true
                        Log.w(TAG, "DefaultAudioSink reflection unavailable; track reuse off", e)
                        null
                    }
                }
            }

            private fun build(): DefaultAudioSinkAccessors {
                val sinkClass = DefaultAudioSink::class.java

                val configurationField = sinkClass.getDeclaredField("configuration").accessible()
                val configurationClass = configurationField.type

                val positionTrackerField = sinkClass.getDeclaredField("audioTrackPositionTracker").accessible()
                val positionTrackerClass = positionTrackerField.type

                val offloadCallbackField =
                    sinkClass.getDeclaredField("offloadStreamEventCallbackV29").accessible()
                val offloadCallbackClass = offloadCallbackField.type

                val writeExceptionHolderField =
                    sinkClass.getDeclaredField("writeExceptionPendingExceptionHolder").accessible()
                val pendingExceptionHolderClass = writeExceptionHolderField.type

                val expectRawHeadReset = runCatching {
                    positionTrackerClass.getDeclaredMethod("expectRawPlaybackHeadReset").accessible()
                }.getOrNull()

                val lastTunnelPts = runCatching {
                    sinkClass.getDeclaredField("lastTunnelingAvSyncPresentationTimeUs").accessible()
                }.getOrNull()

                return DefaultAudioSinkAccessors(
                    audioTrackField = sinkClass.getDeclaredField("audioTrack").accessible(),
                    configurationField = configurationField,
                    pendingConfigurationField = sinkClass.getDeclaredField("pendingConfiguration").accessible(),
                    positionTrackerField = positionTrackerField,
                    offloadCallbackField = offloadCallbackField,
                    enableOnAudioPositionAdvancingFixField =
                        sinkClass.getDeclaredField("enableOnAudioPositionAdvancingFix").accessible(),
                    startMediaTimeUsNeedsInitField =
                        sinkClass.getDeclaredField("startMediaTimeUsNeedsInit").accessible(),
                    startMediaTimeUsNeedsSyncField =
                        sinkClass.getDeclaredField("startMediaTimeUsNeedsSync").accessible(),
                    writeExceptionHolderField = writeExceptionHolderField,
                    initExceptionHolderField =
                        sinkClass.getDeclaredField("initializationExceptionPendingExceptionHolder").accessible(),
                    skippedOutputFrameCountField =
                        sinkClass.getDeclaredField("skippedOutputFrameCountAtLastPosition").accessible(),
                    accumulatedSkippedSilenceField =
                        sinkClass.getDeclaredField("accumulatedSkippedSilenceDurationUs").accessible(),
                    reportSkippedSilenceHandlerField =
                        sinkClass.getDeclaredField("reportSkippedSilenceHandler").accessible(),
                    outputModeField = configurationClass.getDeclaredField("outputMode").accessible(),
                    configurationTunnelingField = configurationClass.getDeclaredField("tunneling").accessible(),
                    outputEncodingField = configurationClass.getDeclaredField("outputEncoding").accessible(),
                    outputPcmFrameSizeField = configurationClass.getDeclaredField("outputPcmFrameSize").accessible(),
                    bufferSizeField = configurationClass.getDeclaredField("bufferSize").accessible(),
                    lastTunnelingAvSyncPtsField = lastTunnelPts,
                    resetSinkStateForFlushMethod =
                        sinkClass.getDeclaredMethod("resetSinkStateForFlush").accessible(),
                    isOffloadedPlaybackMethod =
                        sinkClass.getDeclaredMethod("isOffloadedPlayback", AudioTrack::class.java).accessible(),
                    canReuseAudioTrackMethod =
                        configurationClass.getDeclaredMethod("canReuseAudioTrack", configurationClass).accessible(),
                    positionTrackerIsPlayingMethod =
                        positionTrackerClass.getDeclaredMethod("isPlaying").accessible(),
                    positionTrackerSetAudioTrackMethod =
                        positionTrackerClass.getDeclaredMethod(
                            "setAudioTrack",
                            AudioTrack::class.java,
                            Boolean::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        ).accessible(),
                    positionTrackerExpectRawHeadResetMethod = expectRawHeadReset,
                    offloadUnregisterMethod =
                        offloadCallbackClass.getDeclaredMethod("unregister", AudioTrack::class.java).accessible(),
                    offloadRegisterMethod =
                        offloadCallbackClass.getDeclaredMethod("register", AudioTrack::class.java).accessible(),
                    pendingExceptionClearMethod =
                        pendingExceptionHolderClass.getDeclaredMethod("clear").accessible()
                )
            }

            private fun Field.accessible(): Field = apply { isAccessible = true }
            private fun Method.accessible(): Method = apply { isAccessible = true }
        }
    }
}

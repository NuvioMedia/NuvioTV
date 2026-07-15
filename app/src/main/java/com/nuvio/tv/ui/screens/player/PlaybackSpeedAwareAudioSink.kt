package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.Handler
import android.util.Log
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
 * AudioSink wrapper that:
 * 1. Forces PCM when playback speed ≠ 1x for bitstream formats (speed requires decoding).
 * 2. On passthrough seeks/flushes, reuses the existing [AudioTrack] instead of Media3's
 *    release+recreate path (see Media3 `DefaultAudioSink.flush` TODO b/143500232).
 * 3. Resyncs media clock after passthrough pause/resume, startup, and rebuffer.
 *
 * ## Why reuse on flush (Media3 1.8.0)
 *
 * [DefaultAudioSink.flush] always releases the track due to legacy device bugs
 * (b/7941810, b/19193985). For HDMI/SPDIF passthrough that forces a full receiver
 * handshake and a fresh [AudioTrack] timestamp poller, which is a primary source of
 * seek/resume A/V desync. Upstream even notes: "Experiment with not releasing AudioTrack
 * on flush."
 *
 * This class mirrors Media3's flush steps up to release, then re-binds the same track
 * the way [DefaultAudioSink.initializeAudioTrack] would after a recreate — including
 * setting `startMediaTimeUsNeedsInit = true` so the next [handleBuffer] re-anchors
 * media time (without waiting for the 200ms unexpected-discontinuity path).
 *
 * Reflection is intentionally confined here. If Media3 internals change, we fall back
 * to [ForwardingAudioSink.flush] (full release/recreate).
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

    /**
     * True when the configured format is eligible for bitstream passthrough (not forced PCM).
     * Used to gate track-reuse and clock resync workarounds.
     */
    @Volatile
    private var isCurrentlyPassthrough: Boolean = false

    /**
     * Armed on [pause] during passthrough. Cleared on next [play] after forcing
     * [handleDiscontinuity] so HDMI receiver buffer drain during pause is compensated.
     */
    @Volatile
    private var passthroughPauseCompensationPending: Boolean = false

    /**
     * Armed when entering passthrough [configure]. Cleared on first [play] after
     * forcing [handleDiscontinuity] for initial hardware clock alignment.
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
    }

    /**
     * Passthrough flush: reuse AudioTrack when possible (Media3 always releases).
     * Non-passthrough: default Media3 behaviour.
     *
     * Pause/resume flags are cleared here because flush already resets media clock
     * state (`resetSinkStateForFlush` + `startMediaTimeUsNeedsInit`).
     */
    override fun flush() {
        passthroughPauseCompensationPending = false
        passthroughStartupCompensationPending = false
        if (isCurrentlyPassthrough && tryReuseAudioTrackOnFlush()) {
            Log.i(TAG, "Reused AudioTrack on flush (skipped release/recreate handshake)")
            return
        }
        super.flush()
    }

    /**
     * Implements Media3 [DefaultAudioSink.flush] without [AudioTrack] release, then
     * re-binds the track like [DefaultAudioSink.initializeAudioTrack].
     *
     * Media3 flush order (1.8.0) when initialized:
     * 1. resetSinkStateForFlush()
     * 2. pause track if position tracker reports playing
     * 3. unregister offload stream callbacks
     * 4. apply pendingConfiguration
     * 5. positionTracker.reset() + release routing listener + releaseAudioTrackAsync
     *
     * We replace step 5 with: audioTrack.flush() + positionTracker.setAudioTrack(...)
     * + startMediaTimeUsNeedsInit=true. Routing listener is kept (same track).
     *
     * @return true if reuse succeeded; false means caller must [super.flush].
     */
    private fun tryReuseAudioTrackOnFlush(): Boolean {
        val defaultSink = delegate as? DefaultAudioSink ?: return false
        val accessors = DefaultAudioSinkAccessors.getOrNull() ?: return false

        return try {
            val audioTrack = accessors.audioTrackField.get(defaultSink) as? AudioTrack
                ?: return false

            var configuration = accessors.configurationField.get(defaultSink)
                ?: return false
            val pendingConfiguration = accessors.pendingConfigurationField.get(defaultSink)

            // Seek usually has pendingConfiguration == null. Config changes may set it;
            // if the pending config cannot share the track, force full Media3 flush.
            if (pendingConfiguration != null) {
                val canReuse = accessors.canReuseAudioTrackMethod.invoke(
                    configuration,
                    pendingConfiguration
                ) as Boolean
                if (!canReuse) {
                    return false
                }
            }

            val positionTracker = accessors.positionTrackerField.get(defaultSink)
                ?: return false

            // --- Mirror Media3 flush body (without release) ---

            // 1. Same first step as DefaultAudioSink.flush()
            accessors.resetSinkStateForFlushMethod.invoke(defaultSink)

            // 2. Pause if tracker thinks we are playing
            val trackerPlaying = accessors.positionTrackerIsPlayingMethod.invoke(positionTracker) as Boolean
            if (trackerPlaying) {
                audioTrack.pause()
            }

            // 3. Offload stream event callback
            val isOffloaded = accessors.isOffloadedPlaybackMethod.invoke(null, audioTrack) as Boolean
            if (isOffloaded) {
                val offloadCallback = accessors.offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    accessors.offloadUnregisterMethod.invoke(offloadCallback, audioTrack)
                }
            }

            // 4. Apply pending configuration (Media3 does this unconditionally on flush)
            if (pendingConfiguration != null) {
                accessors.configurationField.set(defaultSink, pendingConfiguration)
                accessors.pendingConfigurationField.set(defaultSink, null)
                configuration = pendingConfiguration
            }

            // 5a. Native buffer clear (Media3 does this on the async release worker)
            audioTrack.flush()

            // 5b. Re-bind position tracker (Media3 would reset() then setAudioTrack on re-init).
            // isPassthrough must match initializeAudioTrack: outputMode == OUTPUT_MODE_PASSTHROUGH.
            val outputMode = accessors.outputModeField.get(configuration) as Int
            val isPassthroughMode = outputMode == OUTPUT_MODE_PASSTHROUGH
            val outputEncoding = accessors.outputEncodingField.get(configuration) as Int
            val outputPcmFrameSize = accessors.outputPcmFrameSizeField.get(configuration) as Int
            val bufferSize = accessors.bufferSizeField.get(configuration) as Int
            val enableOnAudioPositionAdvancingFix =
                accessors.enableOnAudioPositionAdvancingFixField.get(defaultSink) as Boolean

            accessors.positionTrackerSetAudioTrackMethod.invoke(
                positionTracker,
                audioTrack,
                isPassthroughMode,
                outputEncoding,
                outputPcmFrameSize,
                bufferSize,
                enableOnAudioPositionAdvancingFix
            )

            // 5c. Critical: match initializeAudioTrack() so the next handleBuffer() re-anchors
            // startMediaTimeUs from the first post-seek presentationTimeUs, instead of relying
            // on the 200ms UnexpectedDiscontinuityException path (startMediaTimeUs was zeroed
            // by resetSinkStateForFlush but NeedsInit is only set on real re-init).
            accessors.startMediaTimeUsNeedsInitField.set(defaultSink, true)
            accessors.startMediaTimeUsNeedsSyncField.set(defaultSink, false)

            // Exception holders + silence skip counters (end of Media3 flush)
            val writeExceptionHolder = accessors.writeExceptionHolderField.get(defaultSink)
            accessors.pendingExceptionClearMethod.invoke(writeExceptionHolder)
            val initExceptionHolder = accessors.initExceptionHolderField.get(defaultSink)
            accessors.pendingExceptionClearMethod.invoke(initExceptionHolder)
            accessors.skippedOutputFrameCountField.set(defaultSink, 0L)
            accessors.accumulatedSkippedSilenceField.set(defaultSink, 0L)
            (accessors.reportSkippedSilenceHandlerField.get(defaultSink) as? Handler)
                ?.removeCallbacksAndMessages(null)

            // Re-register offload callback for the same track (initializeAudioTrack does this)
            if (isOffloaded) {
                val offloadCallback = accessors.offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    accessors.offloadRegisterMethod.invoke(offloadCallback, audioTrack)
                }
            }

            // Intentionally keep onRoutingChangedListener — same AudioTrack instance.
            true
        } catch (e: Exception) {
            // Track is still owned by DefaultAudioSink; super.flush() can release safely.
            Log.w(TAG, "AudioTrack reuse on flush failed; falling back to Media3 release path", e)
            false
        }
    }

    /**
     * Immediately requests media-clock resync for an active passthrough session.
     * Use after rebuffer recovery where [play] is not re-entered (playWhenReady stays true).
     */
    fun requestPassthroughResync(reason: String = "manual") {
        if (!isCurrentlyPassthrough) return
        handleDiscontinuity()
        Log.d(TAG, "Passthrough resync requested ($reason) via handleDiscontinuity()")
    }

    /**
     * @deprecated Prefer [requestPassthroughResync] for rebuffer (immediate). Kept for
     * call-sites that arm compensation before a subsequent [play].
     */
    fun armPassthroughResync() {
        if (!isCurrentlyPassthrough) return
        passthroughPauseCompensationPending = true
        // Also apply immediately so rebuffer-without-play still resyncs on next buffer.
        handleDiscontinuity()
        Log.d(TAG, "Passthrough resync armed + applied for rebuffer/recovery")
    }

    override fun pause() {
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough pause: resume compensation armed for ${currentInputFormat?.sampleMimeType}")
        }
        super.pause()
    }

    override fun play() {
        if (passthroughPauseCompensationPending || passthroughStartupCompensationPending) {
            val isStartup = passthroughStartupCompensationPending
            passthroughPauseCompensationPending = false
            passthroughStartupCompensationPending = false
            // DefaultAudioSink.handleDiscontinuity() sets startMediaTimeUsNeedsSync=true so the
            // next handleBuffer() adjusts startMediaTimeUs to the arriving presentationTimeUs.
            handleDiscontinuity()
            Log.d(
                TAG,
                "Passthrough ${if (isStartup) "startup" else "resume"}: media time resync via handleDiscontinuity()"
            )
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

    /** True when the configured format is treated as direct bitstream passthrough. */
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

        /**
         * Media3 [DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH] (package-private constant = 2).
         * Kept local so we do not depend on non-public API surface.
         */
        private const val OUTPUT_MODE_PASSTHROUGH = 2
    }

    /**
     * Cached reflective access to Media3 1.8.0 [DefaultAudioSink] flush/init internals.
     * Built once; if any member is missing (version skew), [getOrNull] returns null and
     * callers fall back to stock flush.
     */
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
        val outputEncodingField: Field,
        val outputPcmFrameSizeField: Field,
        val bufferSizeField: Field,
        val resetSinkStateForFlushMethod: Method,
        val isOffloadedPlaybackMethod: Method,
        val canReuseAudioTrackMethod: Method,
        val positionTrackerIsPlayingMethod: Method,
        val positionTrackerSetAudioTrackMethod: Method,
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
                        Log.w(TAG, "DefaultAudioSink reflection unavailable; passthrough track reuse disabled", e)
                        null
                    }
                }
            }

            private fun build(): DefaultAudioSinkAccessors {
                val sinkClass = DefaultAudioSink::class.java

                val configurationField = sinkClass.getDeclaredField("configuration").accessible()
                // Configuration is a private static nested class; sample one instance type via field.
                val configurationClass = configurationField.type

                val positionTrackerField = sinkClass.getDeclaredField("audioTrackPositionTracker").accessible()
                val positionTrackerClass = positionTrackerField.type

                val offloadCallbackField =
                    sinkClass.getDeclaredField("offloadStreamEventCallbackV29").accessible()
                val offloadCallbackClass = offloadCallbackField.type

                // PendingExceptionHolder is a private nested type; grab from field for clear().
                val writeExceptionHolderField =
                    sinkClass.getDeclaredField("writeExceptionPendingExceptionHolder").accessible()
                val pendingExceptionHolderClass = writeExceptionHolderField.type

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
                    outputEncodingField = configurationClass.getDeclaredField("outputEncoding").accessible(),
                    outputPcmFrameSizeField = configurationClass.getDeclaredField("outputPcmFrameSize").accessible(),
                    bufferSizeField = configurationClass.getDeclaredField("bufferSize").accessible(),
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

package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.Ac3Util
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import java.nio.ByteBuffer

/**
 * Audio sink wrapper that forces a decode-to-PCM path when:
 * - Playback speed != 1x for bitstream formats that cannot be tempo-adjusted in passthrough,
 * - Bluetooth media output is active (Media3 policy: Bluetooth only supports PCM), or
 * - The per-format passthrough policy denies the format (user switch or learned rejection).
 *
 * Bluetooth cannot carry TrueHD / Atmos / DTS-HD passthrough. Forcing PCM lets MediaCodec/FFmpeg
 * decode to the format the BT stack actually accepts; the system then encodes to SBC/AAC/aptX/LDAC.
 */
internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    initialForcePcm: Boolean = false,
    forcePcmForBluetooth: Boolean = false,
    private val passthroughPolicy: AudioPassthroughPolicy = AudioPassthroughPolicy.ALLOW_ALL,
    private val onDiagnosticEvent: ((String) -> Unit)? = null
) : ForwardingAudioSink(sink) {

    // Set when the sink is built with forcePcm (error recovery). Don't clear on speed reset.
    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var bluetoothForcePcm: Boolean = forcePcmForBluetooth

    @Volatile
    private var currentInputFormat: Format? = null

    val activeInputFormat: Format?
        get() = currentInputFormat

    // Audio class of the last configured format, readable from any thread; the sink itself
    // may only be called on the playback thread (DefaultAudioSink asserts it).
    @Volatile
    var currentTunnelAudioClass: String? = null
        private set

    @Volatile
    private var listener: AudioSink.Listener? = null

    private val passthroughPacer = PassthroughWaterLevelPacer(onDiagnosticEvent)
    private val iecSink: IecPassthroughAudioSink? = sink as? IecPassthroughAudioSink

    // Stock TrueHD passthrough (media3 1.8.0 DefaultAudioSink.handleBuffer): after a flush the
    // first buffer sets startMediaTimeUs, and buffers are then dropped, uncounted, until one
    // carries a major syncframe ("For TrueHD this can occur after some seek operations"). The
    // clock starts early by the dropped span, up to 128 access units (~107 ms), which is inside
    // the sink's 200 ms discontinuity tolerance, so it is never corrected: audio leads video for
    // the rest of the segment. Seeks that reload through the extractor start on a syncframe
    // (TrueHdSampleRechunker); seeks served from the sample queue do not. Watch the first
    // buffers after a flush and, when at least one was unsynced, ask the sink to re-anchor on
    // the first synced one through its own handleDiscontinuity path.
    private var forwardAnchorPending: Boolean = false
    private var forwardUnsyncedChunks: Int = 0
    private var forwardFirstPtsUs: Long = C.TIME_UNSET
    private var forwardLastEvaluatedPtsUs: Long = C.TIME_UNSET
    // Set when the watcher is armed by an IEC-to-RAW fallback inside handleBuffer. The IEC sink
    // configures the wrapped DefaultAudioSink itself and may feed it the buffer that triggered
    // the fallback in the same call, before this wrapper was watching; that buffer can anchor the
    // sink's media time and then be dropped, uncounted. So the first synced buffer re-anchors
    // even when no unsynced one was seen here: if nothing was dropped, the sink's own arithmetic
    // makes the adjustment about zero.
    private var forwardForceResync: Boolean = false
    private var forwardArmedBy: String = "configure"

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    /**
     * Update Bluetooth policy without rebuilding the player.
     * Call [notifyAudioProcessingRequirementChanged] after a change so Media3 reselects
     * decode-to-PCM vs passthrough on the live renderer.
     *
     * @return true when the effective PCM/passthrough policy changed.
     */
    fun setBluetoothForcePcm(enabled: Boolean): Boolean {
        val wasBluetoothForce = bluetoothForcePcm
        val wasSessionForce = forcePcmForCurrentSession
        bluetoothForcePcm = enabled
        if (enabled) {
            forcePcmForCurrentSession = true
        } else if (!startedWithForcedPcm && playbackSpeed == 1f) {
            // Session was not built as PCM-only; leaving Bluetooth can restore passthrough.
            forcePcmForCurrentSession = false
        }
        return wasBluetoothForce != bluetoothForcePcm || wasSessionForce != forcePcmForCurrentSession
    }

    fun isBluetoothForcePcm(): Boolean = bluetoothForcePcm

    fun isIecHbrActive(): Boolean = iecSink?.isIecActive == true

    fun demandsNonTunnelledVideo(format: Format): Boolean = iecSink?.claimsHbr(format) == true

    // Coarse class of what the sink chain will hand the platform for this format under the
    // current policy: the bitstream mime for passthrough, TUNNEL_AUDIO_CLASS_PCM for anything decoded.
    // Playback thread only: it queries the wrapped sink.
    fun tunnelAudioClass(format: Format): String {
        val mime = format.sampleMimeType ?: return TUNNEL_AUDIO_CLASS_PCM
        if (mime == MimeTypes.AUDIO_RAW) return TUNNEL_AUDIO_CLASS_PCM
        return if (getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) {
            mime
        } else {
            TUNNEL_AUDIO_CLASS_PCM
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        currentTunnelAudioClass = inputFormat.sampleMimeType
            ?.takeIf { it != MimeTypes.AUDIO_RAW }
            ?: TUNNEL_AUDIO_CLASS_PCM
        passthroughPacer.onFormat(inputFormat)
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        passthroughPacer.setIecPacked(iecSink?.isIecActive == true)
        armForwardAnchor(armedBy = "configure")
    }

    override fun play() {
        passthroughPacer.onPlay(nowMs())
        super.play()
    }

    override fun pause() {
        passthroughPacer.onPause(nowMs())
        super.pause()
    }

    override fun flush() {
        passthroughPacer.onTimelineReset(nowMs())
        super.flush()
        armForwardAnchor(armedBy = "flush")
    }

    override fun reset() {
        passthroughPacer.onReset()
        super.reset()
    }

    override fun playToEndOfStream() {
        // The IEC sink drains here too, so a write failure can fall back from this call as well.
        val iecWasActive = iecSink?.isIecActive == true
        super.playToEndOfStream()
        noteIecFallbackIfFlipped(iecWasActive)
    }

    override fun handleDiscontinuity() {
        passthroughPacer.onTimelineReset(nowMs())
        super.handleDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (forwardAnchorPending && presentationTimeUs != forwardLastEvaluatedPtsUs) {
            // Once per buffer: a refused buffer is offered again with the same PTS.
            forwardLastEvaluatedPtsUs = presentationTimeUs
            evaluateForwardAnchor(buffer, presentationTimeUs)
        }
        val passthrough = passthroughPacer.appliesTo(currentInputFormat)
        if (passthrough &&
            !passthroughPacer.shouldAcceptBuffer(presentationTimeUs, nowMs(), playbackSpeed)
        ) {
            return false
        }
        // Read before the sink consumes the buffer.
        val encodedBytes = if (currentInputFormat?.sampleMimeType != MimeTypes.AUDIO_RAW) {
            buffer.remaining()
        } else {
            0
        }
        val iecWasActive = iecSink?.isIecActive == true
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        noteIecFallbackIfFlipped(iecWasActive)
        if (handled && passthrough) {
            passthroughPacer.onBufferAccepted(presentationTimeUs)
        }
        if (handled && encodedBytes > 0) {
            PlayerAudioBitrateMeter.record(encodedBytes, presentationTimeUs)
        }
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val sinkPositionUs = super.getCurrentPositionUs(sourceEnded)
        if (!passthroughPacer.appliesTo(currentInputFormat)) {
            return sinkPositionUs
        }
        return passthroughPacer.clampPositionUs(sinkPositionUs, nowMs(), playbackSpeed)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        var shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        // Going above 1x latches forcePcm for the session. Clear it when back at 1.0x
        // so passthrough can recover (unless recovery built us with forcePcm).
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
        if (!isEncodedPassthroughCandidate(format)) {
            return false
        }
        // Bluetooth: always decode to PCM (Media3 DEFAULT_AUDIO_CAPABILITIES policy).
        if (bluetoothForcePcm || forcePcmForCurrentSession) {
            return true
        }
        // Non-1x speed cannot be applied to bitstream passthrough tracks.
        if (playbackSpeed != 1f) {
            return true
        }
        return isPolicyDeniedPassthrough(format)
    }

    // Keyed on sample MIME only: the codecs-string fallback cannot distinguish DTS from DTS-HD, so null-MIME formats defer to the platform report.
    fun isPolicyDeniedPassthrough(format: Format): Boolean {
        return passthroughPolicy.deniesPassthrough(format.sampleMimeType)
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || !isEncodedPassthroughCandidate(format)) {
            return false
        }
        if (bluetoothForcePcm) {
            val wasForcingPcm = forcePcmForCurrentSession
            forcePcmForCurrentSession = true
            return !wasForcingPcm
        }
        if (speed == 1f) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Formats that devices may try to play via passthrough/offload and that Bluetooth cannot carry.
     * Matches Media3 surround encodings that need decode-to-PCM on A2DP/LE Audio.
     */
    private fun isEncodedPassthroughCandidate(format: Format): Boolean {
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
                    mimeType == MimeTypes.AUDIO_DTS_X ||
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

    // Armed after configure and after flush, only when the wrapped chain will hand the platform
    // TrueHD itself (the IEC sink anchors its own clock; decoded audio arrives here as PCM).
    // The IEC sink leaves the IEC path from inside handleBuffer or playToEndOfStream (write error
    // or stall limit) and configures the wrapped DefaultAudioSink itself, so neither configure()
    // nor flush() runs here. Seen as isIecActive going true-to-false across the call: the stream
    // is RAW from now on for the pacer, and the TrueHD watcher must be re-armed with a forced
    // resync, because the buffer that triggered the fallback may already have been fed to the
    // wrapped sink and dropped uncounted.
    private fun noteIecFallbackIfFlipped(iecWasActive: Boolean) {
        if (iecWasActive && iecSink?.isIecActive == false) {
            passthroughPacer.setIecPacked(false)
            armForwardAnchor(armedBy = "fallback", forceResync = true)
        }
    }

    private fun armForwardAnchor(armedBy: String, forceResync: Boolean = false) {
        forwardAnchorPending =
            currentInputFormat?.sampleMimeType == MimeTypes.AUDIO_TRUEHD &&
                iecSink?.isIecActive != true
        forwardUnsyncedChunks = 0
        forwardFirstPtsUs = C.TIME_UNSET
        forwardLastEvaluatedPtsUs = C.TIME_UNSET
        forwardForceResync = forwardAnchorPending && forceResync
        forwardArmedBy = armedBy
    }

    private fun evaluateForwardAnchor(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (presentationTimeUs == C.TIME_UNSET) return
        if (forwardFirstPtsUs == C.TIME_UNSET) forwardFirstPtsUs = presentationTimeUs
        // Same test the sink applies before dropping; reads without moving the position.
        if (Ac3Util.findTrueHdSyncframeOffset(buffer) == C.INDEX_UNSET) {
            forwardUnsyncedChunks++
            return
        }
        val deltaUs = presentationTimeUs - forwardFirstPtsUs
        val resynced = forwardUnsyncedChunks > 0 || forwardForceResync
        if (resynced) {
            // The sink re-reads startMediaTimeUs from this buffer's PTS; nothing was counted
            // for the dropped ones, so the adjustment is exactly the dropped span.
            handleDiscontinuity()
        }
        val line = "forward_anchor mime=true-hd droppedChunks=$forwardUnsyncedChunks " +
            "deltaUs=$deltaUs resynced=$resynced armedBy=$forwardArmedBy"
        onDiagnosticEvent?.invoke(line)
        Log.i(TAG, line)
        forwardAnchorPending = false
        forwardForceResync = false
    }

    companion object {
        const val TUNNEL_AUDIO_CLASS_PCM = "pcm"
        private const val TAG = "PassthroughSink"
    }
}

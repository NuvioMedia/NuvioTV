package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.DtsUtil
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * HDMI HBR passthrough that packs TrueHD / DTS-HD / DTS:X into IEC 61937 and
 * writes a CBR [AudioFormat.ENCODING_IEC61937] track.
 *
 * Android's RAW packer (`ENCODING_DOLBY_TRUEHD` / `ENCODING_DTS_HD`) is
 * byte-paced, so silence sprints and the media clock drifts. IEC bursts are
 * constant-rate at 192 kHz, so written frames equal content time.
 *
 * Formats this sink does not pack (AC-3, E-AC-3, DTS core, PCM) go through
 * the wrapped [AudioSink] unchanged. If IEC HBR cannot be opened, the same
 * wrapped sink is used — codecs are never rejected here.
 */
internal class IecPassthroughAudioSink(
    sink: AudioSink,
    private val trackFactory: IecAudioTrackFactory = PlatformIecAudioTrackFactory(),
    private val hbrIecEnabled: Boolean = true,
    private val onDiagnosticEvent: ((String) -> Unit)? = null,
    private val onIecBecameReady: (() -> Unit)? = null
) : ForwardingAudioSink(sink) {

    private val matPacker = TrueHdMatPacker()
    private var iecTrack: IecAudioTrack? = null
    private var mode: Mode = Mode.FORWARD
    private val pendingFrames = ArrayDeque<ByteArray>()
    // DTS-HD bursts handed back after they were written; MAT frames go back to matPacker.
    private val dtsBurstPool = ArrayDeque<ByteArray>()
    private var pendingOffset: Int = 0
    private var leftover: ByteArray = ByteArray(0)
    private var startPtsUs: Long = C.TIME_UNSET
    private var writtenFrames: Long = 0L
    private var headAnchorFrames: Long = 0L
    private var playing: Boolean = false
    private var handledEndOfStream: Boolean = false
    private var audioSessionId: Int = 0
    private var volume: Float = 1f
    private var dtsChannelCount: Int = 8
    private var configuredFormat: Format? = null
    private var configuredBufferSize: Int = 0
    private var configuredOutputChannels: IntArray? = null
    private var iecFailedThisSession: Boolean = false
    private var consecutiveWriteStalls: Int = 0
    private var totalWriteStalls: Long = 0L
    private var lastHealthNanos: Long = 0L
    private var lastHealthUnderruns: Int = -1
    private var tunnelingRequested: Boolean = false

    init {
        trackFactory.setReadyListener { onIecBecameReady?.invoke() }
        // The probe opens a direct stream; a sink that cannot use IEC must not pay for it.
        if (hbrIecEnabled) trackFactory.startProbe()
    }

    val isIecActive: Boolean
        get() = mode != Mode.FORWARD && iecTrack != null

    // True when this sink would carry the format on its own IEC track, which a tunnelled
    // video cannot be clocked against; the track selector uses it to keep the video untunnelled.
    fun claimsHbr(format: Format): Boolean {
        return hbrIecEnabled && !iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)
    }

    // Once IEC has failed in this session the format is answered by the wrapped sink, the same
    // way claimsHbr already does, so a recovery re-selects RAW or a decoder instead of IEC.
    override fun getFormatSupport(format: Format): Int {
        if (!iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (!iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)) return true
        return super.supportsFormat(format)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels
        releaseIec()
        // IEC61937 AudioTrack.Builder can block for seconds on HALs that advertise
        // the encoding then reject the track. Never wait for that on this thread:
        // TrueHD may use DOLBY_MAT immediately; IEC only if a background probe
        // already proved it initializes. DTS-HD uses RAW until then.
        // A tunnelled video releases frames against the platform's hw_av_sync clock, and no
        // HAL has been seen to start that clock for an app-packed IEC 61937 stream (Amlogic
        // accepts the bound track, then swallows the audio). Under tunnelling the wrapped
        // sink owns HBR; the selector normally keeps the video untunnelled before it gets here.
        val tunnelReady = !tunnelingRequested
        val tryCustomHbr = hbrIecEnabled && !iecFailedThisSession && tunnelReady &&
            (isTrueHd(inputFormat) || (isHbrPassthrough(inputFormat) && trackFactory.iec61937Ready()))
        if (tryCustomHbr) {
            val opened = openIec(inputFormat)
            if (opened) {
                mode = if (isTrueHd(inputFormat)) Mode.TRUEHD else Mode.DTS_HD
                dtsChannelCount = inputFormat.channelCount.takeIf { it > 0 } ?: 8
                android.util.Log.i(
                    "IecPassthrough",
                    "HBR active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType}"
                )
                onDiagnosticEvent?.invoke(
                    "iec_hbr_active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType}"
                )
                return
            }
        }
        mode = Mode.FORWARD
        if (isHbrPassthrough(inputFormat)) {
            android.util.Log.i(
                "IecPassthrough",
                "HBR RAW mime=${inputFormat.sampleMimeType} (compressed, not PCM)"
            )
            onDiagnosticEvent?.invoke(
                "iec_hbr_raw_fallback mime=${inputFormat.sampleMimeType} " +
                    "iecFailedThisSession=$iecFailedThisSession tunnelReady=$tunnelReady"
            )
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (mode == Mode.FORWARD) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        maybeReportHealth()
        if (startPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
            startPtsUs = presentationTimeUs
        }
        if (!drainPending()) return false
        val accepted = when (mode) {
            Mode.TRUEHD -> handleTrueHd(buffer)
            Mode.DTS_HD -> handleDtsHd(buffer)
            Mode.FORWARD -> super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        drainPending()
        return accepted
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!isIecActive) return super.getCurrentPositionUs(sourceEnded)
        val track = iecTrack ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (writtenFrames == 0L || startPtsUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val head = minOf(track.playbackHeadFrames(), writtenFrames) - headAnchorFrames
        return startPtsUs + head * C.MICROS_PER_SECOND / track.sampleRate
    }

    override fun play() {
        playing = true
        if (isIecActive) {
            iecTrack?.play()
        } else {
            super.play()
        }
    }

    override fun pause() {
        playing = false
        if (isIecActive) {
            iecTrack?.pause()
        } else {
            super.pause()
        }
    }

    override fun flush() {
        if (isIecActive) {
            resetIecState(keepTrack = true)
            iecTrack?.flush()
        } else {
            super.flush()
        }
    }

    override fun handleDiscontinuity() {
        if (isIecActive) {
            // No flush here: the AudioTrack head keeps counting, so re-anchor it
            // or the position jumps by everything played before the discontinuity.
            headAnchorFrames = iecTrack?.playbackHeadFrames() ?: 0L
            startPtsUs = C.TIME_UNSET
        } else {
            super.handleDiscontinuity()
        }
    }

    override fun reset() {
        releaseIec()
        mode = Mode.FORWARD
        tunnelingRequested = false
        super.reset()
    }

    override fun release() {
        releaseIec()
        mode = Mode.FORWARD
        super.release()
    }

    override fun playToEndOfStream() {
        if (isIecActive) {
            drainPending()
            handledEndOfStream = true
        } else {
            super.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        return if (isIecActive) {
            handledEndOfStream && !hasPendingData()
        } else {
            super.isEnded()
        }
    }

    override fun hasPendingData(): Boolean {
        if (!isIecActive) return super.hasPendingData()
        val track = iecTrack ?: return false
        return pendingFrames.isNotEmpty() || leftover.isNotEmpty() || writtenFrames > track.playbackHeadFrames()
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        if (!isIecActive) super.setAudioSessionId(audioSessionId)
    }

    override fun enableTunnelingV21() {
        tunnelingRequested = true
        super.enableTunnelingV21()
    }

    override fun disableTunneling() {
        tunnelingRequested = false
        super.disableTunneling()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (isIecActive) {
            iecTrack?.setVolume(volume)
        } else {
            super.setVolume(volume)
        }
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!isIecActive) return super.getAudioTrackBufferSizeUs()
        val track = iecTrack ?: return C.TIME_UNSET
        // Two MAT frames (40 ms) or four DTS-HD bursts (~43 ms).
        val bytes = if (mode == Mode.TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE * 2
        } else {
            (8192 shl 2) * 4
        }
        val frames = bytes / track.frameSizeBytes
        return frames * C.MICROS_PER_SECOND / track.sampleRate
    }

    private fun iecAvailable(format: Format): Boolean {
        if (!hbrIecEnabled) return false
        return trackFactory.canOpen(IEC_SAMPLE_RATE, hbrIecChannelCount(format))
    }

    private fun openIec(format: Format): Boolean {
        val channelCount = hbrIecChannelCount(format)
        val frameBytes = if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE
        } else {
            Iec61937Packer.dtsHdIecPeriod(channelCount, 512) shl 2
        }
        val bufferBytes = frameBytes * if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) 2 else 4
        val track = trackFactory.openHbr(
            sampleRate = IEC_SAMPLE_RATE,
            channelCount = channelCount,
            bufferSizeBytes = bufferBytes,
            sessionId = audioSessionId,
            trueHd = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        ) ?: return false
        track.setVolume(volume)
        iecTrack = track
        return true
    }

    private fun hbrIecChannelCount(format: Format): Int {
        if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) return 8
        val count = format.channelCount
        return if (count > 0) Iec61937Packer.dtsHdChannelMask(count) else 8
    }

    private fun handleTrueHd(buffer: ByteBuffer): Boolean {
        val data = concat(leftover, buffer)
        var offset = 0
        while (offset + 10 <= data.size) {
            val auSize = TrueHdMatPacker.trueHdAccessUnitSize(data, offset)
            if (auSize < 10) {
                // Not an access unit. The extractor hands over access-unit-aligned samples, so
                // drop the remainder and resync on the next sample rather than carrying the bad
                // head forward under every later buffer (a frozen clock with no error).
                onDiagnosticEvent?.invoke(
                    "iec_truehd_resync auSize=$auSize dropped=${data.size - offset}"
                )
                offset = data.size
                break
            }
            if (offset + auSize > data.size) break
            val au = data.copyOfRange(offset, offset + auSize)
            offset += auSize
            if (matPacker.packAccessUnit(au)) {
                while (matPacker.hasFrame()) {
                    val mat = matPacker.pollFrame()!!
                    pendingFrames.add(
                        if (iecTrack?.payload == HbrPayload.MAT) mat
                        else Iec61937Packer.packTrueHdInPlace(mat)
                    )
                }
            }
        }
        leftover = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
        buffer.position(buffer.limit())
        return true
    }

    private fun handleDtsHd(buffer: ByteBuffer): Boolean {
        // DtsUtil's byte[] overload reads indices 0 and 4..7 only, so it gets just that head
        // rather than a copy of the whole access unit. A unit shorter than eight bytes gives a
        // head exactly as short, which keeps the original out-of-bounds-to-512 behaviour.
        val head = ByteArray(buffer.remaining().coerceAtMost(8))
        val position = buffer.position()
        buffer.get(head)
        buffer.position(position)
        val sampleCount = try {
            DtsUtil.parseDtsAudioSampleCount(head)
        } catch (_: Exception) {
            512
        }
        val period = Iec61937Packer.dtsHdIecPeriod(dtsChannelCount, sampleCount)
        val burst = acquireDtsBurst(period shl 2)
        Iec61937Packer.packDtsHdInto(buffer, period, burst)
        pendingFrames.add(burst)
        return true
    }

    private fun acquireDtsBurst(size: Int): ByteArray {
        val pooled = dtsBurstPool.poll()
        return if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    // Pending frames always belong to the current mode: resetIecState and drainPending run
    // before mode changes, so routing by mode is exact (a 960-sample 8-channel DTS burst is
    // 61440 bytes too, which is why size alone would not be).
    private fun recycleFrame(frame: ByteArray) {
        if (mode == Mode.TRUEHD) {
            matPacker.recycleFrame(frame)
        } else if (dtsBurstPool.size < FRAME_POOL_LIMIT) {
            dtsBurstPool.add(frame)
        }
    }

    private fun drainPending(): Boolean {
        val track = iecTrack ?: return true
        if (playing) track.play()
        while (pendingFrames.isNotEmpty()) {
            val frame = pendingFrames.first()
            while (pendingOffset < frame.size) {
                val written = track.write(frame, pendingOffset, frame.size - pendingOffset)
                if (written < 0) {
                    return fallbackToWrappedSink("write_error code=$written")
                }
                if (written == 0) {
                    // A paused track keeps a full buffer by design; only stalls while the track
                    // should be draining count towards giving up on IEC.
                    if (playing) {
                        totalWriteStalls++
                        if (++consecutiveWriteStalls >= MAX_WRITE_STALLS) {
                            return fallbackToWrappedSink("write_stalls=$consecutiveWriteStalls")
                        }
                    }
                    return false
                }
                consecutiveWriteStalls = 0
                pendingOffset += written
                writtenFrames += written / track.frameSizeBytes
            }
            recycleFrame(pendingFrames.removeFirst())
            pendingOffset = 0
        }
        return true
    }

    private fun fallbackToWrappedSink(reason: String): Boolean {
        val format = configuredFormat
        android.util.Log.w("IecPassthrough", "IEC write failed; falling back to RAW")
        onDiagnosticEvent?.invoke("iec_fallback_to_raw reason=$reason mime=${format?.sampleMimeType}")
        trackFactory.markIecUnusable()
        iecFailedThisSession = true
        resetIecState(keepTrack = false)
        mode = Mode.FORWARD
        if (format != null) {
            super.reset()
            try {
                super.configure(format, configuredBufferSize, configuredOutputChannels)
            } catch (e: AudioSink.ConfigurationException) {
                // This runs inside handleBuffer, where media3 catches only InitializationException
                // and WriteException; a ConfigurationException escaping here reaches the playback
                // thread uncaught and ends the process. Surface the refusal as a recoverable write
                // failure instead: the renderer reports it and the recovery re-selects tracks with
                // IEC already marked unusable, so the format is decoded from then on.
                onDiagnosticEvent?.invoke(
                    "iec_fallback_configure_refused mime=${format.sampleMimeType} reason=$reason"
                )
                throw AudioSink.WriteException(WRITE_ERROR_FALLBACK_REFUSED, format, true)
                    .apply { initCause(e) }
            }
            if (playing) super.play()
        }
        return true
    }

    private fun resetIecState(keepTrack: Boolean) {
        matPacker.reset()
        while (pendingFrames.isNotEmpty()) recycleFrame(pendingFrames.removeFirst())
        pendingOffset = 0
        leftover = ByteArray(0)
        startPtsUs = C.TIME_UNSET
        writtenFrames = 0L
        headAnchorFrames = 0L
        handledEndOfStream = false
        consecutiveWriteStalls = 0
        if (!keepTrack) {
            iecTrack?.release()
            iecTrack = null
            totalWriteStalls = 0L
            lastHealthNanos = 0L
            lastHealthUnderruns = -1
        }
    }

    // One line while IEC is active: at most every HEALTH_INTERVAL_NANOS, and at once when the
    // track's underrun count changes. The HUD and analytics underrun counters only see
    // DefaultAudioSink, which holds no track while IEC is active, so this is the only view of
    // the IEC track's health a user can produce from logcat.
    private fun maybeReportHealth() {
        val track = iecTrack ?: return
        val underruns = track.underrunCount()
        val now = System.nanoTime()
        if (underruns == lastHealthUnderruns && now - lastHealthNanos < HEALTH_INTERVAL_NANOS) return
        lastHealthNanos = now
        lastHealthUnderruns = underruns
        val line = "iec_health mode=$mode payload=${track.payload} underruns=$underruns " +
            "head=${track.playbackHeadFrames()} written=$writtenFrames " +
            "pending=${pendingFrames.size} stalls=$totalWriteStalls playing=$playing"
        android.util.Log.i("IecPassthrough", line)
        onDiagnosticEvent?.invoke(line)
    }

    private fun releaseIec() {
        resetIecState(keepTrack = false)
        mode = Mode.FORWARD
    }

    private enum class Mode { FORWARD, TRUEHD, DTS_HD }

    companion object {
        const val IEC_SAMPLE_RATE = 192_000
        internal const val MAX_WRITE_STALLS = 1_000
        // Reported as the WriteException error code when the wrapped sink refuses the format
        // during a fallback; not an AudioTrack return value.
        internal const val WRITE_ERROR_FALLBACK_REFUSED = -1_000
        private const val HEALTH_INTERVAL_NANOS = 5_000_000_000L
        private const val FRAME_POOL_LIMIT = 8

        fun isTrueHd(format: Format): Boolean {
            return format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        }

        fun isHbrPassthrough(format: Format): Boolean {
            val mime = format.sampleMimeType ?: return false
            return isTrueHd(format) ||
                mime == MimeTypes.AUDIO_DTS_HD ||
                mime == MimeTypes.AUDIO_DTS_X ||
                mime.startsWith("audio/vnd.dts.hd") ||
                mime.startsWith("audio/vnd.dts.uhd")
        }

        private fun concat(prefix: ByteArray, buffer: ByteBuffer): ByteArray {
            if (prefix.isEmpty() && buffer.hasArray() && buffer.arrayOffset() == 0 &&
                buffer.position() == 0 && buffer.remaining() == buffer.array().size
            ) {
                val copy = ByteArray(buffer.remaining())
                val pos = buffer.position()
                buffer.get(copy)
                buffer.position(pos)
                return copy
            }
            val combined = ByteArray(prefix.size + buffer.remaining())
            System.arraycopy(prefix, 0, combined, 0, prefix.size)
            val pos = buffer.position()
            buffer.get(combined, prefix.size, buffer.remaining())
            buffer.position(pos)
            return combined
        }
    }
}

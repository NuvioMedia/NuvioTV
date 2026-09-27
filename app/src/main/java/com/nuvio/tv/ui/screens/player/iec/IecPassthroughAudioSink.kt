package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.DtsUtil
import com.nuvio.tv.ui.screens.player.PassthroughWaterLevelPacer
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HDMI HBR passthrough that packs TrueHD / DTS-HD / DTS:X into IEC 61937 and
 * writes a CBR [AudioFormat.ENCODING_IEC61937] track.
 *
 * Android's RAW packer (`ENCODING_DOLBY_TRUEHD` / `ENCODING_DTS_HD`) is
 * byte-paced, so silence sprints and the media clock drifts. IEC bursts are
 * constant-rate at 192 kHz (176.4 kHz for 44.1 kHz content: TrueHD via
 * DOLBY_MAT, DTS when the probe proved that rate), so written frames equal
 * content time.
 *
 * Formats this sink does not pack (AC-3, E-AC-3, DTS core, PCM) go through
 * the wrapped [AudioSink] unchanged. If IEC HBR cannot be opened, the same
 * wrapped sink is used — codecs are never rejected here.
 */
internal class IecPassthroughAudioSink(
    sink: AudioSink,
    private val trackFactory: IecAudioTrackFactory = PlatformIecAudioTrackFactory(),
    private val hbrIecEnabled: Boolean = true,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onDiagnosticEvent: ((String) -> Unit)? = null,
    private val onIecBecameReady: (() -> Unit)? = null
) : ForwardingAudioSink(sink) {

    private val matPacker = TrueHdMatPacker()
    private var iecTrack: IecAudioTrack? = null
    private var iecBufferSizeBytes: Int = 0
    private var iecOutputSampleRate: Int = IEC_SAMPLE_RATE
    private var mode: Mode = Mode.FORWARD
    private val pendingFrames = ArrayDeque<ByteArray>()
    private val uncommitted = ArrayDeque<UncommittedAu>()
    private val sourcesOnFrame = ArrayDeque<Int>()
    private var sourcesSinceFrame: Int = 0
    // DTS-HD bursts handed back after they were written; MAT frames go back to matPacker.
    private val dtsBurstPool = ArrayDeque<ByteArray>()
    private var pendingOffset: Int = 0
    private var leftover: ByteArray = ByteArray(0)
    private var trueHdScratch: ByteArray = ByteArray(0)
    private var startPtsUs: Long = C.TIME_UNSET
    // PTS of the first buffer after a reset, kept only to report how far the anchor moved.
    private var firstBufferPtsUs: Long = C.TIME_UNSET
    private var discardedAuSinceReset: Int = 0
    private var writtenBytes: Long = 0L
    private var headAnchorFrames: Long = 0L
    private var playing: Boolean = false
    private var handledEndOfStream: Boolean = false
    private var audioSessionId: Int = 0
    private var volume: Float = 1f
    private var dtsChannelCount: Int = 8
    private val dtsDuration = DtsHdFrameDurationEstimator()
    private val uhdAudioChunkId = AtomicInteger()
    private var configuredFormat: Format? = null
    private var configuredBufferSize: Int = 0
    private var configuredOutputChannels: IntArray? = null
    private var iecFailedThisSession: Boolean = false
    private var writeStallStartedNanos: Long = WRITE_STALL_NOT_STARTED
    private var totalWriteStalls: Long = 0L
    private var lastHealthNanos: Long = 0L
    private var lastHealthUnderruns: Int = -1
    private var tunnelingRequested: Boolean = false
    // The factory probe is process-wide and can finish while reset/release has dropped
    // the listener. Deliver onIecBecameReady at most once so a later configure can
    // reselect DTS onto IEC without looping every configure. The probe thread writes
    // this latch; configure catch-up reads it.
    private val iecReadyDelivered = AtomicBoolean(false)

    init {
        attachReadyListener()
        // The probe opens a direct stream; a sink that cannot use IEC must not pay for it.
        if (hbrIecEnabled) trackFactory.startProbe()
    }

    val isIecActive: Boolean
        get() = mode != Mode.FORWARD && iecTrack != null

    // Whole frames written, derived from the byte count so unaligned partial writes keep their
    // remainder instead of losing it on every call.
    private val writtenFrames: Long
        get() = iecTrack?.let { writtenBytes / it.frameSizeBytes } ?: 0L

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
        // reset and release drop the listener; a sink that is reused needs it back.
        // If the probe already succeeded while it was cleared, catch up once.
        attachReadyListener()
        deliverIecReadyIfProbeAlreadySucceeded()
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels
        releaseIec()
        LiveDirectAudioPlayback.setPassthroughLive(false)
        if (hbrIecEnabled) trackFactory.startProbe()
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
                dtsDuration.clockSampleRate = clockSampleRateFor(iecOutputSampleRate)
                android.util.Log.i(
                    "IecPassthrough",
                    "HBR active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType}"
                )
                onDiagnosticEvent?.invoke(
                    "iec_hbr_active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType}"
                )
                refreshPassthroughLive()
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
        refreshPassthroughLive()
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
        if (firstBufferPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
            firstBufferPtsUs = presentationTimeUs
        }
        // DTS-HD packs every access unit, so the first buffer is the anchor. TrueHD anchors in
        // handleTrueHd on the first unit the MAT packer accepts: after a flush the packer discards
        // units until a major sync (the spec allows 128 between syncs, ~107 ms), and anchoring on
        // the first buffer would start the clock early by that span. Audio then leads video by
        // the discarded time for the rest of the segment; media3's discontinuity tolerance
        // (200 ms) never corrects it.
        if (mode == Mode.DTS_HD && startPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
            startPtsUs = presentationTimeUs
        }
        if (!drainPending()) return false
        if (mode == Mode.FORWARD) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        rememberSource(buffer, presentationTimeUs, encodedAccessUnitCount)
        val accepted = when (mode) {
            Mode.TRUEHD -> handleTrueHd(buffer, presentationTimeUs)
            Mode.DTS_HD -> handleDtsHd(buffer, presentationTimeUs)
            Mode.FORWARD -> super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        drainPending()
        if (mode == Mode.FORWARD) return true
        return accepted
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!isIecActive) return super.getCurrentPositionUs(sourceEnded)
        val track = iecTrack ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (track.isPlayHeld() || writtenFrames == 0L || startPtsUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val head = minOf(track.playbackHeadFrames(), writtenFrames) - headAnchorFrames
        val headUs = if (track.sampleRate > 0) {
            head * C.MICROS_PER_SECOND / track.sampleRate
        } else {
            0L
        }
        val presentedUs = if (headUs <= 0L) headUs else (headUs - track.outputLatencyUs()).coerceAtLeast(0L)
        return startPtsUs + presentedUs
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
        writeStallStartedNanos = WRITE_STALL_NOT_STARTED
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
            headAnchorFrames = iecTrack?.playbackHeadFrames() ?: 0L
            clearUncommitted()
            matPacker.reset()
            while (pendingFrames.isNotEmpty()) recycleFrame(pendingFrames.removeFirst())
            pendingOffset = 0
            leftover = ByteArray(0)
            startPtsUs = C.TIME_UNSET
            firstBufferPtsUs = C.TIME_UNSET
            discardedAuSinceReset = 0
            dtsDuration.clearPts()
            writeStallStartedNanos = WRITE_STALL_NOT_STARTED
        } else {
            super.handleDiscontinuity()
        }
    }

    override fun reset() {
        releaseIec()
        mode = Mode.FORWARD
        tunnelingRequested = false
        trackFactory.setReadyListener(null)
        super.reset()
        LiveDirectAudioPlayback.setPassthroughLive(false)
    }

    override fun release() {
        releaseIec()
        mode = Mode.FORWARD
        trackFactory.setReadyListener(null)
        super.release()
        LiveDirectAudioPlayback.setPassthroughLive(false)
    }

    override fun playToEndOfStream() {
        if (!isIecActive) {
            super.playToEndOfStream()
            return
        }
        // A trailing partial unit can never complete; it would pin hasPendingData true.
        leftover = ByteArray(0)
        drainPending()
        if (isIecActive) {
            handledEndOfStream = true
        } else {
            // The drain fell back, so the wrapped sink owns the end of stream now.
            super.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        if (!isIecActive) return super.isEnded()
        // The renderer stops feeding buffers here, so this poll is the only pump left for
        // bursts queued behind a full track. Stalls must not fall back: it is still draining.
        if (handledEndOfStream) drainPending(stallIsFatal = false)
        if (!isIecActive) return super.isEnded()
        return handledEndOfStream && !hasPendingData()
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
        val bytes = iecBufferSizeBytes
        if (bytes <= 0 || track.frameSizeBytes <= 0) return C.TIME_UNSET
        return bytes.toLong() / track.frameSizeBytes * C.MICROS_PER_SECOND / track.sampleRate
    }

    private fun refreshPassthroughLive() {
        val format = configuredFormat
        val hbrWaitingOnIec = format != null &&
            isHbrPassthrough(format) &&
            hbrIecEnabled &&
            !isIecActive &&
            !iecFailedThisSession &&
            !trackFactory.iec61937Ready()
        val live = isIecActive || (
            mode == Mode.FORWARD &&
                format != null &&
                LiveDirectAudioPlayback.isDirectPassthroughFormat(format) &&
                !hbrWaitingOnIec
        )
        LiveDirectAudioPlayback.setPassthroughLive(live)
    }

    private fun attachReadyListener() {
        trackFactory.setReadyListener { deliverIecReady() }
    }

    private fun deliverIecReadyIfProbeAlreadySucceeded() {
        if (hbrIecEnabled && trackFactory.iec61937Ready()) deliverIecReady()
    }

    private fun deliverIecReady() {
        if (!iecReadyDelivered.compareAndSet(false, true)) return
        onIecBecameReady?.invoke()
    }

    private fun iecAvailable(format: Format): Boolean {
        if (!hbrIecEnabled) return false
        // The MAT min-buffer check only vouches for TrueHD. DTS-HD and DTS:X ride IEC bursts,
        // which the background probe has to prove first; before that the wrapped sink answers.
        return if (isTrueHd(format)) {
            val channels = hbrIecChannelCount(format)
            val preferred = iecSampleRateFor(format)
            if (isFortyFourOneTrueHd(format)) {
                trackFactory.canOpen(preferred, channels)
            } else {
                trackFactory.canOpen(preferred, channels) ||
                    (preferred != IEC_SAMPLE_RATE && trackFactory.canOpen(IEC_SAMPLE_RATE, channels))
            }
        } else {
            trackFactory.iec61937Ready()
        }
    }

    private fun openIec(format: Format): Boolean {
        val preferredRate = iecSampleRateFor(format)
        if (openIecAt(format, preferredRate)) return true
        if (isFortyFourOneTrueHd(format)) return false
        return preferredRate != IEC_SAMPLE_RATE && openIecAt(format, IEC_SAMPLE_RATE)
    }

    private fun isFortyFourOneTrueHd(format: Format): Boolean {
        return isTrueHd(format) && format.sampleRate == 44_100
    }

    private fun iecSampleRateFor(format: Format): Int {
        if (format.sampleRate != 44_100) return IEC_SAMPLE_RATE
        // DOLBY_MAT create is cheap, so 44.1 TrueHD can ask for 176.4 without the
        // IEC61937 probe. DTS still needs that probe: IEC61937.Builder can block.
        return if (isTrueHd(format) || trackFactory.iec61937ReadyAt(IEC_SAMPLE_RATE_44K1)) {
            IEC_SAMPLE_RATE_44K1
        } else {
            IEC_SAMPLE_RATE
        }
    }

    private fun clockSampleRateFor(iecRate: Int): Int =
        if (iecRate == IEC_SAMPLE_RATE_44K1) 44_100 else 48_000

    private fun openIecAt(format: Format, sampleRate: Int): Boolean {
        val channelCount = hbrIecChannelCount(format)
        val frameBytes = if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE
        } else {
            Iec61937Packer.dtsHdIecPeriod(channelCount, 512) shl 2
        }
        val bufferBytes = frameBytes * if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) 2 else 4
        val targetBufferBytes = IEC_BUFFER_TARGET_MS * sampleRate / 1000 * channelCount * 2
        val requestedBytes = maxOf(bufferBytes, targetBufferBytes)
        val track = trackFactory.openHbr(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bufferSizeBytes = requestedBytes,
            sessionId = audioSessionId,
            trueHd = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        ) ?: return false
        track.setVolume(volume)
        iecTrack = track
        iecBufferSizeBytes = track.bufferSizeBytes
        iecOutputSampleRate = sampleRate
        return true
    }

    private fun hbrIecChannelCount(format: Format): Int {
        if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) return 8
        val count = format.channelCount
        return if (count > 0) Iec61937Packer.dtsHdChannelMask(count) else 8
    }

    private fun handleTrueHd(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        val carried = leftover.size
        if (carried == 0 && buffer.hasArray()) {
            val array = buffer.array()
            val start = buffer.arrayOffset() + buffer.position()
            consumeTrueHd(array, start, start + buffer.remaining(), 0, presentationTimeUs)
        } else {
            val end = appendTrueHd(leftover, buffer)
            consumeTrueHd(trueHdScratch, 0, end, carried, presentationTimeUs)
        }
        buffer.position(buffer.limit())
        return true
    }

    private fun consumeTrueHd(
        data: ByteArray,
        start: Int,
        end: Int,
        carried: Int,
        presentationTimeUs: Long
    ) {
        var offset = start
        var discardedInBuffer = 0
        while (offset + 10 <= end) {
            val auSize = TrueHdMatPacker.trueHdAccessUnitSize(data, offset)
            if (auSize < 10) {
                // Not an access unit. The extractor hands over access-unit-aligned samples, so
                // drop the remainder and resync on the next sample rather than carrying the bad
                // head forward under every later buffer (a frozen clock with no error).
                onDiagnosticEvent?.invoke(
                    "iec_truehd_resync auSize=$auSize dropped=${end - offset}"
                )
                offset = end
                break
            }
            if (offset + auSize > end) break
            val auStart = offset - start
            val wasSynced = matPacker.isSynced
            val frameReady = matPacker.packAccessUnit(data, offset, auSize)
            offset += auSize
            if (startPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
                if (matPacker.isSynced) {
                    anchorTrueHd(presentationTimeUs, auStart >= carried, discardedInBuffer)
                } else if (!wasSynced) {
                    discardedAuSinceReset++
                    if (auStart >= carried) discardedInBuffer++
                }
            }
            if (frameReady) {
                while (matPacker.hasFrame()) {
                    val mat = matPacker.pollFrame()!!
                    pendingFrames.add(
                        if (iecTrack?.payload == HbrPayload.MAT) mat
                        else Iec61937Packer.packTrueHdInPlace(mat)
                    )
                    noteFrameQueued()
                }
            }
        }
        val tail = end - offset
        leftover = if (tail <= 0) {
            ByteArray(0)
        } else {
            data.copyOfRange(offset, end)
        }
    }

    private fun appendTrueHd(prefix: ByteArray, buffer: ByteBuffer): Int {
        val need = prefix.size + buffer.remaining()
        if (trueHdScratch.size < need) trueHdScratch = ByteArray(need)
        System.arraycopy(prefix, 0, trueHdScratch, 0, prefix.size)
        val pos = buffer.position()
        buffer.get(trueHdScratch, prefix.size, buffer.remaining())
        buffer.position(pos)
        return need
    }

    // The buffer's PTS is that of its first access unit; the packer accepted the unit at index
    // [discardedInBuffer] (every earlier unit in this buffer was discarded), so the clock starts
    // at PTS + index * unit duration. A unit that began in the carried leftover belongs to the
    // previous buffer's time; anchoring on this buffer's PTS is then the closest available.
    private fun anchorTrueHd(bufferPtsUs: Long, startedInBuffer: Boolean, discardedInBuffer: Int) {
        val offsetUs = if (startedInBuffer) {
            discardedInBuffer * 40L * C.MICROS_PER_SECOND / matPacker.baseSampleRate()
        } else {
            0L
        }
        startPtsUs = bufferPtsUs + offsetUs
        val deltaUs = if (firstBufferPtsUs != C.TIME_UNSET) startPtsUs - firstBufferPtsUs else 0L
        val line = "iec_anchor mode=TRUEHD bufferPts=$bufferPtsUs discardedAu=$discardedAuSinceReset " +
            "inBuffer=$discardedInBuffer anchorPts=$startPtsUs deltaUs=$deltaUs " +
            "leftoverFallback=${!startedInBuffer}"
        onDiagnosticEvent?.invoke(line)
        android.util.Log.i("IecPassthrough", line)
    }

    private fun handleDtsHd(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        // DtsUtil's byte[] overload reads indices 0 and 4..7 only, so it gets just that head
        // rather than a copy of the whole access unit. A unit shorter than eight bytes gives a
        // head exactly as short, which keeps the original out-of-bounds-to-512 behaviour.
        val position = buffer.position()
        val head = ByteArray(buffer.remaining().coerceAtMost(8))
        buffer.get(head)
        buffer.position(position)
        val sampleCount = resolveDtsSampleCount(head, buffer, presentationTimeUs)
        val period = Iec61937Packer.dtsHdIecPeriod(dtsChannelCount, sampleCount)
        val burst = acquireDtsBurst(period shl 2)
        Iec61937Packer.packDtsHdInto(buffer, period, burst)
        pendingFrames.add(burst)
        noteFrameQueued()
        return true
    }

    // Core DTS-HD sizes from the ETSI TS 102 114 header. DTS:X / DTS-UHD have no
    // core; prefer parseDtsUhdHeader when the FTOC CRC is valid, and keep that
    // duration across non-sync / CRC failures. Otherwise the PTS delta snapped
    // to the {384, 480, 512} x 1..8 grid.
    private fun resolveDtsSampleCount(head: ByteArray, buffer: ByteBuffer, ptsUs: Long): Int {
        if (hasDtsCoreSyncWord(head)) {
            val parsed = try {
                DtsUtil.parseDtsAudioSampleCount(head)
            } catch (_: Exception) {
                0
            }
            if (parsed > 0) {
                dtsDuration.observeKnownCount(parsed)
                return parsed
            }
        }
        val fromUhd = sampleCountFromUhdHeader(head, buffer)
        if (fromUhd > 0) {
            dtsDuration.notePts(ptsUs)
            return fromUhd
        }
        return dtsDuration.resolveFromPts(ptsUs)
    }

    private fun sampleCountFromUhdHeader(head: ByteArray, buffer: ByteBuffer): Int {
        if (head.size < 4) return 0
        val word = (head[0].toInt() and 0xFF shl 24) or
            (head[1].toInt() and 0xFF shl 16) or
            (head[2].toInt() and 0xFF shl 8) or
            (head[3].toInt() and 0xFF)
        val frameType = DtsUtil.getFrameType(word)
        if (frameType != DtsUtil.FRAME_TYPE_UHD_SYNC &&
            frameType != DtsUtil.FRAME_TYPE_UHD_NON_SYNC
        ) {
            return 0
        }
        val position = buffer.position()
        val accessUnit = ByteArray(buffer.remaining())
        buffer.get(accessUnit)
        buffer.position(position)
        try {
            val header = DtsUtil.parseDtsUhdHeader(accessUnit, uhdAudioChunkId)
            dtsDuration.rememberUhdDurationUs(header.frameDurationUs)
        } catch (_: Exception) {
            // Non-sync and CRC failures still carry the last sync duration.
        }
        val cached = dtsDuration.sampleCountFromUhdCache()
        if (cached > 0) dtsDuration.observeKnownCount(cached)
        return cached
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

    private fun drainPending(stallIsFatal: Boolean = true): Boolean {
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
                    if (playing) {
                        totalWriteStalls++
                        val now = nanoTime()
                        if (writeStallStartedNanos == WRITE_STALL_NOT_STARTED) {
                            writeStallStartedNanos = now
                        }
                        val stalledFor = now - writeStallStartedNanos
                        if (stallIsFatal && stalledFor >= WRITE_STALL_BUDGET_NANOS) {
                            return fallbackToWrappedSink("write_stall_ms=${stalledFor / 1_000_000L}")
                        }
                    }
                    return false
                }
                writeStallStartedNanos = WRITE_STALL_NOT_STARTED
                pendingOffset += written
                writtenBytes += written
            }
            recycleFrame(pendingFrames.removeFirst())
            pendingOffset = 0
            noteFrameWritten()
        }
        return true
    }

    private fun fallbackToWrappedSink(reason: String): Boolean {
        val replays = ArrayList(uncommitted)
        val format = configuredFormat
        val endOfStreamRequested = handledEndOfStream
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
                refreshPassthroughLive()
                throw AudioSink.WriteException(WRITE_ERROR_FALLBACK_REFUSED, format, true)
                    .apply { initCause(e) }
            }
            if (playing) super.play()
            var handedOff = true
            for (replay in replays) {
                val bytes = ByteBuffer.wrap(replay.bytes)
                if (!super.handleBuffer(bytes, replay.ptsUs, replay.count)) {
                    handedOff = false
                    break
                }
            }
            if (endOfStreamRequested) super.playToEndOfStream()
            refreshPassthroughLive()
            return handedOff
        }
        refreshPassthroughLive()
        return true
    }

    private fun rememberSource(buffer: ByteBuffer, ptsUs: Long, count: Int) {
        val size = buffer.remaining()
        if (size <= 0) return
        val bytes = ByteArray(size)
        val view = buffer.duplicate()
        view.get(bytes)
        uncommitted.add(UncommittedAu(bytes, ptsUs, count))
        sourcesSinceFrame++
    }

    private fun noteFrameQueued() {
        sourcesOnFrame.add(sourcesSinceFrame)
        sourcesSinceFrame = 0
    }

    private fun noteFrameWritten() {
        if (sourcesOnFrame.isEmpty()) return
        var drop = sourcesOnFrame.removeFirst()
        while (drop > 0 && uncommitted.isNotEmpty()) {
            uncommitted.removeFirst()
            drop--
        }
    }

    private fun clearUncommitted() {
        uncommitted.clear()
        sourcesOnFrame.clear()
        sourcesSinceFrame = 0
    }

    private fun resetIecState(keepTrack: Boolean) {
        clearUncommitted()
        matPacker.reset()
        while (pendingFrames.isNotEmpty()) recycleFrame(pendingFrames.removeFirst())
        pendingOffset = 0
        leftover = ByteArray(0)
        startPtsUs = C.TIME_UNSET
        firstBufferPtsUs = C.TIME_UNSET
        discardedAuSinceReset = 0
        if (keepTrack) dtsDuration.clearPts() else dtsDuration.reset()
        if (!keepTrack) {
            uhdAudioChunkId.set(0)
        }
        writtenBytes = 0L
        headAnchorFrames = 0L
        handledEndOfStream = false
        writeStallStartedNanos = WRITE_STALL_NOT_STARTED
        if (!keepTrack) {
            iecTrack?.release()
            iecTrack = null
            iecBufferSizeBytes = 0
            iecOutputSampleRate = IEC_SAMPLE_RATE
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

    private class UncommittedAu(val bytes: ByteArray, val ptsUs: Long, val count: Int)

    private enum class Mode { FORWARD, TRUEHD, DTS_HD }

    companion object {
        const val IEC_SAMPLE_RATE = 192_000
        const val IEC_SAMPLE_RATE_44K1 = 176_400
        // Target buffer for the app-packed IEC61937 track. The track is written from the
        // playback thread; a garbage-collection or scheduler stall on that thread that
        // outlasts the track's buffer starves the HAL and drops audio. Observed feeder
        // stalls reach several hundred milliseconds, while the default request (~40 ms) is
        // only floored/rounded up to ~60-85 ms of real headroom by the HALs measured. Ask
        // for enough to ride out the worst observed stall. createTrack falls back to the
        // HAL minimum if a device rejects the larger allocation, so this never reduces the
        // buffer or fails an open the default would have made.
        internal const val IEC_BUFFER_TARGET_MS = 1_000
        internal const val WRITE_STALL_BUDGET_NANOS = 500_000_000L
        private const val WRITE_STALL_NOT_STARTED = Long.MIN_VALUE
        internal const val WRITE_ERROR_FALLBACK_REFUSED = -1_000
        private const val HEALTH_INTERVAL_NANOS = 5_000_000_000L
        private const val FRAME_POOL_LIMIT = 8

        fun isTrueHd(format: Format): Boolean {
            return format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        }

        fun isHbrPassthrough(format: Format): Boolean {
            return PassthroughWaterLevelPacer.isHbrMime(format.sampleMimeType)
        }

        // ETSI TS 102 114 sync words: 16-bit and 14-bit, big and little endian.
        private fun hasDtsCoreSyncWord(head: ByteArray): Boolean {
            if (head.size < 4) return false
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            val b2 = head[2].toInt() and 0xFF
            val b3 = head[3].toInt() and 0xFF
            return (b0 == 0x7F && b1 == 0xFE && b2 == 0x80 && b3 == 0x01) ||
                (b0 == 0xFE && b1 == 0x7F && b2 == 0x01 && b3 == 0x80) ||
                (b0 == 0x1F && b1 == 0xFF && b2 == 0xE8 && b3 == 0x00) ||
                (b0 == 0xFF && b1 == 0x1F && b2 == 0x00 && b3 == 0xE8)
        }

    }
}

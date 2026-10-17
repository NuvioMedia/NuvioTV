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

internal class IecPassthroughAudioSink(
    sink: AudioSink,
    private val trackFactory: IecAudioTrackFactory = PlatformIecAudioTrackFactory(),
    private val hbrIecEnabled: Boolean = true,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onDiagnosticEvent: ((String) -> Unit)? = null,
    private val keepAliveEnabled: Boolean = true,
    private val keepAliveThreadEnabled: Boolean = true,
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
    private val dtsBurstPool = ArrayDeque<ByteArray>()
    private var pendingOffset: Int = 0
    private var leftover: ByteArray = ByteArray(0)
    private var trueHdScratch: ByteArray = ByteArray(0)
    private var startPtsUs: Long = C.TIME_UNSET
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

    // TrueHD keep-alive: the track is never paused or flushed once it plays. Content is held back
    // while paused, and padding-only MAT frames keep the HDMI bitstream going, so the receiver and
    // the TV never lose the format. The ledger keeps those frames out of the media clock.
    private val writeLock = Any()
    private val fillerLedger = IecKeepAliveLedger()
    private var fillerFrame: ByteArray = ByteArray(0)
    private var fillerOffset: Int = 0
    private var fillerFramesWritten: Long = 0L
    private var realEndBytes: Long = 0L
    @Volatile
    private var trackStarted: Boolean = false
    private var stallHeadFrames: Long = -1L
    private var heldSinceNanos: Long = NOT_HELD
    private var heldTooLong: Boolean = false
    @Volatile
    private var keepAliveThread: Thread? = null
    private var lastHealthNanos: Long = 0L
    private var lastHealthUnderruns: Int = -1
    private var tunnelingRequested: Boolean = false
    private val iecReadyDelivered = AtomicBoolean(false)

    @Volatile
    private var hbrAnsweredBeforeProbe: Boolean = false
    private val timestampClock = IecAudioTimestampClock(nanoTime)

    init {
        attachReadyListener()
        if (hbrIecEnabled) trackFactory.startProbe()
    }

    val isIecActive: Boolean
        get() = mode != Mode.FORWARD && iecTrack != null

    private val writtenFrames: Long
        get() = iecTrack?.let { writtenBytes / it.frameSizeBytes } ?: 0L

    private val realEndFrames: Long
        get() = iecTrack?.let { realEndBytes / it.frameSizeBytes } ?: 0L

    /** The track plays on through pauses and seeks; see [keepAliveTick]. */
    val isKeepAliveActive: Boolean
        get() = keepAliveEnabled && mode == Mode.TRUEHD && iecTrack != null && trackStarted

    fun claimsHbr(format: Format): Boolean {
        return hbrIecEnabled && !iecFailedThisSession && isHbrPassthrough(format) && iecAvailable(format)
    }

    override fun getFormatSupport(format: Format): Int {
        if (hbrIecRoute(format)) return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (hbrIecRoute(format)) return true
        return super.supportsFormat(format)
    }

    private fun hbrIecRoute(format: Format): Boolean {
        if (iecFailedThisSession || !isHbrPassthrough(format)) return false
        if (iecAvailable(format)) return true
        if (hbrIecEnabled && !trackFactory.iec61937Ready()) hbrAnsweredBeforeProbe = true
        return false
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        attachReadyListener()
        deliverIecReadyIfProbeAlreadySucceeded()
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels
        releaseIec()
        LiveDirectAudioPlayback.setPassthroughLive(false)
        if (hbrIecEnabled) trackFactory.startProbe()
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
        val head: Long
        val written: Long
        val sample: IecAudioTimestampSample?
        synchronized(writeLock) {
            val realEnd = realEndFrames
            val played = minOf(track.playbackHeadFrames(), realEnd)
            head = fillerLedger.contentFrames(played) - headAnchorFrames
            written = (fillerLedger.contentFrames(realEnd) - headAnchorFrames).coerceAtLeast(0L)
            sample = track.timestamp()?.let { stamp ->
                val adjusted =
                    fillerLedger.contentFrames(minOf(stamp.framePosition, realEnd)) - headAnchorFrames
                if (adjusted < 0L) null else stamp.copy(framePosition = adjusted)
            }
        }
        return timestampClock.positionUs(
            sampleRate = track.sampleRate,
            headFrames = head.coerceAtLeast(0L),
            writtenFrames = written,
            startPtsUs = startPtsUs,
            latencyUs = track.outputLatencyUs(),
            playHeld = track.isPlayHeld(),
            timestamp = sample,
            playing = playing
        )
    }

    override fun play() {
        playing = true
        if (isIecActive) {
            val track = iecTrack ?: return
            synchronized(writeLock) {
                heldSinceNanos = NOT_HELD
                heldTooLong = false
                startTrack(track)
            }
            if (isKeepAliveActive) {
                try {
                    drainPending()
                } catch (_: AudioSink.WriteException) {
                } catch (_: AudioSink.InitializationException) {
                }
            }
        } else {
            super.play()
        }
    }

    override fun pause() {
        playing = false
        writeStallStartedNanos = WRITE_STALL_NOT_STARTED
        timestampClock.reset()
        if (isIecActive) {
            if (isKeepAliveActive) {
                synchronized(writeLock) {
                    finishPartialFrame()
                    heldSinceNanos = nanoTime()
                }
                onDiagnosticEvent?.invoke("iec_keepalive_hold reason=pause ${keepAliveState()}")
            } else {
                iecTrack?.pause()
            }
        } else {
            super.pause()
        }
    }

    override fun flush() {
        if (isIecActive) {
            if (isKeepAliveActive) {
                synchronized(writeLock) {
                    finishPartialFrame()
                    resetIecState(keepTrack = true, keepCounters = true)
                    // Content written before the seek still plays out; the new timeline starts after it.
                    headAnchorFrames = fillerLedger.contentFrames(realEndFrames)
                    if (!playing) heldSinceNanos = nanoTime()
                }
                onDiagnosticEvent?.invoke("iec_keepalive_hold reason=flush ${keepAliveState()}")
            } else {
                resetIecState(keepTrack = true)
                iecTrack?.flush()
            }
        } else {
            super.flush()
        }
    }

    override fun handleDiscontinuity() {
        if (isIecActive) {
            synchronized(writeLock) {
                val played = minOf(iecTrack?.playbackHeadFrames() ?: 0L, realEndFrames)
                headAnchorFrames = fillerLedger.contentFrames(played)
            }
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
            timestampClock.reset()
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
        leftover = ByteArray(0)
        drainPending()
        if (isIecActive) {
            handledEndOfStream = true
        } else {
            super.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        if (!isIecActive) return super.isEnded()
        if (handledEndOfStream) drainPending(stallIsFatal = false)
        if (!isIecActive) return super.isEnded()
        return handledEndOfStream && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        if (!isIecActive) return super.hasPendingData()
        val track = iecTrack ?: return false
        return pendingFrames.isNotEmpty() || leftover.isNotEmpty() ||
            synchronized(writeLock) { realEndFrames > track.playbackHeadFrames() }
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
        if (!hbrAnsweredBeforeProbe) return
        if (!iecReadyDelivered.compareAndSet(false, true)) return
        onIecBecameReady?.invoke()
    }

    private fun iecAvailable(format: Format): Boolean {
        if (!hbrIecEnabled) return false
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
        }
        val cached = dtsDuration.sampleCountFromUhdCache()
        if (cached > 0) dtsDuration.observeKnownCount(cached)
        return cached
    }

    private fun acquireDtsBurst(size: Int): ByteArray {
        val pooled = dtsBurstPool.poll()
        return if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    private fun recycleFrame(frame: ByteArray) {
        if (mode == Mode.TRUEHD) {
            matPacker.recycleFrame(frame)
        } else if (dtsBurstPool.size < FRAME_POOL_LIMIT) {
            dtsBurstPool.add(frame)
        }
    }

    private fun drainPending(stallIsFatal: Boolean = true): Boolean {
        val track = iecTrack ?: return true
        if (playing) synchronized(writeLock) { startTrack(track) }
        val keepAlive = isKeepAliveActive
        // Paused: content waits here while the filler keeps the output alive, otherwise it would play.
        if (keepAlive && !playing) return false
        while (pendingFrames.isNotEmpty()) {
            val frame = pendingFrames.first()
            while (pendingOffset < frame.size) {
                var capHeld = false
                val written = synchronized(writeLock) {
                    if (keepAlive) {
                        if (!writeFiller(track)) return@synchronized 0
                        val queued = writtenFrames - track.playbackHeadFrames()
                        if (queued >= keepAliveCapFrames(track)) {
                            capHeld = true
                            return@synchronized 0
                        }
                    }
                    val count = track.write(frame, pendingOffset, frame.size - pendingOffset)
                    if (count > 0) {
                        pendingOffset += count
                        writtenBytes += count
                        if (pendingOffset == frame.size) realEndBytes = writtenBytes
                    }
                    count
                }
                if (written < 0) {
                    return fallbackToWrappedSink("write_error code=$written")
                }
                if (written == 0) {
                    if (playing) {
                        if (!capHeld) totalWriteStalls++
                        val now = nanoTime()
                        val headNow = track.playbackHeadFrames()
                        if (writeStallStartedNanos == WRITE_STALL_NOT_STARTED ||
                            (keepAlive && headNow != stallHeadFrames)
                        ) {
                            writeStallStartedNanos = now
                            stallHeadFrames = headNow
                        }
                        val stalledFor = now - writeStallStartedNanos
                        if (stallIsFatal && stalledFor >= WRITE_STALL_BUDGET_NANOS) {
                            return fallbackToWrappedSink("write_stall_ms=${stalledFor / 1_000_000L}")
                        }
                    }
                    return false
                }
                writeStallStartedNanos = WRITE_STALL_NOT_STARTED
            }
            recycleFrame(pendingFrames.removeFirst())
            pendingOffset = 0
            noteFrameWritten()
        }
        return true
    }

    private fun startTrack(track: IecAudioTrack) {
        track.play()
        if (trackStarted || track.isPlayHeld()) return
        trackStarted = true
        if (!keepAliveEnabled || mode != Mode.TRUEHD) return
        val frame = TrueHdMatPacker.paddingOnlyFrame()
        fillerFrame = if (track.payload == HbrPayload.MAT) frame else Iec61937Packer.packTrueHdInPlace(frame)
        fillerOffset = 0
        onDiagnosticEvent?.invoke("iec_keepalive_armed payload=${track.payload} capMs=$KEEP_ALIVE_CAP_MS")
        if (keepAliveThreadEnabled) startKeepAliveThread()
    }

    private fun startKeepAliveThread() {
        if (keepAliveThread != null) return
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(KEEP_ALIVE_TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                try {
                    keepAliveTick()
                } catch (_: Exception) {
                }
            }
        }, "iec-keepalive")
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY + 2
        keepAliveThread = thread
        thread.start()
    }

    private fun stopKeepAliveThread() {
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }

    /**
     * Tops the track up with a padding-only MAT frame whenever less than [KEEP_ALIVE_LOW_WATER_MS]
     * of audio is queued: during a pause or a seek, and when the playback thread is late. Runs on
     * the keep-alive thread, or from a test.
     */
    internal fun keepAliveTick() {
        val track = iecTrack ?: return
        synchronized(writeLock) {
            if (!isKeepAliveActive || heldTooLong) return
            if (heldSinceNanos != NOT_HELD && nanoTime() - heldSinceNanos >= KEEP_ALIVE_MAX_HOLD_NANOS) {
                // A very long pause: let the output rest as it did before. Resuming re-announces once.
                heldTooLong = true
                track.pause()
                onDiagnosticEvent?.invoke("iec_keepalive_released reason=held_too_long ${keepAliveState()}")
                return
            }
            // A content frame half written by the playback thread must not be split by filler.
            if (pendingOffset > 0) return
            if (fillerOffset == 0) {
                val queued = writtenFrames - track.playbackHeadFrames()
                if (queued >= keepAliveLowWaterFrames(track)) return
            }
            writeFiller(track, whole = true)
        }
    }

    /** Completes a half-written content frame so that filler, or the next seek's content, starts on a burst boundary. */
    private fun finishPartialFrame() {
        val track = iecTrack ?: return
        if (pendingOffset == 0 || pendingFrames.isEmpty()) return
        val frame = pendingFrames.first()
        while (pendingOffset < frame.size) {
            val count = track.write(frame, pendingOffset, frame.size - pendingOffset)
            if (count <= 0) return
            pendingOffset += count
            writtenBytes += count
        }
        realEndBytes = writtenBytes
        recycleFrame(pendingFrames.removeFirst())
        pendingOffset = 0
        noteFrameWritten()
    }

    /** Writes the rest of a started filler frame, or a whole one. False while a frame is still partly written. */
    private fun writeFiller(track: IecAudioTrack, whole: Boolean = false): Boolean {
        if (fillerFrame.isEmpty()) return true
        if (fillerOffset == 0 && !whole) return true
        while (fillerOffset < fillerFrame.size) {
            val count = track.write(fillerFrame, fillerOffset, fillerFrame.size - fillerOffset)
            if (count <= 0) return false
            val before = writtenFrames
            fillerOffset += count
            writtenBytes += count
            fillerLedger.add(before, writtenFrames)
        }
        fillerOffset = 0
        fillerFramesWritten++
        return true
    }

    private fun keepAliveState(): String {
        val track = iecTrack ?: return "track=none"
        val queuedMs = (writtenFrames - track.playbackHeadFrames()) * 1000L / track.sampleRate
        return "queuedMs=$queuedMs filler=$fillerFramesWritten"
    }

    private fun keepAliveCapFrames(track: IecAudioTrack): Long =
        KEEP_ALIVE_CAP_MS * track.sampleRate / 1000L

    private fun keepAliveLowWaterFrames(track: IecAudioTrack): Long =
        KEEP_ALIVE_LOW_WATER_MS * track.sampleRate / 1000L

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

    private fun resetIecState(keepTrack: Boolean, keepCounters: Boolean = false) {
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
        if (!keepCounters) {
            synchronized(writeLock) {
                writtenBytes = 0L
                realEndBytes = 0L
                headAnchorFrames = 0L
                fillerLedger.clear()
                fillerOffset = 0
            }
        }
        handledEndOfStream = false
        writeStallStartedNanos = WRITE_STALL_NOT_STARTED
        timestampClock.reset()
        if (!keepTrack) {
            stopKeepAliveThread()
            synchronized(writeLock) {
                iecTrack?.release()
                iecTrack = null
                trackStarted = false
                fillerFrame = ByteArray(0)
                fillerFramesWritten = 0L
                heldSinceNanos = NOT_HELD
                heldTooLong = false
            }
            iecBufferSizeBytes = 0
            iecOutputSampleRate = IEC_SAMPLE_RATE
            totalWriteStalls = 0L
            lastHealthNanos = 0L
            lastHealthUnderruns = -1
        }
    }

    private fun maybeReportHealth() {
        val track = iecTrack ?: return
        val underruns = track.underrunCount()
        val now = System.nanoTime()
        if (underruns == lastHealthUnderruns && now - lastHealthNanos < HEALTH_INTERVAL_NANOS) return
        lastHealthNanos = now
        lastHealthUnderruns = underruns
        val line = "iec_health mode=$mode payload=${track.payload} underruns=$underruns " +
            "head=${track.playbackHeadFrames()} written=$writtenFrames " +
            "pending=${pendingFrames.size} stalls=$totalWriteStalls playing=$playing " +
            "keepAlive=$isKeepAliveActive ${keepAliveState()}"
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
        internal const val IEC_BUFFER_TARGET_MS = 1_000
        internal const val WRITE_STALL_BUDGET_NANOS = 500_000_000L
        private const val WRITE_STALL_NOT_STARTED = Long.MIN_VALUE
        internal const val WRITE_ERROR_FALLBACK_REFUSED = -1_000
        private const val HEALTH_INTERVAL_NANOS = 5_000_000_000L
        private const val FRAME_POOL_LIMIT = 8
        private const val NOT_HELD = Long.MIN_VALUE
        // Content queued in the track while playing; what is left of it after a seek plays out first.
        internal const val KEEP_ALIVE_CAP_MS = 240L
        // Below this the keep-alive adds a padding-only MAT frame (20 ms) so the output never runs dry.
        internal const val KEEP_ALIVE_LOW_WATER_MS = 80L
        internal const val KEEP_ALIVE_TICK_MS = 10L
        internal const val KEEP_ALIVE_MAX_HOLD_NANOS = 30L * 60L * 1_000_000_000L

        fun isTrueHd(format: Format): Boolean {
            return format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        }

        fun isHbrPassthrough(format: Format): Boolean {
            return PassthroughWaterLevelPacer.isHbrMime(format.sampleMimeType)
        }

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

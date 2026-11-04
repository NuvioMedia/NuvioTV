package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.ui.screens.player.RewrappableExtractor
import java.io.EOFException
import java.io.IOException

/**
 * Wraps an [Extractor] so its video track feeds a [DolbyVisionP5Session].
 *
 * Profile 5 is recognised from the track's codecs or, failing that, from the RPU of its first
 * sample (a Matroska file whose colour metadata claims SDR loses its Dolby Vision config, so its
 * track reads as plain HEVC). With an active session the format of such a track is held back
 * until that first sample is read, so the decoder starts with the right one and the player never
 * has to restart. A Profile 5 track then plays as HDR10 through the [DolbyVisionP5SurfaceRenderer]:
 *  - the format becomes plain HEVC Main10, so a regular HEVC decoder decodes the base layer;
 *  - its colour info becomes BT.2020 / PQ / full range (Profile 5 is full range), so the decoder
 *    is set up for 10-bit HDR output;
 *  - each sample's RPU NAL is parsed and its mapping recorded at the sample's time.
 * Sample bytes are passed on unchanged: HEVC decoders ignore the RPU NAL type.
 *
 * Wrap it directly around the extractor that writes the samples, so it sees each RPU before any
 * other wrapper can strip it. It is a [RewrappableExtractor], so it stays in place when libass
 * swaps in its own Matroska extractor.
 */
@UnstableApi
internal class DolbyVisionP5Extractor(
    private val delegate: Extractor,
    private val session: DolbyVisionP5Session,
) : RewrappableExtractor {

    override val wrappedExtractor: Extractor get() = delegate

    override fun rewrap(inner: Extractor): Extractor = DolbyVisionP5Extractor(inner, session)

    private var output: P5ExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val wrapped = P5ExtractorOutput(output, session)
        this.output = wrapped
        delegate.init(wrapped)
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        // A half-read sample is dropped by the seek: so are its buffered bytes.
        output?.onSeek()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@UnstableApi
private class P5ExtractorOutput(
    private val delegate: ExtractorOutput,
    private val session: DolbyVisionP5Session,
) : ExtractorOutput {

    private val videoTracks = ArrayList<P5TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO) return track
        return P5TrackOutput(track, session).also { videoTracks += it }
    }

    fun onSeek() = videoTracks.forEach(P5TrackOutput::onSeek)

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class P5TrackOutput(
    private val delegate: TrackOutput,
    private val session: DolbyVisionP5Session,
) : TrackOutput {

    private enum class Mode {
        /** Not Profile 5 (or settled): everything passes straight through. */
        PASS,
        /** Session can't map: only look for Profile 5 in the first samples, for the player's note. */
        PROBE,
        /** Profile 5 being mapped: the format went out as HDR10, every sample's RPU is recorded. */
        MAP,
        /**
         * HEVC whose container didn't say Profile 5: the format and bytes are held until the first
         * sample's RPU shows whether it is, so the decoder gets the right format from the start.
         */
        DEFER,
    }

    private var mode = Mode.PASS
    private var heldFormat: Format? = null
    private var probeSamplesLeft = 0
    // The main sample bytes since the last committed sample (passed on already, except in DEFER).
    private var buffer = ByteArray(0)
    private var bufferLength = 0
    private val forward = ParsableByteArray()

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    fun onSeek() {
        bufferLength = 0
    }

    override fun format(format: Format) {
        val hevc = isHevcOrDolbyVision(format.sampleMimeType)
        val signalled = hevc && (isProfile5(format.codecs) || session.containerSignalledProfile5)
        if (signalled) session.onProfile5Track()
        if (mode == Mode.DEFER) {
            heldFormat = format
            return
        }
        mode = when {
            !hevc -> Mode.PASS
            session.active && signalled -> Mode.MAP
            session.active -> Mode.DEFER
            !session.detected -> Mode.PROBE
            else -> Mode.PASS
        }
        probeSamplesLeft = PROBE_SAMPLES
        when (mode) {
            Mode.MAP -> delegate.format(toHdr10(format))
            Mode.DEFER -> heldFormat = format
            else -> delegate.format(format)
        }
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (!buffersPart(sampleDataPart)) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensureCapacity(bufferLength + length)
        val read = input.read(buffer, bufferLength, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        appended(read)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (length <= 0 || !buffersPart(sampleDataPart)) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensureCapacity(bufferLength + length)
        data.readBytes(buffer, bufferLength, length)
        appended(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (mode == Mode.PASS) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        // [offset] bytes at the end already belong to the next sample.
        val carry = offset.coerceIn(0, bufferLength)
        val sampleEnd = bufferLength - carry
        var result = DolbyVisionP5Native.NOT_PARSED
        if (cryptoData == null) {
            session.onSampleScanned()
            val rpu = DolbyVisionP5Nal.findRpu(buffer, sampleEnd)
            if (rpu != DolbyVisionP5Nal.NONE) {
                result = DolbyVisionP5Nal.ingest(buffer, rpu, DolbyVisionP5Native::ingestRpu)
                session.onRpuResult(result)
            }
        }
        if (mode == Mode.DEFER) release(profile5 = result > 0L)
        // A sample without a usable RPU keeps the mapping before it.
        if (mode == Mode.MAP && result > 0L) session.onSampleMapping(timeUs, result)
        if (mode == Mode.PROBE && (session.detected || --probeSamplesLeft <= 0)) mode = Mode.PASS
        if (mode == Mode.PASS) {
            bufferLength = 0
        } else {
            if (carry > 0) System.arraycopy(buffer, sampleEnd, buffer, 0, carry)
            bufferLength = carry
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    /** Whether this part of the sample goes through [buffer]; ends a deferral it can't hold. */
    private fun buffersPart(sampleDataPart: Int): Boolean {
        if (mode == Mode.PASS) return false
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) return true
        // Encryption or supplemental data must keep its place before the main data.
        if (mode == Mode.DEFER) release(profile5 = false)
        return false
    }

    private fun appended(length: Int) {
        if (mode == Mode.DEFER) {
            bufferLength += length
            if (bufferLength > MAX_DEFERRED_BYTES) release(profile5 = false)
        } else {
            forwardBuffered(bufferLength, length)
            bufferLength += length
        }
    }

    /** Ends a deferral: sends the held format (as HDR10 for Profile 5) and the held bytes. */
    private fun release(profile5: Boolean) {
        val format = heldFormat ?: return
        heldFormat = null
        if (profile5) session.onProfile5Track()
        delegate.format(if (profile5) toHdr10(format) else format)
        if (bufferLength > 0) forwardBuffered(0, bufferLength)
        mode = if (profile5) Mode.MAP else Mode.PASS
        if (mode == Mode.PASS) bufferLength = 0
    }

    private fun forwardBuffered(start: Int, length: Int) {
        forward.reset(buffer, start + length)
        forward.setPosition(start)
        delegate.sampleData(forward, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
    }

    private fun ensureCapacity(needed: Int) {
        if (buffer.size >= needed) return
        var newSize = if (buffer.isEmpty()) 256 * 1024 else buffer.size
        while (newSize < needed) newSize = newSize shl 1
        buffer = buffer.copyOf(newSize)
    }

    private companion object {
        /** HEVC Main 10, level 5.1: what a Profile 5 base layer decodes as. */
        const val HEVC_MAIN10_CODECS = "hvc1.2.4.L153.B0"
        /** About two seconds of video: enough to meet an RPU (every Profile 5 frame has one). */
        const val PROBE_SAMPLES = 48
        /** A first sample larger than this isn't waited for: the track plays as it is. */
        const val MAX_DEFERRED_BYTES = 16 * 1024 * 1024
        val PROFILE_5 = Regex("^(?:dvhe|dvh1)\\.0?5\\.")

        fun isProfile5(codecs: String?): Boolean =
            codecs != null && PROFILE_5.containsMatchIn(codecs.trim().lowercase())

        fun isHevcOrDolbyVision(mimeType: String?): Boolean =
            mimeType == MimeTypes.VIDEO_H265 || mimeType == MimeTypes.VIDEO_DOLBY_VISION

        fun toHdr10(format: Format): Format {
            val colorInfo = ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_FULL)
                .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                .setLumaBitdepth(10)
                .setChromaBitdepth(10)
                .setHdrStaticInfo(format.colorInfo?.hdrStaticInfo)
                .build()
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(HEVC_MAIN10_CODECS)
                .setColorInfo(colorInfo)
                .build()
        }
    }
}

/** Finds the Dolby Vision RPU NAL (type 62) of an HEVC sample. */
internal object DolbyVisionP5Nal {
    const val NONE = -1L
    private const val NAL_TYPE_RPU = 62

    /**
     * The RPU NAL in the first [length] bytes of [data], from its 2-byte NAL header on, packed as
     * (offset shl 32) or length; [NONE] when there is none. Handles Annex-B start codes and
     * 4-byte length prefixes.
     */
    fun findRpu(data: ByteArray, length: Int): Long {
        if (length < 6) return NONE
        val annexB = data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))
        return if (annexB) findAnnexB(data, length) else findLengthPrefixed(data, length)
    }

    inline fun ingest(data: ByteArray, packed: Long, parse: (ByteArray, Int, Int) -> Long): Long =
        parse(data, (packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())

    private fun pack(offset: Int, length: Int): Long = (offset.toLong() shl 32) or length.toLong()

    private fun findAnnexB(data: ByteArray, length: Int): Long {
        var i = 0
        while (true) {
            val start = nextStartCode(data, i, length)
            if (start < 0) return NONE
            val nalStart = start + 3
            if (nalStart >= length) return NONE
            val type = (data[nalStart].toInt() shr 1) and 0x3F
            if (type == NAL_TYPE_RPU) {
                var nalEnd = nextStartCode(data, nalStart + 2, length).let { if (it < 0) length else it }
                // A 4-byte start code leaves a zero byte before the next NAL.
                while (nalEnd > nalStart && data[nalEnd - 1].toInt() == 0) nalEnd--
                return if (nalEnd - nalStart >= 3) pack(nalStart, nalEnd - nalStart) else NONE
            }
            i = nalStart
        }
    }

    private fun findLengthPrefixed(data: ByteArray, length: Int): Long {
        var offset = 0
        while (offset + 4 < length) {
            val nalLength = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            val nalStart = offset + 4
            if (nalLength <= 0 || nalStart + nalLength > length) return NONE
            val type = (data[nalStart].toInt() shr 1) and 0x3F
            if (type == NAL_TYPE_RPU) return if (nalLength >= 3) pack(nalStart, nalLength) else NONE
            offset = nalStart + nalLength
        }
        return NONE
    }

    /** Index of the next 00 00 01 at or after [from], or -1. */
    private fun nextStartCode(data: ByteArray, from: Int, length: Int): Int {
        var i = from
        while (i + 2 < length) {
            // A byte above 1 can't be part of a start code: none starts at i, i+1 or i+2.
            if ((data[i + 2].toInt() and 0xFF) > 1) {
                i += 3
            } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                return i
            } else {
                i++
            }
        }
        return -1
    }
}

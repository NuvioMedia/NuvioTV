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
import java.io.EOFException
import java.io.IOException

/**
 * Wraps an [Extractor] so its video track feeds a [DolbyVisionP5Session].
 *
 * With an inactive session it only detects Profile 5: from the track's codecs, or else from the
 * RPUs of its first samples (a Matroska file whose colour metadata claims SDR loses its Dolby
 * Vision config, so its track reads as plain HEVC). With an active one it also makes the track
 * play as HDR10 through the [DolbyVisionP5HdrEffect]:
 *  - the format becomes plain HEVC Main10, so a regular HEVC decoder decodes the base layer;
 *  - its colour info becomes BT.2020 / PQ / full range, which routes the frames through Media3's
 *    10-bit HDR effect path and tells it how to read the decoded values (Profile 5 is full range);
 *  - each sample's RPU NAL is parsed and its mapping recorded at the sample's time.
 * Sample bytes are passed on unchanged: HEVC decoders ignore the RPU NAL type.
 *
 * Wrap it directly around the extractor that writes the samples, so it sees each RPU before any
 * other wrapper can strip it.
 */
@UnstableApi
internal class DolbyVisionP5Extractor(
    private val delegate: Extractor,
    private val session: DolbyVisionP5Session,
) : Extractor {

    override fun init(output: ExtractorOutput) {
        delegate.init(P5ExtractorOutput(output, session))
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@UnstableApi
private class P5ExtractorOutput(
    private val delegate: ExtractorOutput,
    private val session: DolbyVisionP5Session,
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) P5TrackOutput(track, session) else track
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class P5TrackOutput(
    private val delegate: TrackOutput,
    private val session: DolbyVisionP5Session,
) : TrackOutput {

    private var capturing = false
    // Detecting only: samples whose RPU is still to be checked for Profile 5.
    private var probeSamplesLeft = 0
    // The main sample bytes written since the last sample was committed.
    private var buffer = ByteArray(0)
    private var bufferLength = 0
    private val forward = ParsableByteArray()

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        val hevc = isHevcOrDolbyVision(format.sampleMimeType)
        val profile5 = isProfile5(format.codecs) || (session.containerSignalledProfile5 && hevc)
        if (profile5) session.onProfile5Track()
        // An active session exists only for a stream already found to be Profile 5.
        val mapping = session.active && hevc
        probeSamplesLeft = if (!mapping && hevc && !session.detected) PROBE_SAMPLES else 0
        capturing = mapping || probeSamplesLeft > 0
        delegate.format(if (mapping) toHdr10(format) else format)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (!capturing || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensureCapacity(bufferLength + length)
        val read = input.read(buffer, bufferLength, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        forwardBuffered(bufferLength, read, sampleDataPart)
        bufferLength += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!capturing || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensureCapacity(bufferLength + length)
        data.readBytes(buffer, bufferLength, length)
        forwardBuffered(bufferLength, length, sampleDataPart)
        bufferLength += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (capturing) {
            // [offset] bytes at the end already belong to the next sample.
            val carry = offset.coerceIn(0, bufferLength)
            val sampleEnd = bufferLength - carry
            if (cryptoData == null) {
                val rpu = DolbyVisionP5Nal.findRpu(buffer, sampleEnd)
                if (rpu != DolbyVisionP5Nal.NONE) {
                    val result = DolbyVisionP5Nal.ingest(buffer, rpu, DolbyVisionP5Native::ingestRpu)
                    session.onRpuResult(result)
                    // A sample without a usable RPU keeps the mapping before it.
                    if (session.active && result > 0L) session.onSampleMapping(timeUs, result)
                }
            }
            if (carry > 0) System.arraycopy(buffer, sampleEnd, buffer, 0, carry)
            bufferLength = carry
            if (!session.active && (session.detected || --probeSamplesLeft <= 0)) {
                // Detection settled: stop buffering. Everything was already passed on.
                capturing = false
                bufferLength = 0
            }
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun forwardBuffered(start: Int, length: Int, sampleDataPart: Int) {
        forward.reset(buffer, start + length)
        forward.setPosition(start)
        delegate.sampleData(forward, length, sampleDataPart)
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

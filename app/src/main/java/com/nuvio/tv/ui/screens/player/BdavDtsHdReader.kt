package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Reads a Blu-ray DTS-HD stream (HDMV stream types 0x85 and 0x86). Each frame is a DTS core
 * followed by its extension substream, often in a PES packet of its own. A frame is emitted whole
 * as one DTS sample, as Matroska delivers DTS-HD to the DTS decoder and to passthrough, once the
 * next core starts. Media3's DTS reader would emit the core and the extension substream as
 * separate samples and switch the track format between them.
 */
internal class BdavDtsHdReader(
    private val language: String?,
    private val roleFlags: Int
) : ElementaryStreamReader {
    private lateinit var output: TrackOutput
    private lateinit var formatId: String

    private var packet = ByteArray(16 * 1024)
    private var packetLength = 0
    private var packetTimeUs = C.TIME_UNSET

    private var formatEmitted = false
    private var frameDurationUs = 0L

    private var frame = ByteArray(16 * 1024)
    private var frameLength = 0
    private var frameTimeUs = C.TIME_UNSET
    private var nextFrameTimeUs = C.TIME_UNSET

    /** Bytes of the frame's last core or extension substream that are still to come in the next packet. */
    private var bytesOwed = 0

    override fun seek() {
        packetLength = 0
        packetTimeUs = C.TIME_UNSET
        frameLength = 0
        frameTimeUs = C.TIME_UNSET
        nextFrameTimeUs = C.TIME_UNSET
        bytesOwed = 0
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        packetLength = 0
        packetTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        val length = data.bytesLeft()
        if (packetLength + length > packet.size) packet = packet.copyOf(maxOf(packet.size * 2, packetLength + length))
        data.readBytes(packet, packetLength, length)
        packetLength += length
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        readPacket()
        packetLength = 0
        if (isEndOfInput) flushFrame()
    }

    private fun readPacket() {
        var position = 0
        if (bytesOwed > 0 && frameLength > 0 && !matches(packet, 0, packetLength, CORE_SYNC)) {
            position = minOf(bytesOwed, packetLength)
            appendToFrame(0, position)
            bytesOwed -= position
        }
        var packetTimeUnused = packetTimeUs != C.TIME_UNSET
        while (position < packetLength) {
            position = when {
                matches(packet, position, packetLength, CORE_SYNC) -> {
                    flushFrame()
                    if (!formatEmitted) emitFormat(position)
                    frameTimeUs = if (packetTimeUnused) packetTimeUs else nextFrameTimeUs
                    packetTimeUnused = false
                    position + appendBlock(position, coreLength(position))
                }
                frameLength > 0 && matches(packet, position, packetLength, EXTENSION_SUBSTREAM_SYNC) ->
                    position + appendBlock(position, extensionSubstreamLength(packet, position, packetLength))
                else -> nextSync(position + 1)
            }
        }
    }

    /** Appends the block of [length] bytes at [position], or the rest of the packet when [length] is unknown. */
    private fun appendBlock(position: Int, length: Int): Int {
        val available = packetLength - position
        val taken = if (length > 0) minOf(length, available) else available
        appendToFrame(position, taken)
        bytesOwed = if (length > 0) length - taken else 0
        return taken
    }

    private fun coreLength(position: Int): Int {
        if (position + CORE_HEADER_LENGTH > packetLength) return C.LENGTH_UNSET
        val length = DtsUtil.getDtsFrameSize(packet.copyOfRange(position, position + CORE_HEADER_LENGTH))
        return if (length >= CORE_HEADER_LENGTH) length else C.LENGTH_UNSET
    }

    private fun nextSync(from: Int): Int {
        var position = from
        while (position < packetLength) {
            if (matches(packet, position, packetLength, CORE_SYNC) ||
                matches(packet, position, packetLength, EXTENSION_SUBSTREAM_SYNC)
            ) return position
            position++
        }
        return packetLength
    }

    private fun appendToFrame(position: Int, length: Int) {
        if (frameLength + length > frame.size) frame = frame.copyOf(maxOf(frame.size * 2, frameLength + length))
        System.arraycopy(packet, position, frame, frameLength, length)
        frameLength += length
    }

    /** Writes the frame as one sample. Data is only written whole, so a seek never leaves a partial sample. */
    private fun flushFrame() {
        if (frameLength > 0 && formatEmitted && frameTimeUs != C.TIME_UNSET) {
            output.sampleData(ParsableByteArray(frame.copyOf(frameLength)), frameLength)
            output.sampleMetadata(frameTimeUs, C.BUFFER_FLAG_KEY_FRAME, frameLength, 0, null)
        }
        nextFrameTimeUs = if (frameTimeUs != C.TIME_UNSET && frameDurationUs > 0) frameTimeUs + frameDurationUs else C.TIME_UNSET
        frameLength = 0
        frameTimeUs = C.TIME_UNSET
        bytesOwed = 0
    }

    private fun emitFormat(position: Int) {
        if (position + CORE_HEADER_LENGTH > packetLength) return
        val header = packet.copyOfRange(position, position + CORE_HEADER_LENGTH)
        val format = DtsUtil.parseDtsFormat(header, formatId, language, roleFlags, MimeTypes.VIDEO_MP2T, null)
        if (format.sampleRate <= 0) return
        frameDurationUs = DtsUtil.parseDtsAudioSampleCount(header) * C.MICROS_PER_SECOND / format.sampleRate
        output.format(format)
        formatEmitted = true
    }

    companion object {
        /** Enough of a core frame for its sync word, frame size, channels, sample rate and bitrate. */
        const val CORE_HEADER_LENGTH = 16

        private val CORE_SYNC = byteArrayOf(0x7F, 0xFE.toByte(), 0x80.toByte(), 0x01)
        private val EXTENSION_SUBSTREAM_SYNC = byteArrayOf(0x64, 0x58, 0x20, 0x25)

        /**
         * The length of the extension substream at [position] from its header's nuExtSSFsize, or
         * [C.LENGTH_UNSET] when the header is cut off. After the sync word come 8 user bits, a 2-bit
         * substream index, the size type, then the header size and the frame size: 8 and 16 bits,
         * or 12 and 20 bits.
         */
        fun extensionSubstreamLength(data: ByteArray, position: Int, limit: Int): Int {
            if (position + 10 > limit) return C.LENGTH_UNSET
            val bits = ParsableBitArray(data.copyOfRange(position + 4, position + 10))
            bits.skipBits(8 + 2)
            val longSizes = bits.readBit()
            bits.skipBits(if (longSizes) 12 else 8)
            return bits.readBits(if (longSizes) 20 else 16) + 1
        }

        private fun matches(data: ByteArray, position: Int, limit: Int, pattern: ByteArray): Boolean =
            position + pattern.size <= limit && pattern.indices.all { data[position + it] == pattern[it] }
    }
}

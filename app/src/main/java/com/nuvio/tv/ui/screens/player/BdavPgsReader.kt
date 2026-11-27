package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Reads a Blu-ray presentation graphics stream (HDMV stream type 0x90), the disc's subtitles.
 * PES packets carry PGS segments, each a type byte and a 16-bit length. A display set runs from a
 * presentation composition segment to an end segment, often over several packets; it is emitted
 * whole, at the time of the packet that starts it, as Matroska delivers PGS to Media3's PgsParser.
 */
internal class BdavPgsReader(
    private val language: String?,
    private val roleFlags: Int
) : ElementaryStreamReader {
    private lateinit var output: TrackOutput

    private var pending = ByteArray(16 * 1024)
    private var pendingLength = 0
    private var packetTimeUs = C.TIME_UNSET

    private var displaySet = ByteArray(16 * 1024)
    private var displaySetLength = 0
    private var displaySetTimeUs = C.TIME_UNSET

    override fun seek() {
        pendingLength = 0
        packetTimeUs = C.TIME_UNSET
        resetDisplaySet()
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        output.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .setRoleFlags(roleFlags)
                .build()
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        packetTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        val length = data.bytesLeft()
        if (pendingLength + length > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLength + length))
        data.readBytes(pending, pendingLength, length)
        pendingLength += length
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        var position = 0
        while (position + SEGMENT_HEADER_LENGTH <= pendingLength) {
            val type = pending[position].toInt() and 0xFF
            val segmentLength = SEGMENT_HEADER_LENGTH +
                (((pending[position + 1].toInt() and 0xFF) shl 8) or (pending[position + 2].toInt() and 0xFF))
            // A segment cut by the packet's end waits for the rest in the next packet.
            if (position + segmentLength > pendingLength) break
            onSegment(type, position, segmentLength)
            position += segmentLength
        }
        pendingLength -= position
        System.arraycopy(pending, position, pending, 0, pendingLength)
        if (isEndOfInput) pendingLength = 0
    }

    private fun onSegment(type: Int, position: Int, length: Int) {
        if (type == SEGMENT_PRESENTATION_COMPOSITION) {
            // A new display set; one left without its end segment is dropped.
            resetDisplaySet()
            displaySetTimeUs = packetTimeUs
        }
        // Segments before the first composition, as after a seek, belong to a set already missed.
        if (displaySetTimeUs == C.TIME_UNSET) return
        if (displaySetLength + length > displaySet.size) displaySet = displaySet.copyOf(maxOf(displaySet.size * 2, displaySetLength + length))
        System.arraycopy(pending, position, displaySet, displaySetLength, length)
        displaySetLength += length
        if (type == SEGMENT_END) {
            output.sampleData(ParsableByteArray(displaySet.copyOf(displaySetLength)), displaySetLength)
            output.sampleMetadata(displaySetTimeUs, C.BUFFER_FLAG_KEY_FRAME, displaySetLength, 0, null)
            resetDisplaySet()
        }
    }

    private fun resetDisplaySet() {
        displaySetLength = 0
        displaySetTimeUs = C.TIME_UNSET
    }

    companion object {
        private const val SEGMENT_HEADER_LENGTH = 3
        const val SEGMENT_PRESENTATION_COMPOSITION = 0x16
        const val SEGMENT_END = 0x80
    }
}

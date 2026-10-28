package com.nuvio.tv.ui.screens.player.mpvass

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.nuvio.tv.core.player.dvmkv.EbmlProcessor
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import java.io.EOFException

internal interface MatroskaEvents {
    /** Where the attachments are: their element, or their content and its length. */
    fun attachments(uri: Uri, position: Long, length: Long?)
}

@OptIn(UnstableApi::class)
internal class MatroskaReader(
    private val sink: AssSink,
    private val readBack: () -> String?,
    private val uri: Uri?,
    private val events: MatroskaEvents,
    dolbyVisionSampleTransformer: MatroskaExtractor.DolbyVisionSampleTransformer?,
) : Extractor {
    private val matroska = Matroska(dolbyVisionSampleTransformer) { position, length -> uri?.let { events.attachments(it, position, length) } }
    private var transcoder: SubtitleTranscodingExtractorOutput? = null

    @Volatile
    private var seekMap: SeekMap? = null

    override fun sniff(input: ExtractorInput) = matroska.sniff(input)

    override fun init(output: ExtractorOutput) {
        val transcoder = SubtitleTranscodingExtractorOutput(output, Silent).also { transcoder = it }
        val seeking = object : ExtractorOutput by transcoder {
            override fun seekMap(seekMap: SeekMap) {
                this@MatroskaReader.seekMap = seekMap
                transcoder.seekMap(ReadBack(seekMap))
            }
        }
        matroska.init(Subtitles(seeking, sink))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = matroska.read(Skips(input), seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        transcoder?.resetSubtitleParsers()
        matroska.seek(position, timeUs)
        // Media3 itself starts at the keyframe's cluster, so starting earlier is a read-back.
        val keyframe = seekMap?.getSeekPoints(timeUs)?.first?.position ?: return
        matroska.readBack(if (position < keyframe) keyframe else -1, readBack())
    }

    override fun release() = matroska.release()

    override fun getUnderlyingImplementation(): Extractor = matroska

    private inner class Skips(private val input: ExtractorInput) : ExtractorInput by input {
        override fun skipFully(length: Int) {
            matroska.skipping(input.position, length.toLong())
            input.skipFully(length)
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            matroska.skipping(input.position, length.toLong())
            return input.skipFully(length, allowEndOfInput)
        }
    }

    /** Media3's seek map, starting each seek early enough to read the subtitle lines already showing at its target. */
    private inner class ReadBack(private val map: SeekMap) : SeekMap by map {
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val points = map.getSeekPoints(timeUs)
            val from = matroska.index.earliest(readBack(), timeUs)
            if (from == null || from >= points.first.position) return points
            val first = SeekPoint(points.first.timeUs, from)
            return if (points.second == points.first) SeekMap.SeekPoints(first) else SeekMap.SeekPoints(first, points.second)
        }
    }

    companion object {
        const val ANY_TRACK = "*"
    }
}

@OptIn(UnstableApi::class)
private class Matroska(
    dolbyVisionSampleTransformer: MatroskaExtractor.DolbyVisionSampleTransformer?,
    private val attachments: (position: Long, length: Long?) -> Unit,
) : MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA, dolbyVisionSampleTransformer) {
    val index = SubtitleIndex()
    private var segment = 0L
    private var seekId = 0L
    private var seekPosition = -1L
    private var attachmentsFound = false
    private var attachmentsNext = false
    private var timecodeScale = 1_000_000L
    private var cueTime = -1L
    private var cueTrack = -1L
    private var cuePosition = -1L
    private var cueDuration = -1L

    /** Before this cluster, only [readBackTrack]'s blocks are read. */
    private var readBackUntil = -1L
    private var readBackTrack: String? = null
    private var readingBack = false
    private val varint = ByteArray(8)

    private val assTracks = HashSet<Long>()
    private var entryNumber = -1L
    private var entryCodec: String? = null

    // A read error can stop a block part way, and the next read carries on in it.
    private var inMedia3Block = false
    private var takeSize = -1
    private var taken = 0

    fun readBack(until: Long, track: String?) {
        readBackUntil = until
        readBackTrack = track
        readingBack = false
    }

    override fun seek(position: Long, timeUs: Long) {
        inMedia3Block = false
        takeSize = -1
        super.seek(position, timeUs)
    }

    override fun getElementType(id: Int) = when (id) {
        ATTACHMENTS -> EbmlProcessor.ELEMENT_TYPE_UNKNOWN.also { attachmentsNext = true }
        CUE_DURATION -> EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT
        else -> super.getElementType(id)
    }

    /** Reports the attachments' content as the reader skips it. */
    fun skipping(position: Long, length: Long) {
        if (!attachmentsNext) return
        attachmentsNext = false
        if (!attachmentsFound) {
            attachmentsFound = true
            attachments(position, length)
        }
    }

    override fun isLevel1Element(id: Int) = id == ATTACHMENTS || super.isLevel1Element(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            SEGMENT -> segment = contentPosition
            SEEK -> {
                seekId = 0
                seekPosition = -1
            }
            TRACK_ENTRY -> {
                entryNumber = -1
                entryCodec = null
            }
            CLUSTER -> readingBack = contentPosition < readBackUntil
            CUE_POINT -> cueTime = -1
            CUE_TRACK_POSITIONS -> {
                cueTrack = -1
                cuePosition = -1
                cueDuration = -1
            }
        }
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun endMasterElement(id: Int) {
        when (id) {
            CUE_TRACK_POSITIONS -> if (cueTime >= 0 && cueTrack >= 0 && cuePosition >= 0 && cueDuration > 0 && cueTrack in assTracks) {
                index.add(cueTrack, scale(cueTime), scale(cueDuration), segment + cuePosition)
            }
            // Before Media3 sends the seek map that reads it.
            CUES -> index.finish()
            SEEK -> if (seekId == ATTACHMENTS.toLong() && seekPosition >= 0 && !attachmentsFound) {
                attachmentsFound = true
                attachments(segment + seekPosition, null)
            }
            TRACK_ENTRY -> if (entryNumber >= 0 && entryCodec in ASS_CODECS) assTracks += entryNumber
        }
        super.endMasterElement(id)
    }

    override fun integerElement(id: Int, value: Long) {
        when (id) {
            TIMECODE_SCALE -> timecodeScale = value
            TRACK_NUMBER -> entryNumber = value
            CUE_TIME -> cueTime = value
            CUE_TRACK -> cueTrack = value
            CUE_CLUSTER_POSITION -> cuePosition = value
            CUE_DURATION -> return run { cueDuration = value }
            SEEK_POSITION -> seekPosition = value
        }
        super.integerElement(id, value)
    }

    override fun stringElement(id: Int, value: String) {
        if (id == CODEC_ID) entryCodec = value
        super.stringElement(id, value)
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if (id == SEEK_ID && contentSize in 1..4) {
            input.peekFully(varint, 0, contentSize)
            input.resetPeekPosition()
            seekId = (0 until contentSize).fold(0L) { value, i -> (value shl 8) or (varint[i].toLong() and 0xFF) }
        }
        if (id != SIMPLE_BLOCK && id != BLOCK) return super.binaryElement(id, contentSize, input)
        if (!inMedia3Block && takeSize < 0 && readingBack) {
            val track = trackOf(input)
            if (track != null && !readsBack(track)) startTaking(contentSize)
        }
        if (takeSize < 0) {
            inMedia3Block = true
            super.binaryElement(id, contentSize, input)
            inMedia3Block = false
            return
        }
        take(input)
    }

    private fun startTaking(size: Int) {
        takeSize = size
        taken = 0
    }

    private fun take(input: ExtractorInput) {
        while (taken < takeSize) {
            val read = input.skip(takeSize - taken)
            if (read == C.RESULT_END_OF_INPUT) throw EOFException()
            taken += read
        }
        takeSize = -1
    }

    /** The track number the block about to be read starts with. */
    private fun trackOf(input: ExtractorInput): Long? {
        input.peekFully(varint, 0, 1)
        val length = varintLength(varint[0])
        if (length !in 1..8) return null.also { input.resetPeekPosition() }
        if (length > 1) input.peekFully(varint, 1, length - 1)
        input.resetPeekPosition()
        var track = varint[0].toLong() and (0xFFL shr length)
        for (i in 1 until length) track = (track shl 8) or (varint[i].toLong() and 0xFF)
        return track
    }

    /** Whether a block of `track`'s is one of [readBackTrack]'s. */
    private fun readsBack(track: Long) = when (val showing = readBackTrack) {
        MatroskaReader.ANY_TRACK -> index.has(track)
        else -> track.toString() == showing
    }

    private fun varintLength(first: Byte) = Integer.numberOfLeadingZeros(first.toInt() and 0xFF) - 23

    private fun scale(timecode: Long) = Util.scaleLargeTimestamp(timecode, timecodeScale, 1000)

    private companion object {
        const val SEGMENT = 0x18538067
        const val TIMECODE_SCALE = 0x2AD7B1
        const val TRACK_ENTRY = 0xAE
        const val TRACK_NUMBER = 0xD7
        const val CODEC_ID = 0x86
        const val CLUSTER = 0x1F43B675
        const val SIMPLE_BLOCK = 0xA3
        const val BLOCK = 0xA1
        const val CUES = 0x1C53BB6B
        const val CUE_POINT = 0xBB
        const val CUE_TIME = 0xB3
        const val CUE_TRACK_POSITIONS = 0xB7
        const val CUE_TRACK = 0xF7
        const val CUE_CLUSTER_POSITION = 0xF1
        const val CUE_DURATION = 0xB2
        const val ATTACHMENTS = 0x1941A469
        const val SEEK = 0x4DBB
        const val SEEK_ID = 0x53AB
        const val SEEK_POSITION = 0x53AC
        val ASS_CODECS = setOf("S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA")
    }
}

/** Where the subtitle lines with a duration in Matroska's index sit in the file. */
private class SubtitleIndex {
    private class Line(val startUs: Long, val endUs: Long, val position: Long)

    private val reading = HashMap<Long, MutableList<Line>>()

    @Volatile
    private var tracks: Map<Long, List<Line>>? = null

    fun add(track: Long, startUs: Long, durationUs: Long, position: Long) {
        if (tracks == null) reading.getOrPut(track) { mutableListOf() } += Line(startUs, startUs + durationUs, position)
    }

    fun finish() {
        if (tracks == null) tracks = reading.mapValues { (_, lines) -> lines.sortedBy { it.startUs } }
        reading.clear()
    }

    fun has(track: Long) = tracks?.containsKey(track) == true

    /** Where the first of `track`'s lines showing at `timeUs` sits, of those begun up to [READ_BACK_US] before. */
    fun earliest(track: String?, timeUs: Long): Long? {
        val tracks = tracks ?: return null
        val lines = when (track) {
            null -> return null
            MatroskaReader.ANY_TRACK -> tracks.values
            else -> listOfNotNull(track.toLongOrNull()?.let(tracks::get))
        }
        var earliest: Long? = null
        for (list in lines) {
            var i = firstFrom(list, timeUs - READ_BACK_US)
            while (i < list.size && list[i].startUs <= timeUs) {
                val line = list[i++]
                if (line.endUs > timeUs && line.position < (earliest ?: Long.MAX_VALUE)) earliest = line.position
            }
        }
        return earliest
    }

    private fun firstFrom(lines: List<Line>, timeUs: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startUs < timeUs) low = mid + 1 else high = mid
        }
        return low
    }

    private companion object {
        // mpv's demuxer-mkv-subtitle-preroll-secs-index.
        const val READ_BACK_US = 10_000_000L
    }
}

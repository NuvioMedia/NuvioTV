package com.nuvio.tv.ui.screens.player.mpvass

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException
import java.io.File

/** A font the file carries, on disk, and the names it answers to, null where it couldn't be read. */
internal class FontFile(val file: File, val names: Set<String>?)

/** Where a file's ASS goes as it is read: its fonts, each track's header, and the lines. */
internal interface AssSink {
    fun fonts(fonts: List<FontFile>)

    fun header(track: String, header: ByteArray)

    fun line(track: String, startMs: Long, durationMs: Long, line: ByteArray)
}

internal fun isAss(format: Format) = format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA

private val parsers = DefaultSubtitleParserFactory()

@OptIn(UnstableApi::class)
internal object Silent : SubtitleParser.Factory {
    override fun supportsFormat(format: Format) = parsers.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) = parsers.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = if (isAss(format)) Nothing else parsers.create(format)

    private object Nothing : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {}

        override fun getCueReplacementBehavior() = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }
}

@OptIn(UnstableApi::class)
internal class Scripts(private val onScript: (key: String, script: ByteArray) -> Unit) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format) = parsers.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) = parsers.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = if (isAss(format)) Script(format.id.orEmpty()) else parsers.create(format)

    private inner class Script(private val key: String) : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            onScript(key, data.copyOfRange(offset, offset + length))
        }

        override fun getCueReplacementBehavior() = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }
}

/** Takes each subtitle sample, with the time Matroska gives it, on its way to the player. */
@OptIn(UnstableApi::class)
internal class Subtitles(private val output: ExtractorOutput, private val sink: AssSink) : ExtractorOutput by output {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = output.track(id, type)
        return if (type == C.TRACK_TYPE_TEXT) SubtitleOutput(track, sink) else track
    }
}

@OptIn(UnstableApi::class)
private class SubtitleOutput(private val track: TrackOutput, private val sink: AssSink) : TrackOutput by track {
    private var id: String? = null
    private var pending = ByteArray(4096)
    private var filled = 0

    override fun format(format: Format) {
        id = format.id
        when {
            id == null -> {}
            isAss(format) -> format.initializationData.getOrNull(1)?.let { sink.header(id!!, it) }
            else -> id = null
        }
        track.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean) =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (id == null) return track.sampleData(input, length, allowEndOfInput, sampleDataPart)
        reserve(length)
        val read = input.read(pending, filled, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        track.sampleData(ParsableByteArray(pending.copyOfRange(filled, filled + read)), read, sampleDataPart)
        filled += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (id != null) {
            reserve(length)
            System.arraycopy(data.data, data.position, pending, filled, length)
            filled += length
        }
        track.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val id = id
        if (id != null) {
            val end = filled - offset
            if (timeUs != C.TIME_UNSET) take(id, timeUs, end - size, end)
            System.arraycopy(pending, end, pending, 0, offset)
            filled = offset
        }
        track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun take(id: String, timeUs: Long, from: Int, to: Int) {
        dialogue(from, to)?.let { (durationMs, line) -> sink.line(id, timeUs / 1000, durationMs, line) }
    }

    private fun reserve(length: Int) {
        if (filled + length > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, filled + length))
    }

    /** Matroska's ASS sample, `Dialogue: 0:00:00:00,<duration>,<line>`, as its duration and line. */
    private fun dialogue(from: Int, to: Int): Pair<Long, ByteArray>? {
        val first = indexOfComma(from, to)
        val second = indexOfComma(first + 1, to)
        if (first < 0 || second < 0) return null
        val parts = String(pending, first + 1, second - first - 1, Charsets.US_ASCII).split(':', '.')
        if (parts.size != 4) return null
        val (hours, minutes, seconds, centis) = parts.map { it.toLongOrNull() ?: return null }
        val durationMs = ((hours * 60 + minutes) * 60 + seconds) * 1000 + centis * 10
        return durationMs to pending.copyOfRange(second + 1, to)
    }

    private fun indexOfComma(from: Int, to: Int): Int {
        if (from < 0) return -1
        for (i in from until to) if (pending[i] == ','.code.toByte()) return i
        return -1
    }
}

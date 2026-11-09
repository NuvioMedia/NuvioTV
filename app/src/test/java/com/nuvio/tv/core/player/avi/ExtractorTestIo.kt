package com.nuvio.tv.core.player.avi

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/** Bytes at a file position. */
internal interface ByteSource {
    val length: Long
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

internal class ArraySource(private val data: ByteArray) : ByteSource {
    override val length = data.size.toLong()
    override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), target, offset, count)
        return count
    }
}

internal class FileSource(file: File) : ByteSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val buffer = ByteArray(1 shl 20)
    private var bufferStart = -1L
    private var bufferLength = 0
    override val length = raf.length()

    override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return C.RESULT_END_OF_INPUT
        if (bufferStart < 0 || position < bufferStart || position >= bufferStart + bufferLength) {
            raf.seek(position)
            bufferLength = maxOf(0, raf.read(buffer))
            bufferStart = position
        }
        val at = (position - bufferStart).toInt()
        val count = minOf(length, bufferLength - at)
        System.arraycopy(buffer, at, target, offset, count)
        return count
    }

    override fun close() = raf.close()
}

/** An [ExtractorInput] over a [ByteSource] that can start at any position. */
internal class SourceInput(private val source: ByteSource, private var position: Long = 0) : ExtractorInput {
    private var peekPosition = position

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = source.readAt(position, buffer, offset, length)
        if (read > 0) {
            position += read
            peekPosition = maxOf(peekPosition, position)
        }
        return read
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val savedPeek = peekPosition
        peekPosition = position
        if (!peekFully(target, offset, length, allowEndOfInput)) {
            peekPosition = savedPeek
            return false
        }
        position = peekPosition
        peekPosition = maxOf(savedPeek, position)
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        val count = minOf(length.toLong(), source.length - position).toInt()
        if (count <= 0) return C.RESULT_END_OF_INPUT
        position += count
        peekPosition = maxOf(peekPosition, position)
        return count
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        val savedPeek = peekPosition
        peekPosition = position
        if (!advancePeekPosition(length, allowEndOfInput)) {
            peekPosition = savedPeek
            return false
        }
        position = peekPosition
        peekPosition = maxOf(savedPeek, position)
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val read = source.readAt(peekPosition, target, offset, length)
        if (read > 0) peekPosition += read
        return read
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (peekPosition + length > source.length) {
            if (allowEndOfInput && peekPosition >= source.length) return false
            throw EOFException()
        }
        var done = 0
        while (done < length) {
            val read = source.readAt(peekPosition + done, target, offset + done, length - done)
            if (read <= 0) throw EOFException()
            done += read
        }
        peekPosition += length
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        if (peekPosition + length > source.length) {
            if (allowEndOfInput && peekPosition >= source.length) return false
            throw EOFException()
        }
        peekPosition += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekPosition = position
    }

    override fun getPeekPosition(): Long = peekPosition
    override fun getPosition(): Long = position
    override fun getLength(): Long = source.length
    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        throw e
    }
}

internal class Sample(val timeUs: Long, val flags: Int, val size: Int, val firstByte: Int)

internal class RecordingTrack(val type: Int) : TrackOutput {
    var format: Format? = null
    val samples = ArrayList<Sample>()
    private var pending = ByteArray(0)
    private var pendingSize = 0

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        ensure(length)
        val read = input.read(pending, pendingSize, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        pendingSize += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        ensure(length)
        data.readBytes(pending, pendingSize, length)
        pendingSize += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        val start = pendingSize - offset - size
        samples += Sample(timeUs, flags, size, if (size > 0) pending[start].toInt() and 0xFF else -1)
        pendingSize = 0
    }

    private fun ensure(more: Int) {
        if (pendingSize + more > pending.size) pending = pending.copyOf(maxOf(pendingSize + more, pending.size * 2))
    }
}

internal class RecordingOutput : ExtractorOutput {
    val tracks = LinkedHashMap<Int, RecordingTrack>()
    var seekMap: SeekMap? = null

    override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack(type) }
    override fun endTracks() {}
    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    fun track(type: Int): RecordingTrack = tracks.values.first { it.type == type }
}

/** Runs [extractor] from [start] until the end of the input or until [stop] says so; returns the input. */
internal fun runExtractor(
    extractor: Extractor,
    source: ByteSource,
    start: Long = 0,
    stop: () -> Boolean = { false },
): SourceInput {
    var input = SourceInput(source, start)
    val holder = PositionHolder()
    while (!stop()) {
        when (extractor.read(input, holder)) {
            Extractor.RESULT_END_OF_INPUT -> break
            Extractor.RESULT_SEEK -> input = SourceInput(source, holder.position)
        }
    }
    return input
}

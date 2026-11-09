package com.nuvio.tv.core.player

import java.util.Arrays
import kotlin.math.abs

/**
 * Times the audio chunks of one AVI stream by what comes before them in the stream: the bytes for a stream with a
 * sample size (constant bitrate), otherwise the chunks, one block of scale / rate each.
 *
 * The stock reader spreads the stream's duration evenly over its chunks. That only holds when every chunk is the same
 * size, and muxers usually put about half a second of audio into the first chunk, so every later chunk was stamped
 * that much early. After a seek its chunk number is also one too low, as it points at the chunk before the seek
 * position.
 *
 * A chunk is found in the stream's idx1 entries by the file position of its data. A chunk the index does not list
 * follows on from the chunk before it, or takes the reader's time when there is none.
 */
internal class AviAudioClock private constructor(
    val countsBytes: Boolean,
    private val usNumerator: Long,
    private val usDenominator: Long,
) {
    private var dataPositions = LongArray(0)
    private var bytesBefore: LongArray? = null
    private var running = false
    private var nextUnits = 0L

    /** Chunks listed in the index, 0 without one. */
    var indexedChunks = 0
        private set

    /** Whether the last chunk was found in the index. */
    var lastChunkIndexed = false
        private set

    /** Takes the stream's idx1 entries; false (and no index) when the positions do not run in file order. */
    fun useIndex(entries: AviIndex.Entries): Boolean {
        val positions = entries.dataPositions
        for (i in 1 until positions.size) {
            if (positions[i] <= positions[i - 1]) return false
        }
        bytesBefore = if (countsBytes) {
            LongArray(entries.sizes.size).also { before ->
                var sum = 0L
                for (i in entries.sizes.indices) {
                    before[i] = sum
                    sum += entries.sizes[i]
                }
            }
        } else {
            null
        }
        dataPositions = positions
        indexedChunks = positions.size
        return true
    }

    /** The reader moved: the next chunk is placed by the index or by the reader's time, not by the last chunk. */
    fun reset() {
        running = false
    }

    /**
     * The time of the chunk whose data starts at [dataPosition] (negative when unknown) and is [size] bytes long,
     * which the stock reader stamped [readerTimeUs].
     */
    fun chunkTimeUs(dataPosition: Long, size: Int, readerTimeUs: Long): Long {
        val index = if (dataPosition >= 0 && dataPositions.isNotEmpty()) {
            Arrays.binarySearch(dataPositions, dataPosition)
        } else {
            -1
        }
        lastChunkIndexed = index >= 0
        val units = when {
            index >= 0 -> bytesBefore?.get(index) ?: index.toLong()
            running -> nextUnits
            else -> scale(readerTimeUs.coerceAtLeast(0L), usDenominator, usNumerator)
        }
        running = true
        nextUnits = units + if (countsBytes) size.toLong() else 1L
        return scale(units, usNumerator, usDenominator)
    }

    companion object {
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val MIN_BYTES_PER_SECOND = 1_000L
        private const val MAX_BYTES_PER_SECOND = 20_000_000L
        private const val MAX_HEADER_MISMATCH = 0.1
        private const val MIN_BLOCK_US = 1_000L
        private const val MAX_BLOCK_US = 2_000_000L

        /** A clock for [stream], or null when its header gives no usable rate. */
        fun forStream(stream: AviHeader.Stream): AviAudioClock? {
            if (!stream.isAudio) return null
            if (stream.sampleSize > 0) {
                val strhUsable = stream.scale > 0 && stream.rate > 0
                val strhBytesPerSecond =
                    if (strhUsable) stream.rate * stream.sampleSize / stream.scale.toDouble() else 0.0
                val avg = stream.avgBytesPerSecond.toDouble()
                if (strhUsable && avg > 0 && abs(strhBytesPerSecond - avg) > avg * MAX_HEADER_MISMATCH) return null
                val bytesPerSecond = if (strhUsable) strhBytesPerSecond else avg
                if (bytesPerSecond < MIN_BYTES_PER_SECOND || bytesPerSecond > MAX_BYTES_PER_SECOND) return null
                return if (strhUsable) {
                    reduced(true, stream.scale * MICROS_PER_SECOND, stream.rate * stream.sampleSize)
                } else {
                    reduced(true, MICROS_PER_SECOND, stream.avgBytesPerSecond)
                }
            }
            if (stream.scale <= 0 || stream.rate <= 0) return null
            val blockUs = stream.scale * MICROS_PER_SECOND / stream.rate
            if (blockUs < MIN_BLOCK_US || blockUs > MAX_BLOCK_US) return null
            return reduced(false, stream.scale * MICROS_PER_SECOND, stream.rate)
        }

        private fun reduced(countsBytes: Boolean, numerator: Long, denominator: Long): AviAudioClock {
            val divisor = gcd(numerator, denominator)
            return AviAudioClock(countsBytes, numerator / divisor, denominator / divisor)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        /** value * multiplier / divisor, rounded down, without overflowing for the sizes found in AVI files. */
        internal fun scale(value: Long, multiplier: Long, divisor: Long): Long {
            val whole = value / divisor
            val rest = value % divisor
            val restScaled = if (rest != 0L && multiplier > Long.MAX_VALUE / rest) {
                (rest.toDouble() * multiplier / divisor).toLong()
            } else {
                rest * multiplier / divisor
            }
            return whole * multiplier + restScaled
        }
    }
}

/** The chunk index the AVI reader uses, from idx1 or from the OpenDML indexes. */
internal object AviIndex {

    /** Where the data of each chunk of a stream starts in the file, and its size, in index order. */
    class Entries(val dataPositions: LongArray, val sizes: IntArray)

    /** The reader's index entries in its order: chunk id, AVIIF flags, chunk header position and data size. */
    class Chunks(val chunkIds: IntArray, val flags: IntArray, val positions: LongArray, val sizes: IntArray, val count: Int)

    private const val CHUNK_HEADER_BYTES = 8
    private const val KEY_FRAME_FLAG = 0x10

    /** The audio ('##wb') entries of [streams]. */
    fun audioEntries(chunks: Chunks, streams: Collection<Int>): Map<Int, Entries> {
        if (streams.isEmpty()) return emptyMap()
        val counts = HashMap<Int, Int>()
        forEachChunk(chunks, 'w', 'b', streams) { stream, _ -> counts[stream] = (counts[stream] ?: 0) + 1 }
        val positions = counts.mapValues { LongArray(it.value) }
        val sizes = counts.mapValues { IntArray(it.value) }
        val filled = HashMap<Int, Int>()
        forEachChunk(chunks, 'w', 'b', streams) { stream, at ->
            val i = filled[stream] ?: 0
            positions.getValue(stream)[i] = chunks.positions[at] + CHUNK_HEADER_BYTES
            sizes.getValue(stream)[i] = chunks.sizes[at]
            filled[stream] = i + 1
        }
        return counts.keys.associateWith { Entries(positions.getValue(it), sizes.getValue(it)) }
    }

    /** The key frames of a video stream: their number among the stream's chunks, and where their chunk starts. */
    class KeyFrames(val chunkIndices: IntArray, val positions: LongArray)

    /** The key frame entries ('##dc' or '##db') of video [stream], as the reader keeps them. */
    fun videoKeyFrames(chunks: Chunks, stream: Int): KeyFrames? {
        val indices = ArrayList<Int>()
        val positions = ArrayList<Long>()
        var count = 0
        for (at in 0 until chunks.count) {
            val id = chunks.chunkIds[at]
            val type1 = (id ushr 24) and 0xFF
            if ((id ushr 16) and 0xFF == 'd'.code && (type1 == 'c'.code || type1 == 'b'.code) &&
                streamNumber(id) == stream
            ) {
                if (chunks.flags[at] and KEY_FRAME_FLAG != 0) {
                    indices += count
                    positions += chunks.positions[at]
                }
                count++
            }
        }
        if (indices.isEmpty()) return null
        return KeyFrames(indices.toIntArray(), positions.toLongArray())
    }

    private inline fun forEachChunk(
        chunks: Chunks,
        type0: Char,
        type1: Char,
        streams: Collection<Int>,
        action: (stream: Int, at: Int) -> Unit,
    ) {
        for (at in 0 until chunks.count) {
            val id = chunks.chunkIds[at]
            if ((id ushr 16) and 0xFF == type0.code && (id ushr 24) and 0xFF == type1.code) {
                val stream = streamNumber(id)
                if (stream >= 0 && stream in streams) action(stream, at)
            }
        }
    }

    private fun streamNumber(chunkId: Int): Int {
        val tens = (chunkId and 0xFF) - '0'.code
        val units = ((chunkId ushr 8) and 0xFF) - '0'.code
        return if (tens in 0..9 && units in 0..9) tens * 10 + units else -1
    }
}

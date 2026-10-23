package com.nuvio.tv.ui.screens.player.iec

/**
 * Frames written to the IEC track that carry no content (keep-alive filler). The media clock skips
 * over them, so a pause or a seek leaves the picture in step with the sound that follows.
 * Positions are absolute track frames since the track was opened.
 */
internal class IecKeepAliveLedger {
    private val starts = LongArray(INITIAL_CAPACITY).let { ArrayListLong(it) }
    private val ends = LongArray(INITIAL_CAPACITY).let { ArrayListLong(it) }

    val fillerFrames: Long
        get() {
            var total = 0L
            for (i in 0 until starts.size) total += ends[i] - starts[i]
            return total
        }

    /** Filler written from [startFrame] (inclusive) to [endFrame] (exclusive). Adjacent runs merge. */
    fun add(startFrame: Long, endFrame: Long) {
        if (endFrame <= startFrame) return
        val last = starts.size - 1
        if (last >= 0 && ends[last] == startFrame) {
            ends[last] = endFrame
        } else {
            starts.add(startFrame)
            ends.add(endFrame)
        }
    }

    /** Filler frames that lie before the absolute track position [frame]. */
    fun fillerBefore(frame: Long): Long {
        var total = 0L
        for (i in 0 until starts.size) {
            val start = starts[i]
            if (frame <= start) break
            total += minOf(frame, ends[i]) - start
        }
        return total
    }

    /** Converts an absolute track position to content frames: the same position without its filler. */
    fun contentFrames(frame: Long): Long = frame - fillerBefore(frame)

    fun clear() {
        starts.clear()
        ends.clear()
    }

    private class ArrayListLong(private var values: LongArray) {
        var size: Int = 0
            private set

        operator fun get(index: Int): Long = values[index]

        operator fun set(index: Int, value: Long) {
            values[index] = value
        }

        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun clear() {
            size = 0
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 8
    }
}

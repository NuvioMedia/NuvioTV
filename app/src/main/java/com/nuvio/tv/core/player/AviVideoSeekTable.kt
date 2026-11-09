package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import java.util.Arrays

/**
 * The seek points of an AVI file's video stream, the same ones the stock reader gives for it.
 *
 * The stock seek map answers with whichever stream's seek point comes first in the file. Every audio chunk counts as
 * a seek point there, so for a time on or just after a key frame the audio chunk stored before that key frame wins.
 * The video reader then starts from the key frame before that position and numbers the frames from there, so the
 * picture is stamped up to a whole group of pictures early. Only a key frame, or a place before the first one, is a
 * safe start for the video reader.
 */
@UnstableApi
internal class AviVideoSeekTable private constructor(
    private val frameDurationUs: Long,
    private val chunkIndices: IntArray,
    private val positions: LongArray,
) {
    /** Whether the video reader numbers its frames right when reading starts at [position]. */
    fun isSafeStart(position: Long): Boolean = position < positions[0] || Arrays.binarySearch(positions, position) >= 0

    fun seekPoints(timeUs: Long): SeekMap.SeekPoints {
        val frame = (timeUs / frameDurationUs).toInt()
        var at = Arrays.binarySearch(chunkIndices, frame)
        if (at < 0) at = maxOf(0, -at - 2)
        val first = point(at)
        if (chunkIndices[at] == frame || at + 1 >= chunkIndices.size) return SeekMap.SeekPoints(first)
        return SeekMap.SeekPoints(first, point(at + 1))
    }

    private fun point(at: Int) = SeekPoint(chunkIndices[at] * frameDurationUs, positions[at])

    companion object {
        fun create(stream: AviHeader.Stream, keyFrames: AviIndex.KeyFrames): AviVideoSeekTable? {
            if (stream.scale <= 0 || stream.rate <= 0 || stream.length <= 0 || keyFrames.positions.isEmpty()) return null
            val indices = keyFrames.chunkIndices
            val positions = keyFrames.positions
            for (i in 1 until indices.size) {
                if (indices[i] <= indices[i - 1] || positions[i] <= positions[i - 1]) return null
            }
            val durationUs = AviAudioClock.scale(stream.length, stream.scale * 1_000_000L, stream.rate)
            val frameDurationUs = durationUs / stream.length
            if (frameDurationUs <= 0) return null
            return AviVideoSeekTable(frameDurationUs, indices, positions)
        }
    }
}

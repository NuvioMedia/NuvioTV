package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeOrderTimestampsTest {

    private val frameUs = 40_000L

    /** Feeds decode-order inputs a few buffers ahead of the outputs, like a codec does, and returns the shown times. */
    private fun play(
        repair: DecodeOrderTimestamps,
        inputTimesUs: List<Long>,
        outputLabelsUs: List<Long>,
        lookahead: Int = 4
    ): List<Long> {
        val shown = ArrayList<Long>()
        var queued = 0
        for ((index, label) in outputLabelsUs.withIndex()) {
            // A picture never leaves the codec before the input that holds it went in.
            while (queued < inputTimesUs.size && (queued <= index + lookahead || inputTimesUs[queued] <= label)) {
                repair.onInputQueued(inputTimesUs[queued++])
            }
            // A held buffer is offered more than once before it is consumed.
            assertEquals(repair.outputTimeUs(label), repair.outputTimeUs(label))
            shown += repair.outputTimeUs(label)
            repair.onOutputConsumed(label)
        }
        return shown
    }

    /** Display-order labels for an I (B B P)* stream whose inputs are stamped in decode order. */
    private fun ibbpLabels(groups: Int): List<Long> {
        val labels = ArrayList<Long>()
        labels += 0L
        for (g in 0 until groups) {
            val p = 1 + g * 3L
            labels += (p + 1) * frameUs
            labels += (p + 2) * frameUs
            labels += p * frameUs
        }
        return labels
    }

    @Test
    fun monotonicStream_passesThroughAndNeverEngages() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val times = (0 until 200).map { it * frameUs }

        val shown = play(repair, times, times)

        assertEquals(times, shown)
        assertFalse(repair.engaged)
        assertEquals(0L, repair.repaired)
        assertEquals(0L, repair.discarded)
    }

    @Test
    fun decodeOrderStream_engagesAfterTwoBackwardStepsThenHandsOutAscendingTimes() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val labels = ibbpLabels(groups = 40)
        val inputs = labels.indices.map { it * frameUs }

        val shown = play(repair, inputs, labels)

        assertTrue(repair.engaged)
        val engagedFrom = 1 + 3 * DecodeOrderTimestamps.ENGAGE_AFTER_BACKWARD_STEPS
        for (i in engagedFrom until shown.size) {
            assertEquals("frame $i", i * frameUs, shown[i])
        }
        assertEquals(0L, repair.discarded)
    }

    @Test
    fun packedAviChunks_twoPicturesPerChunkAndPlaceholders_stayOnTheGrid() {
        // Chunks: I, [P+B], B, placeholder, [P+B], B, placeholder ... Pictures carry the time of their chunk.
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val labels = ArrayList<Long>()
        labels += 0L
        for (g in 0 until 40) {
            val packed = (1 + g * 3L) * frameUs
            labels += packed
            labels += packed + frameUs
            labels += packed
        }
        val inputs = labels.indices.map { it * frameUs }

        val shown = play(repair, inputs, labels)

        assertTrue(repair.engaged)
        for (i in 8 until shown.size) {
            assertEquals("frame $i", i * frameUs, shown[i])
        }
    }

    @Test
    fun engagedStateSurvivesAFlush() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val labels = ibbpLabels(groups = 10)
        play(repair, labels.indices.map { it * frameUs }, labels)
        assertTrue(repair.engaged)

        repair.flush()
        val base = 1_000 * frameUs
        val afterSeek = ibbpLabels(groups = 10).map { it + base }
        val shown = play(repair, afterSeek.indices.map { base + it * frameUs }, afterSeek)

        assertTrue(repair.engaged)
        for (i in shown.indices) {
            assertEquals("frame $i", base + i * frameUs, shown[i])
        }
    }

    @Test
    fun picturesTheDecoderNeverReturns_doNotShiftTheFramesThatFollow() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val labels = ibbpLabels(groups = 60).toMutableList()
        val inputs = labels.indices.map { it * frameUs }
        // The decoder swallows five B-frames over the run.
        val lostLabels = listOf(41L, 71L, 101L, 131L, 161L).map { it * frameUs }
        lostLabels.forEach { labels.removeAt(labels.indexOf(it)) }

        val shown = play(repair, inputs, labels)

        assertEquals(lostLabels.size.toLong(), repair.discarded)
        val last = shown.size - 1
        assertEquals((last + lostLabels.size) * frameUs, shown[last])
        for (i in 8 until shown.size) {
            assertTrue("frame $i goes forward", shown[i] > shown[i - 1])
        }
    }

    @Test
    fun leadingFramesDroppedAfterASeek_leaveNoLastingOffset() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val warmUp = ibbpLabels(groups = 10)
        play(repair, warmUp.indices.map { it * frameUs }, warmUp)
        repair.flush()

        // Open GOP after the seek: I, two leading B-frames the decoder discards, then P B B P B B ...
        val base = 1_000 * frameUs
        val inputs = (0 until 64).map { base + it * frameUs }
        val labels = ArrayList<Long>()
        labels += base
        for (g in 0 until 20) {
            val p = 3 + g * 3L
            labels += base + (p + 1) * frameUs
            labels += base + (p + 2) * frameUs
            labels += base + p * frameUs
        }

        val shown = play(repair, inputs, labels)

        assertEquals(2L, repair.discarded)
        for (k in 1 until shown.size) {
            assertEquals("frame $k", base + (k + 2) * frameUs, shown[k])
        }
    }

    @Test
    fun alternatingFrameDurations_areNotMistakenForLeftovers() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        // 3:2 pulldown flags: frames last 33 ms and 50 ms in turn.
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until 120) {
            times += t
            t += if (i % 2 == 0) 33_367L else 50_050L
        }
        val labels = ArrayList<Long>()
        labels += times[0]
        var i = 1
        while (i + 2 < times.size) {
            labels += times[i + 1]
            labels += times[i + 2]
            labels += times[i]
            i += 3
        }

        val shown = play(repair, times.subList(0, labels.size), labels)

        assertTrue(repair.engaged)
        assertEquals(0L, repair.discarded)
        for (k in 8 until shown.size) {
            assertEquals("frame $k", times[k], shown[k])
        }
    }

    @Test
    fun twoStrayBackwardStepsFarApart_doNotEngage() {
        val repair = DecodeOrderTimestamps(maxReorderFrames = 1)
        val times = (0 until 400).map { it * frameUs }.toMutableList()
        val labels = times.toMutableList()
        labels[50] = labels[48]
        labels[300] = labels[298]

        play(repair, times, labels)

        assertFalse(repair.engaged)
    }

    @Test
    fun reorderDepth_coversLegacyFormatsOnly() {
        assertEquals(1, DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_VC1, MimeTypes.VIDEO_MATROSKA))
        assertEquals(1, DecodeOrderTimestamps.maxReorderFramesFor("video/vc1", null))
        assertEquals(1, DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_AVI))
        assertEquals(1, DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_MATROSKA))
        assertEquals(1, DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_DIVX, MimeTypes.VIDEO_MATROSKA))
        assertEquals(4, DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_AVI))

        assertNull(DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_MATROSKA))
        assertNull(DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MATROSKA))
        assertNull(DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_MP4))
        assertNull(DecodeOrderTimestamps.maxReorderFramesFor(MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_MATROSKA))
        assertNull(DecodeOrderTimestamps.maxReorderFramesFor(null, MimeTypes.VIDEO_AVI))
    }
}

package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.PriorityQueue

/**
 * Presentation times for video whose container stores decode-order times (AVI, VFW-style Matroska, ASF).
 *
 * The decoder returns pictures in display order, each still carrying the time of the input buffer that held it,
 * so with B-frames the times step backwards at every reference frame and the renderer drops those frames as late.
 * After [ENGAGE_AFTER_BACKWARD_STEPS] such steps close together the queued input times are handed out in ascending
 * order instead. Streams with real presentation times never step backwards and pass through untouched.
 */
internal class DecodeOrderTimestamps(private val maxReorderFrames: Int) {

    var engaged = false
        private set
    var repaired = 0L
        private set
    var discarded = 0L
        private set

    private val pending = PriorityQueue<Long>()
    private val recentStepsUs = LongArray(STEP_WINDOW)
    private var stepCount = 0
    private var lastInputUs = UNSET
    private var lastLabelUs = UNSET
    private var backwardSteps = 0
    private var outputsSinceBackwardStep = 0

    fun onInputQueued(timeUs: Long) {
        if (lastInputUs != UNSET) {
            val step = timeUs - lastInputUs
            if (step in 1..MAX_STEP_US) {
                recentStepsUs[stepCount % STEP_WINDOW] = step
                stepCount++
            }
        }
        lastInputUs = timeUs
        pending.add(timeUs)
        if (pending.size > MAX_PENDING) {
            pending.poll()
            discarded++
        }
    }

    /** Time to present the picture labelled [labelUs] with. Does not advance, a held buffer is offered again. */
    fun outputTimeUs(labelUs: Long): Long {
        if (!engaged) return labelUs
        dropStale(labelUs)
        return pending.peek() ?: labelUs
    }

    fun onOutputConsumed(labelUs: Long) {
        if (engaged) {
            dropStale(labelUs)
            val handedOut = pending.poll()
            if (handedOut != null && handedOut != labelUs) repaired++
        } else {
            if (!pending.remove(labelUs)) pending.poll()
            if (lastLabelUs != UNSET && labelUs + BACKWARD_TOLERANCE_US < lastLabelUs) {
                if (outputsSinceBackwardStep > BACKWARD_STEP_WINDOW) backwardSteps = 0
                backwardSteps++
                outputsSinceBackwardStep = 0
                if (backwardSteps >= ENGAGE_AFTER_BACKWARD_STEPS) engaged = true
            } else {
                outputsSinceBackwardStep++
            }
        }
        lastLabelUs = labelUs
    }

    /** Decoder flushed (seek, drop to keyframe, codec released). A stream found to be in decode order stays so. */
    fun flush() {
        pending.clear()
        stepCount = 0
        lastInputUs = UNSET
        lastLabelUs = UNSET
        backwardSteps = 0
        outputsSinceBackwardStep = 0
    }

    // Every input the decoder returns no picture for (leading B-frames after a seek, a damaged frame) leaves one
    // time too many at the head and would show all later pictures early. A time further behind the picture's own
    // label than reordering can explain is such a leftover.
    private fun dropStale(labelUs: Long) {
        val stepUs = frameStepUs()
        if (stepUs == UNSET) return
        val oldestPlausibleUs = labelUs - maxReorderFrames * stepUs - stepUs / 2
        while (true) {
            val head = pending.peek() ?: return
            if (head >= oldestPlausibleUs) return
            pending.poll()
            discarded++
        }
    }

    // The longest recent gap between inputs, so alternating frame durations (pulldown flags) never look stale.
    private fun frameStepUs(): Long {
        val filled = minOf(stepCount, STEP_WINDOW)
        if (filled == 0) return UNSET
        var longest = 0L
        for (i in 0 until filled) if (recentStepsUs[i] > longest) longest = recentStepsUs[i]
        return longest
    }

    companion object {
        const val ENGAGE_AFTER_BACKWARD_STEPS = 2
        private const val BACKWARD_STEP_WINDOW = 64
        private const val BACKWARD_TOLERANCE_US = 1_000L
        private const val MAX_PENDING = 64
        private const val STEP_WINDOW = 8
        private const val MAX_STEP_US = 200_000L
        private const val UNSET = Long.MIN_VALUE
        private const val REORDER_FRAMES_SINGLE_REFERENCE = 1
        private const val REORDER_FRAMES_H264 = 4

        /** As below; null as well for tracks whose extractor already gives presentation times. */
        fun maxReorderFramesFor(format: Format): Int? =
            if (format.customData === PresentationOrderTimes) {
                null
            } else {
                maxReorderFramesFor(format.sampleMimeType, format.containerMimeType)
            }

        /**
         * How far a picture's time can sit behind its input time, in frames, for tracks that may carry decode-order
         * times; null for everything else, which is never touched.
         */
        fun maxReorderFramesFor(sampleMimeType: String?, containerMimeType: String?): Int? {
            val mime = sampleMimeType?.lowercase() ?: return null
            val inAvi = containerMimeType.equals(MimeTypes.VIDEO_AVI, ignoreCase = true)
            return when {
                mime == MimeTypes.VIDEO_VC1 || mime == "video/vc1" -> REORDER_FRAMES_SINGLE_REFERENCE
                mime == MimeTypes.VIDEO_MP4V || mime == MimeTypes.VIDEO_DIVX ||
                    mime == MimeTypes.VIDEO_MP42 || mime == MimeTypes.VIDEO_MP43 ||
                    mime == MimeTypes.VIDEO_H263 -> REORDER_FRAMES_SINGLE_REFERENCE
                inAvi && mime == MimeTypes.VIDEO_H264 -> REORDER_FRAMES_H264
                inAvi -> REORDER_FRAMES_SINGLE_REFERENCE
                else -> null
            }
        }
    }
}

/** [Format.customData] of a video track whose extractor already gives presentation times (unpacked AVI). */
internal object PresentationOrderTimes

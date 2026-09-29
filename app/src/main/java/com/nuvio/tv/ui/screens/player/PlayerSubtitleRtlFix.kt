@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.text.BidiFormatter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextDirectionHeuristics
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object PlayerSubtitleRtlFix {

    // FILE 2 (looks great) - GREAT AND KEEP IT AS IS
    private val bidiFormatter = BidiFormatter.getInstance(/* rtlContext = */ false)

    // DEBUG TOGGLE: true = every line that goes through the FILE 1 path gets "3" in its middle
    private const val DEBUG_MARK_FILE1_PASSTHROUGH = false
    private const val DEBUG_MARK = "3"

    fun fixCueText(
        cue: Cue,
        boundarySwapped: Boolean = false
    ): Cue {
        val text = cue.text ?: return cue
        val fixed = fixText(text, boundarySwapped) ?: return cue
        return cue.buildUpon().setText(fixed).build()
    }

    fun fixTimedCues(
        cues: List<CuesWithTiming>
    ): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        // FILE 1 (the buggy one) - DO TO IN THE FUTURE
        val boundarySwapped = trackHasSwappedBoundaryPunctuation()

        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)
        for (entry in cues) {
            val entryCues = entry.cues
            var modified: ArrayList<Cue>? = null
            for (i in entryCues.indices) {
                val original = entryCues[i]
                val fixed = fixCueText(original, boundarySwapped)
                if (fixed !== original) {
                    if (modified == null) {
                        modified = ArrayList(entryCues.size)
                        for (j in 0 until i) {
                            modified.add(entryCues[j])
                        }
                    }
                    modified.add(fixed)
                } else {
                    modified?.add(original)
                }
            }
            if (modified != null) {
                anyChanged = true
                out.add(copyTimedCues(entry, modified))
            } else {
                out.add(entry)
            }
        }
        return if (anyChanged) out else cues
    }

    private fun copyTimedCues(entry: CuesWithTiming, cues: List<Cue>): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> 5_000_000L
        }
        return CuesWithTiming(cues, entry.startTimeUs, durationUs)
    }

    // FILE 1 (the buggy one) - DO TO IN THE FUTURE
    private fun trackHasSwappedBoundaryPunctuation(): Boolean {
        return DEBUG_MARK_FILE1_PASSTHROUGH
    }

    private fun fixText(text: CharSequence, boundarySwapped: Boolean): CharSequence? {
        val preserveSpans = text is Spanned
        val lines = text.splitByNewlines()
        var changed = false
        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 8)
        for (i in lines.indices) {
            if (i > 0) out.append('\n')
            var line = lines[i]
            if (line.isEmpty()) continue

            // FILE 1 (the buggy one) - DO TO IN THE FUTURE
            if (boundarySwapped) {
                val unswapped = unswapBoundaryPunctuation(line)
                if (unswapped !== line) changed = true
                line = unswapped
            }

            // FILE 2 (looks great) - GREAT AND KEEP IT AS IS
            if (hasAnyStrongRtlCharacter(line)) {
                val wrapped = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.ANYRTL_LTR, true) ?: line
                if (wrapped !== line) changed = true
                line = wrapped
            }

            out.append(line)
        }
        if (!changed) return null
        return finishBuilder(out)
    }

    // FILE 1 (the buggy one) - DO TO IN THE FUTURE
    private fun unswapBoundaryPunctuation(line: CharSequence): CharSequence {
        if (DEBUG_MARK_FILE1_PASSTHROUGH) return insertDebugMark(line)
        return line
    }

    private fun insertDebugMark(line: CharSequence): CharSequence {
        val mid = line.length / 2
        val result: Appendable =
            if (line is Spanned) SpannableStringBuilder() else StringBuilder(line.length + 1)
        result.append(line.subSequence(0, mid))
        result.append(DEBUG_MARK)
        result.append(line.subSequence(mid, line.length))
        return finishBuilder(result)
    }

    private fun finishBuilder(builder: Appendable): CharSequence = when (builder) {
        is SpannableStringBuilder -> builder
        is StringBuilder -> builder.toString()
        else -> builder.toString()
    }

    private fun CharSequence.splitByNewlines(): List<CharSequence> {
        val result = mutableListOf<CharSequence>()
        var start = 0
        var i = 0
        while (i < this.length) {
            if (this[i] == '\n') {
                result.add(this.subSequence(start, i))
                start = i + 1
            }
            i++
        }
        result.add(this.subSequence(start, this.length))
        return result
    }

    // FILE 2 (looks great) - GREAT AND KEEP IT AS IS
    private fun hasAnyStrongRtlCharacter(text: CharSequence): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint >= 0x0590) {
                if (codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFEFF) {
                    return true
                }
                val d = Character.getDirectionality(codePoint)
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                    d == Character.DIRECTIONALITY_ARABIC_NUMBER
                ) {
                    return true
                }
            }
            i += Character.charCount(codePoint)
        }
        return false
    }
}

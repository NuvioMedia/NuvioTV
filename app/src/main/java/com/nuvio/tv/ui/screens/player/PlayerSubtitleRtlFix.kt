@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.text.BidiFormatter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextDirectionHeuristics
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming

/**
 * RTL subtitle wrapping from androidx/media#2553 ([androidx.media3.ui.SubtitlePainter] on newer
 * Media3). Media3 1.8 paints cues without that wrap, so the same `BidiFormatter.unicodeWrap`
 * (LTR heuristic, spans and line breaks preserved) is applied once when cues are loaded.
 *
 * Non-RTL cues are left untouched.
 */
internal object PlayerSubtitleRtlFix {

    private const val TAG = "PlayerSubtitleRtlFix"

    fun fixCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        if (!containsRtl(text)) return cue
        val fixed = wrapText(text)
        if (fixed.contentEquals(text)) return cue
        return cue.buildUpon().setText(fixed).build()
    }

    /**
     * Applies [fixCueText] to every cue once. Returns the same list instance when nothing changes.
     */
    fun fixTimedCues(cues: List<CuesWithTiming>): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)
        for (entry in cues) {
            val entryCues = entry.cues
            var modified: ArrayList<Cue>? = null
            for (i in entryCues.indices) {
                val original = entryCues[i]
                val fixed = fixCueText(original)
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

    /**
     * True when [input] contains a right-to-left character (Hebrew, Arabic, or an RTL embedding).
     */
    fun containsRtl(input: CharSequence?): Boolean {
        if (input == null) return false
        val length = input.length
        var offset = 0
        while (offset < length) {
            val codePoint = Character.codePointAt(input, offset)
            val dir = Character.getDirectionality(codePoint)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
            ) {
                return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Wraps each line with [BidiFormatter.unicodeWrap] using an LTR direction heuristic.
     * Spans are restored with indices shifted by the inserted bidi marks.
     */
    fun wrapText(input: CharSequence): CharSequence {
        val bidiFormatter = BidiFormatter.getInstance()
        val spannedInput = input as? Spanned
        val spans = spannedInput?.getSpans(0, input.length, Any::class.java)
        val spanStarts = spans?.let { IntArray(it.size) { -1 } }
        val spanEnds = spans?.let { IntArray(it.size) { -1 } }

        val raw = input.toString()
        val useCrlf = raw.contains("\r\n")
        val lines = if (useCrlf) raw.split("\r\n") else raw.split("\n")
        val eolLength = if (useCrlf) 2 else 1

        val wrappedLines = ArrayList<String>(lines.size)
        var spanUpdate = 0
        var lineStart = 0
        for (line in lines) {
            // unicodeWrap adds two characters or none.
            val wrappedLine = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.LTR)
            if (spans != null && spanStarts != null && spanEnds != null && spannedInput != null) {
                val diff = wrappedLine.length - line.length
                if (diff > 0) spanUpdate++
                for (j in spans.indices) {
                    val spanStart = spannedInput.getSpanStart(spans[j])
                    if (spanStarts[j] < 0 &&
                        spanStart >= lineStart &&
                        spanStart < lineStart + line.length
                    ) {
                        spanStarts[j] = spanUpdate
                    }
                    val spanEnd = spannedInput.getSpanEnd(spans[j]) - 1
                    if (spanEnds[j] < 0 &&
                        spanEnd >= lineStart &&
                        spanEnd < lineStart + line.length
                    ) {
                        spanEnds[j] = spanUpdate
                    }
                }
                lineStart += line.length + eolLength
                if (diff > 0) spanUpdate++
            }
            wrappedLines.add(wrappedLine)
        }

        val wrapped = SpannableStringBuilder(wrappedLines.joinToString("\n"))
        if (spans != null && spanStarts != null && spanEnds != null && spannedInput != null) {
            for (i in spans.indices) {
                val start = spannedInput.getSpanStart(spans[i]) + spanStarts[i]
                val end = spannedInput.getSpanEnd(spans[i]) + spanEnds[i]
                val flags = spannedInput.getSpanFlags(spans[i])
                if (start >= 0 && start < wrapped.length && end >= 0 && end <= wrapped.length) {
                    wrapped.setSpan(spans[i], start, end, flags)
                } else {
                    Log.w(TAG, "Span out of bounds: start=$start,end=$end,len=${wrapped.length}")
                }
            }
        }
        return wrapped
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
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.text.BidiFormatter
import android.text.SpannableStringBuilder
import android.text.TextDirectionHeuristics
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
        val bidiFormatter = BidiFormatter.getInstance(false)
        val wrapped = SpannableStringBuilder(input)

        val lines = input.toString().split('\n')

        var lineEnd = input.length
        for (i in lines.size - 1 downTo 0) {
            var line = lines[i]
            val hasCr = line.endsWith('\r')
            if (hasCr) {
                line = line.substring(0, line.length - 1)
            }
            val lineLength = line.length
            val lineStart = lineEnd - lineLength - (if (hasCr) 1 else 0)

            if (hasCr) {
                wrapped.delete(lineStart + lineLength, lineStart + lineLength + 1)
            }

            val wrappedLine = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.FIRSTSTRONG_LTR)
            val diff = wrappedLine.length - lineLength
            if (diff > 0) {
                val prefixLength = if (diff == 1) {
                    if (wrappedLine.startsWith(line)) 0 else 1
                } else {
                    diff / 2
                }
                if (prefixLength + lineLength < wrappedLine.length) {
                    wrapped.insert(
                        lineStart + lineLength,
                        wrappedLine.subSequence(prefixLength + lineLength, wrappedLine.length)
                    )
                }
                if (prefixLength > 0) {
                    wrapped.insert(lineStart, wrappedLine.subSequence(0, prefixLength))
                }
            }
            if (i > 0) {
                lineEnd = lineStart - 1
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

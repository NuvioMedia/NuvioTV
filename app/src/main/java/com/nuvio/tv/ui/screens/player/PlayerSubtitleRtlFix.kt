@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

/**
 * RTL cue text normalization for [androidx.media3.ui.SubtitleView] (LTR layout container).
 *
 * Safe to run once at parse/load time; [fixCueText] is a no-op (identity) for non-RTL cues.
 * Plain [String] cues use [StringBuilder] (cheaper + JVM-testable); spanned cues keep spans.
 */
internal object PlayerSubtitleRtlFix {

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        // Arabic: wrap each physical line with RLE (\u202B) ... PDF (\u202C).
        // This renders boundary punctuation and auto-wrapped lines as RTL in an LTR container.
        if (containsArabic(text)) {
            val fixed = wrapArabicLines(text)
            if (fixed.contentEquals(text)) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        // Hebrew / other RTL: punctuation boundary-swap method (span preserving when needed).
        if (containsRtlChars(text)) {
            val fixed = fixHebrewLines(text, isBuiltInSubtitle) ?: return cue
            if (fixed.contentEquals(text)) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        return cue
    }

    /**
     * Applies [fixCueText] to every cue once. Returns the same list instance when nothing changes.
     * Intended for sidecar parse (background thread), not the 100ms render ticker.
     */
    fun fixTimedCues(
        cues: List<CuesWithTiming>,
        isBuiltInSubtitle: Boolean = false
    ): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)
        for (entry in cues) {
            val entryCues = entry.cues
            var modified: ArrayList<Cue>? = null
            for (i in entryCues.indices) {
                val original = entryCues[i]
                val fixed = fixCueText(original, isBuiltInSubtitle)
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

    private fun wrapArabicLines(text: CharSequence): CharSequence {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 8)
        val lines = text.splitByNewlines()
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            // Clear existing directional markers -> prevents double wrapping (idempotent).
            val line = lines[i].stripDirectionalWrap()
            if (line.isEmpty()) {
                builder.append(line)
                continue
            }
            // Keep the trailing CR (paragraph separator) OUTSIDE of the embedding; otherwise
            // it terminates the RLE run and leaves the PDF orphan.
            val hasCr = line[line.length - 1] == '\r'
            val core = if (hasCr) line.subSequence(0, line.length - 1) else line
            if (core.isEmpty()) {
                builder.append(line)
                continue
            }
            builder.append('\u202B').append(core).append('\u202C')
            if (hasCr) builder.append('\r')
        }
        return finishBuilder(builder)
    }

    private fun fixHebrewLines(text: CharSequence, isBuiltInSubtitle: Boolean): CharSequence? {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length)
        val lines = text.splitByNewlines()
        var changed = false
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i]
            val fixed = if (isBuiltInSubtitle) {
                moveLeadingRtlPunctuationToEndForBuiltIn(line, preserveSpans)
            } else {
                fixRtlPunctuationForLtr(line, preserveSpans)
            }
            if (fixed !== line && fixed.toString() != line.toString()) changed = true
            builder.append(fixed)
        }
        if (!changed) return null
        return finishBuilder(builder)
    }

    private fun finishBuilder(builder: Appendable): CharSequence = when (builder) {
        is SpannableStringBuilder -> builder
        is StringBuilder -> builder.toString()
        else -> builder.toString()
    }

    private fun containsArabic(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint in 0x0600..0x06FF || // Arabic block
                codePoint in 0x0750..0x077F || // Arabic Supplement
                codePoint in 0x0870..0x08FF || // Arabic Extended
                codePoint in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
                codePoint in 0xFE70..0xFEFF || // Arabic Presentation Forms-B
                Character.getDirectionality(codePoint) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun mirrorPunctuation(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        else -> c
    }

    private fun appendMirroredReversed(
        out: Appendable,
        line: CharSequence,
        from: Int,
        toExclusive: Int
    ) {
        if (from >= toExclusive) return

        fun isNumberSeparator(c: Char) = c == ',' || c == ':' || c == '.' || c == '-' || c == '/'

        // 1. Split [from, toExclusive) into chunks: number-runs (digits + embedded , : .) stay together
        val chunks = ArrayList<IntRange>()
        var i = from
        while (i < toExclusive) {
            if (line[i].isDigit()) {
                val start = i
                i++
                while (i < toExclusive) {
                    if (line[i].isDigit()) {
                        i++
                    } else if (
                        isNumberSeparator(line[i]) &&
                        i + 1 < toExclusive &&
                        line[i + 1].isDigit()
                    ) {
                        // separator sandwiched between digits, e.g. 16,300 / 10:50 / 1.23
                        i++ // consume separator, loop will consume following digits
                    } else {
                        break
                    }
                }
                chunks.add(start until i) // whole number (with separators) as one chunk
            } else {
                chunks.add(i until i + 1) // single char chunk
                i++
            }
        }

        // 2. Walk chunks back-to-front, but append each chunk's *contents* in original order
        for (idx in chunks.indices.reversed()) {
            val range = chunks[idx]
            if (range.last - range.first + 1 > 1) {
                // number run -> keep as-is, don't reverse the digits/separators themselves
                out.append(line.subSequence(range.first, range.last + 1))
            } else {
                val c = line[range.first]
                val m = mirrorPunctuation(c)
                if (m != c) out.append(m) else out.append(line.subSequence(range.first, range.first + 1))
            }
        }
    }

    private fun fixRtlPunctuationForLtr(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isRtlPunctuation(line[start], isEnd = false)) start++

        var end = end0
        while (end > start && isRtlPunctuation(line[end - 1], isEnd = true)) end--

        if (start == 0 && end == end0) return line

        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        appendMirroredReversed(out, line, end, end0) // trailing punct/numbers -> front
        out.append(line.subSequence(start, end)) // middle, untouched
        appendMirroredReversed(out, line, 0, start) // leading punct/numbers -> end
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun moveLeadingRtlPunctuationToEndForBuiltIn(
        line: CharSequence,
        preserveSpans: Boolean
    ): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var end = 0
        while (end < end0 && line[end] in MOBILE_RTL_PUNCTUATION) end++
        if (end == 0) return line

        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        out.append(line.subSequence(end, end0))
            .append(line.subSequence(0, end))
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    // Clears existing directional control characters (idempotency + legacy RLM/LRE remnants).
    private fun CharSequence.stripDirectionalWrap(): CharSequence {
        val hasMarker = (0 until length).any { isDirectionalMark(this[it]) }
        if (!hasMarker) return this
        if (this !is Spanned) {
            val sb = StringBuilder(length)
            for (ch in this) {
                if (!isDirectionalMark(ch)) sb.append(ch)
            }
            return sb.toString()
        }
        val sb = SpannableStringBuilder(this)
        var k = 0
        while (k < sb.length) {
            if (isDirectionalMark(sb[k])) sb.delete(k, k + 1) else k++
        }
        return sb
    }

    private fun isDirectionalMark(c: Char): Boolean =
        c == '\u202A' || c == '\u202B' || c == '\u202C' || // LRE / RLE / PDF
            c == '\u200E' || c == '\u200F' // LRM / RLM

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

    private fun isRtlPunctuation(ch: Char, isEnd: Boolean): Boolean {
        if (isEnd && ch.isDigit()) return false
        return ch in RTL_PUNCTUATION || ch.isWhitespace()
    }

    private fun containsRtlChars(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)

            // Direct Unicode range checks for Hebrew and Arabic scripts
            if (codePoint in 0x0590..0x05FF || // Hebrew block (letters, points, punctuation)
                codePoint in 0xFB1D..0xFB4F || // Hebrew Presentation Forms
                codePoint in 0x0600..0x06FF || // Arabic block
                codePoint in 0x0750..0x077F || // Arabic Supplement
                codePoint in 0x0870..0x08FF || // Arabic Extended
                codePoint in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
                codePoint in 0xFE70..0xFEFF // Arabic Presentation Forms-B
            ) {
                return true
            }

            val d = Character.getDirectionality(codePoint)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                d == Character.DIRECTIONALITY_ARABIC_NUMBER
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun hasAnyRtlCharacter(text: CharSequence): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint >= 0x0590) {
                if (codePoint in 0x0590..0x08FF ||
                    codePoint in 0xFB1D..0xFEFF
                ) {
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

    private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"') + ('0'..'9')
    private val MOBILE_RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(')
}

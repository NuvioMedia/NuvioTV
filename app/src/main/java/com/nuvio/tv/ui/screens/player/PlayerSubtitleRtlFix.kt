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

/**
 * Repairs Hebrew/RTL subtitle files whose boundary characters (punctuation, dashes, quotes and
 * leading numbers) were stored in visual order, so they land on the wrong side of the line.
 */
internal object PlayerSubtitleRtlFix {

    private val bidiFormatter = BidiFormatter.getInstance(/* rtlContext = */ false)

    /**
     * Debug marks, inserted in the middle of every processed line:
     * 1 = boundary punctuation was moved, 2 = line was already correct,
     * 3 = punctuation and a leading number were moved (Dark-style files).
     */
    private const val DEBUG_MODE = false
    private const val MARK_PUNCTUATION_MOVED = "1"
    private const val MARK_UNCHANGED = "2"
    private const val MARK_NUMBERS_MOVED = "3"

    private const val CARRIAGE_RETURN = '\r'
    private const val LRM = '\u200E'
    private const val ELLIPSIS = '\u2026'
    private const val SOF_PASUQ = '\u05C3'
    private const val MAQAF = '\u05BE'

    fun fixCueText(
        cue: Cue,
        boundarySwapped: Boolean = false,
        numbersMoved: Boolean = false
    ): Cue {
        val text = cue.text ?: return cue
        val fixed = fixText(text, boundarySwapped, numbersMoved) ?: return cue
        return cue.buildUpon().setText(fixed).build()
    }

    fun fixTimedCues(cues: List<CuesWithTiming>): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        val boundarySwapped = isBoundarySwappedTrack()
        val numbersMoved = boundarySwapped && trackHasMovedNumbers(cues)

        val fixedEntries = cues.map { fixEntry(it, boundarySwapped, numbersMoved) }
        val anyChanged = fixedEntries.indices.any { fixedEntries[it] !== cues[it] }
        return if (anyChanged) fixedEntries else cues
    }

    private fun fixEntry(
        entry: CuesWithTiming,
        boundarySwapped: Boolean,
        numbersMoved: Boolean
    ): CuesWithTiming {
        val original = entry.cues
        var fixedCues: ArrayList<Cue>? = null
        for (index in original.indices) {
            val fixed = fixCueText(original[index], boundarySwapped, numbersMoved)
            if (fixed !== original[index] && fixedCues == null) {
                fixedCues = ArrayList<Cue>(original.size).apply { addAll(original.subList(0, index)) }
            }
            fixedCues?.add(fixed)
        }
        return fixedCues?.let { copyWithCues(entry, it) } ?: entry
    }

    private fun copyWithCues(entry: CuesWithTiming, cues: List<Cue>): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> 5_000_000L
        }
        return CuesWithTiming(cues, entry.startTimeUs, durationUs)
    }

    // TODO: replace with a real per-track corruption check.
    private fun isBoundarySwappedTrack(): Boolean = DEBUG_MODE

    /**
     * Dark-style files are recognised by a line that starts with a number glued by a hyphen to a
     * Hebrew word (".80-זה מתקדם"), which never happens in a correctly ordered file.
     */
    private fun trackHasMovedNumbers(cues: List<CuesWithTiming>): Boolean =
        cues.any { entry ->
            entry.cues.any { cue ->
                cue.text?.splitByNewlines()?.any { startsWithHyphenatedNumber(it) } == true
            }
        }

    // ---------------------------------------------------------------------------------------
    // Per-cue processing
    // ---------------------------------------------------------------------------------------

    private fun fixText(text: CharSequence, boundarySwapped: Boolean, numbersMoved: Boolean): CharSequence? {
        val lines = text.splitByNewlines()
        val out = newBuilder(text, extraCapacity = 8)
        var changed = false

        for (index in lines.indices) {
            if (index > 0) out.append('\n')
            var line = lines[index]
            if (line.isEmpty()) continue

            if (boundarySwapped) {
                val unswapped = unswapLine(line, numbersMoved)
                if (unswapped !== line) changed = true
                line = unswapped
            } else if (DEBUG_MODE) {
                line = insertDebugMark(line, MARK_UNCHANGED)
                changed = true
            }

            if (containsStrongRtl(line)) {
                val wrapped = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.ANYRTL_LTR, true) ?: line
                if (wrapped !== line) changed = true
                line = wrapped
            }
            out.append(line)
        }
        return if (changed) finish(out) else null
    }

    /** Applies the first matching repair rule to a single line. */
    private fun unswapLine(line: CharSequence, numbersMoved: Boolean): CharSequence {
        restoreLeadingNumber(line, numbersMoved)?.let { return it }
        restoreLeadingQuote(line, numbersMoved)?.let { return it }
        if (numbersMoved && startsWithNumber(line)) {
            return withDebugMark(moveLeadingRunToEnd(line), MARK_NUMBERS_MOVED)
        }
        return unswapPunctuation(line)
    }

    // ---------------------------------------------------------------------------------------
    // Rule: number moved to the end behind an LRM ("גלונים ‎70" -> "70 גלונים")
    // ---------------------------------------------------------------------------------------

    private data class TrailingNumber(
        val body: CharSequence,
        val number: CharSequence,
        val hasCarriageReturn: Boolean
    )

    private fun restoreLeadingNumber(line: CharSequence, numbersMoved: Boolean): CharSequence? {
        val trailing = splitTrailingLrmNumber(line) ?: return null
        val body = unswapLine(trailing.body, numbersMoved)
        return buildLike(line) {
            append(trailing.number)
            append(' ')
            append(body)
            if (trailing.hasCarriageReturn) append(CARRIAGE_RETURN)
        }
    }

    /** Splits "<text> LRM<number>" at the end of a line; the LRM marks a number that was moved. */
    private fun splitTrailingLrmNumber(line: CharSequence): TrailingNumber? {
        val end = line.contentEnd()
        var numberStart = end
        while (numberStart > 0 && isNumberCharBefore(line, numberStart, end)) numberStart--
        if (numberStart == end || numberStart == 0 || line[numberStart - 1] != LRM) return null

        var bodyEnd = numberStart - 1
        while (bodyEnd > 0 && (line[bodyEnd - 1].isWhitespace() || isBidiControl(line[bodyEnd - 1]))) bodyEnd--
        if (bodyEnd == 0) return null

        return TrailingNumber(
            body = line.subSequence(0, bodyEnd),
            number = line.subSequence(numberStart, end),
            hasCarriageReturn = line.endsWithCarriageReturn()
        )
    }

    private fun isNumberCharBefore(line: CharSequence, index: Int, end: Int): Boolean {
        val c = line[index - 1]
        if (c.isDigit()) return true
        return isNumberSeparator(c) && index < end && line[index].isDigit() &&
            index >= 2 && line[index - 2].isDigit()
    }

    // ---------------------------------------------------------------------------------------
    // Rule: opening quote moved to the end ('אנחנו נלחם"' -> '"אנחנו נלחם')
    // ---------------------------------------------------------------------------------------

    private fun restoreLeadingQuote(line: CharSequence, numbersMoved: Boolean): CharSequence? {
        val quoteIndex = displacedOpeningQuoteIndex(line)
        if (quoteIndex < 0) return null

        val body = unswapLine(line.subSequence(0, quoteIndex), numbersMoved)
        return buildLike(line) {
            appendSlice(line, quoteIndex, quoteIndex + 1)
            append(body)
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
    }

    /**
     * Index of a quote at the end of the line that was really the opening quote, or -1.
     * That is the case when the other quotes in the line have no opening quote for a closing one
     * ('מתוקה קטנה" מציגים"'), or when there are no other quotes at all.
     */
    private fun displacedOpeningQuoteIndex(line: CharSequence): Int {
        val index = line.contentEnd() - 1
        if (index <= 0 || !isQuote(line[index]) || isInsideWord(line, index)) return -1
        val others = quoteBalance(line, end = index, skip = -1)
        val displaced = others.count == 0 || (others.unmatchedClosers > 0 && others.unclosedOpeners == 0)
        return if (displaced) index else -1
    }

    /**
     * True if the first quote of the line, right after any leading punctuation, was really the
     * closing quote: the other quotes leave an opening quote unclosed ('"בעונה של "פולאאוט'),
     * or there are no other quotes at all.
     */
    private fun leadingQuoteIsDisplacedClosing(line: CharSequence): Boolean {
        val end = line.contentEnd()
        var index = 0
        while (index < end && (isBoundaryPunctuation(line[index]) ||
                line[index].isWhitespace() || isBidiControl(line[index]))
        ) index++
        if (index >= end || !isQuote(line[index]) || isInsideWord(line, index)) return false
        val others = quoteBalance(line, end = end, skip = index)
        return others.count == 0 || (others.unclosedOpeners > 0 && others.unmatchedClosers == 0)
    }

    private data class QuoteBalance(val count: Int, val unmatchedClosers: Int, val unclosedOpeners: Int)

    /** Pairs up the quotes in [0, end), ignoring the one at [skip] and gershayim inside words. */
    private fun quoteBalance(line: CharSequence, end: Int, skip: Int): QuoteBalance {
        var count = 0
        var unmatchedClosers = 0
        var openers = 0
        for (i in 0 until end) {
            if (i == skip || !isQuote(line[i]) || isInsideWord(line, i)) continue
            count++
            when {
                looksLikeOpeningQuote(line, i) -> openers++
                looksLikeClosingQuote(line, i) -> if (openers > 0) openers-- else unmatchedClosers++
            }
        }
        return QuoteBalance(count, unmatchedClosers, openers)
    }

    /** A quote between two letters is a gershayim mark (ק"מ, דו"ח, ב"הנזל), not a real quote. */
    private fun isInsideWord(line: CharSequence, index: Int): Boolean =
        index > 0 && index + 1 < line.length && line[index - 1].isLetter() && line[index + 1].isLetter()

    private fun looksLikeOpeningQuote(line: CharSequence, index: Int): Boolean =
        index + 1 < line.length && line[index + 1].isLetterOrDigit() &&
            (index == 0 || !line[index - 1].isLetterOrDigit())

    private fun looksLikeClosingQuote(line: CharSequence, index: Int): Boolean =
        index > 0 && !line[index - 1].isWhitespace()

    // ---------------------------------------------------------------------------------------
    // Rule: punctuation and dashes moved to the wrong edge
    // ---------------------------------------------------------------------------------------

    private fun unswapPunctuation(line: CharSequence): CharSequence {
        var source = line
        if (hasDashAtBothEnds(line)) {
            // "- text -" is a symmetric decoration and stays as is, unless punctuation was
            // displaced to right after the opening dash ("- ...text -").
            source = swapDashWithFollowingPunctuation(line)
            if (source === line) return withDebugMark(line, MARK_UNCHANGED)
        }
        val moved = moveTrailingDashToFront(source) ?: moveLeadingPunctuationToEnd(source)
        return withDebugMark(moved, MARK_PUNCTUATION_MOVED)
    }

    /** "- ...text -" -> "... -text -". Returns the same instance if the pattern doesn't match. */
    private fun swapDashWithFollowingPunctuation(line: CharSequence): CharSequence {
        val end = line.contentEnd()
        var dashIndex = 0
        while (dashIndex < end && (line[dashIndex].isWhitespace() || isBidiControl(line[dashIndex]))) dashIndex++
        if (dashIndex >= end || !isDash(line[dashIndex])) return line

        var punctuationStart = dashIndex + 1
        while (punctuationStart < end && line[punctuationStart].isWhitespace()) punctuationStart++
        var punctuationEnd = punctuationStart
        while (punctuationEnd < end && isSentencePunctuation(line[punctuationEnd])) punctuationEnd++
        if (punctuationEnd == punctuationStart) return line

        return buildLike(line) {
            appendSlice(line, 0, dashIndex)
            appendSlice(line, punctuationStart, punctuationEnd)
            appendSlice(line, dashIndex + 1, punctuationStart)
            appendSlice(line, dashIndex, dashIndex + 1)
            appendSlice(line, punctuationEnd, line.length)
        }
    }

    /**
     * A dialogue dash displaced to the end, with sentence punctuation at the front:
     * ".ואני אהיה שם-" -> "-ואני אהיה שם."   "!אני -" -> "- אני!"
     * Returns null if the line doesn't have that shape.
     */
    private fun moveTrailingDashToFront(line: CharSequence): CharSequence? {
        val end = line.contentEnd()
        if (end <= 1 || !isDash(line[end - 1]) || isDash(line[0])) return null

        val dashIndex = end - 1
        var bodyEnd = dashIndex
        while (bodyEnd > 0 && line[bodyEnd - 1].isWhitespace()) bodyEnd--

        return buildLike(line) {
            appendSlice(line, dashIndex, dashIndex + 1)
            appendSlice(line, bodyEnd, dashIndex)
            append(moveLeadingPunctuationToEnd(line.subSequence(0, bodyEnd)))
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
    }

    /**
     * ".שלום עולם" -> "שלום עולם."   "- שלום" -> "שלום -"
     * The leading run may contain whitespace and bidi marks (dropped); spaces next to the moved
     * punctuation travel with it. A lone quote counts as punctuation, paired quotes stay put.
     * Returns the same instance if the leading run has no punctuation.
     */
    private fun moveLeadingPunctuationToEnd(line: CharSequence): CharSequence {
        if (line.isEmpty()) return line
        val end = line.contentEnd()
        if (end == 0) return line

        val quoteIsMovable = leadingQuoteIsDisplacedClosing(line)
        fun isMovable(c: Char) = isBoundaryPunctuation(c) || (quoteIsMovable && isQuote(c))

        var runEnd = 0
        var hasPunctuation = false
        while (runEnd < end) {
            val c = line[runEnd]
            if (isMovable(c)) hasPunctuation = true
            else if (!c.isWhitespace() && !isBidiControl(c)) break
            runEnd++
        }
        if (!hasPunctuation || runEnd >= end) return line

        var spaceStart = runEnd
        while (spaceStart > 0 && line[spaceStart - 1].isWhitespace()) spaceStart--

        return buildLike(line) {
            appendSlice(line, runEnd, end)
            appendSlice(line, spaceStart, runEnd)
            for (i in 0 until runEnd) {
                if (isMovable(line[i])) appendSlice(line, i, i + 1)
            }
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rule: punctuation and numbers moved to the front (Dark-style files)
    // ---------------------------------------------------------------------------------------

    /**
     * Moves the leading run of punctuation and numbers to the end, reversing the order of its
     * chunks but keeping each number intact:
     * "?33-כל ה" -> "כל ה-33?"   ".80-זה מתקדם" -> "זה מתקדם-80."
     */
    private fun moveLeadingRunToEnd(line: CharSequence): CharSequence {
        if (line.isEmpty()) return line
        val end = line.contentEnd()
        if (end == 0) return line

        var runEnd = 0
        while (runEnd < end && isRunChar(line[runEnd])) runEnd++
        if (runEnd == 0 || runEnd >= end) return line

        val chunks = splitIntoChunks(line, runEnd)
        if (chunks.isEmpty()) return line

        return buildLike(line) {
            appendSlice(line, runEnd, end)
            for (chunk in chunks.asReversed()) appendChunk(line, chunk)
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
    }

    private fun isRunChar(c: Char): Boolean =
        isBoundaryPunctuation(c) || c.isDigit() || c.isWhitespace() || isBidiControl(c)

    /** Splits the run into single characters, with a whole number (e.g. "1,000") as one chunk. */
    private fun splitIntoChunks(line: CharSequence, runEnd: Int): List<IntRange> {
        val chunks = ArrayList<IntRange>()
        var i = 0
        while (i < runEnd) {
            val c = line[i]
            when {
                isBidiControl(c) -> i++
                c.isDigit() -> {
                    val start = i++
                    while (i < runEnd && (line[i].isDigit() || isSeparatorBetweenDigits(line, i, runEnd))) i++
                    chunks.add(start until i)
                }
                else -> {
                    chunks.add(i until i + 1)
                    i++
                }
            }
        }
        return chunks
    }

    private fun isSeparatorBetweenDigits(line: CharSequence, index: Int, limit: Int): Boolean =
        isNumberSeparator(line[index]) && index + 1 < limit && line[index + 1].isDigit()

    private fun Appendable.appendChunk(line: CharSequence, chunk: IntRange) {
        if (chunk.last > chunk.first) {
            appendSlice(line, chunk.first, chunk.last + 1)
            return
        }
        val c = line[chunk.first]
        val mirrored = mirrorBracket(c)
        if (mirrored != c) append(mirrored) else appendSlice(line, chunk.first, chunk.first + 1)
    }

    private fun mirrorBracket(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        else -> c
    }

    // ---------------------------------------------------------------------------------------
    // Line inspection
    // ---------------------------------------------------------------------------------------

    /** Skips leading non-alphanumerics and returns the end index of the number that follows, or -1. */
    private fun leadingNumberEnd(line: CharSequence): Int {
        var i = 0
        while (i < line.length && !line[i].isLetterOrDigit()) i++
        val start = i
        while (i < line.length && (line[i].isDigit() || isSeparatorBetweenDigitsFrom(line, i, start))) i++
        return if (i > start) i else -1
    }

    private fun isSeparatorBetweenDigitsFrom(line: CharSequence, index: Int, numberStart: Int): Boolean =
        isNumberSeparator(line[index]) && index > numberStart && index + 1 < line.length && line[index + 1].isDigit()

    private fun startsWithNumber(line: CharSequence): Boolean = leadingNumberEnd(line) != -1

    private fun startsWithHyphenatedNumber(line: CharSequence): Boolean {
        val end = leadingNumberEnd(line)
        if (end == -1 || end >= line.length || !isDash(line[end])) return false
        return end + 1 < line.length && Character.UnicodeBlock.of(line[end + 1]) == Character.UnicodeBlock.HEBREW
    }

    private fun hasDashAtBothEnds(line: CharSequence): Boolean {
        var start = 0
        var end = line.length - 1
        while (start <= end && (line[start].isWhitespace() || isBidiControl(line[start]))) start++
        while (end >= start && (line[end].isWhitespace() || isBidiControl(line[end]))) end--
        return start < end && isDash(line[start]) && isDash(line[end])
    }

    private fun containsStrongRtl(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint >= 0x0590) {
                if (codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFEFF) return true
                when (Character.getDirectionality(codePoint)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                    Character.DIRECTIONALITY_ARABIC_NUMBER -> return true
                }
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Character classes
    // ---------------------------------------------------------------------------------------

    private fun isBoundaryPunctuation(c: Char): Boolean = when (c) {
        '.', ',', '?', '!', '-', ':', ';', ELLIPSIS, ')', '(', SOF_PASUQ -> true
        else -> false
    }

    /** Boundary punctuation without dashes and brackets. */
    private fun isSentencePunctuation(c: Char): Boolean = when (c) {
        '.', ',', '?', '!', ':', ';', ELLIPSIS, SOF_PASUQ -> true
        else -> false
    }

    private fun isDash(c: Char): Boolean =
        c == '-' || c == MAQAF || c == '\u2010' || c == '\u2011'

    private fun isQuote(c: Char): Boolean =
        c == '"' || c == '\u05F4' || c == '\u201C' || c == '\u201D'

    /** Separators allowed inside a number: "1,000", "3.14", "12:30", "1/2". */
    private fun isNumberSeparator(c: Char): Boolean =
        c == ',' || c == '.' || c == ':' || c == '/'

    private fun isBidiControl(c: Char): Boolean =
        c == LRM || c == '\u200F' || c == '\u061C' ||
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF'

    // ---------------------------------------------------------------------------------------
    // Text utilities
    // ---------------------------------------------------------------------------------------

    private fun CharSequence.endsWithCarriageReturn(): Boolean = lastOrNull() == CARRIAGE_RETURN

    /** End index of the line content, excluding a trailing '\r' (CRLF files). */
    private fun CharSequence.contentEnd(): Int = if (endsWithCarriageReturn()) length - 1 else length

    private fun CharSequence.splitByNewlines(): List<CharSequence> {
        val lines = ArrayList<CharSequence>()
        var start = 0
        for (i in indices) {
            if (this[i] == '\n') {
                lines.add(subSequence(start, i))
                start = i + 1
            }
        }
        lines.add(subSequence(start, length))
        return lines
    }

    /** Spanned input gets a span-preserving builder, plain text a StringBuilder. */
    private fun newBuilder(source: CharSequence, extraCapacity: Int = 0): Appendable =
        if (source is Spanned) SpannableStringBuilder() else StringBuilder(source.length + extraCapacity)

    private fun finish(builder: Appendable): CharSequence =
        if (builder is SpannableStringBuilder) builder else builder.toString()

    private inline fun buildLike(source: CharSequence, block: Appendable.() -> Unit): CharSequence {
        val builder = newBuilder(source)
        builder.block()
        return finish(builder)
    }

    private fun Appendable.appendSlice(source: CharSequence, start: Int, end: Int) {
        append(source.subSequence(start, end))
    }

    private fun withDebugMark(line: CharSequence, mark: String): CharSequence =
        if (DEBUG_MODE) insertDebugMark(line, mark) else line

    private fun insertDebugMark(line: CharSequence, mark: String): CharSequence {
        val middle = line.length / 2
        return buildLike(line) {
            appendSlice(line, 0, middle)
            append(mark)
            appendSlice(line, middle, line.length)
        }
    }
}

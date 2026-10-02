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

    /** Inserts the marks of the rules that fired into the middle of every processed line. */
    private const val DEBUG_MODE = true

    /** Treats every track as corrupted, skipping the per-track detection (for debugging). */
    private const val FORCE_SWAPPED_TRACK = false

    /** Rules listed here are skipped, to check whether one of them causes a problem. */
    private val disabledRules: Set<Rule> = emptySet()

    private const val MARK_UNCHANGED = "2"

    /** Every repair rule, with the debug mark it leaves on a line (several marks: "5+1"). */
    internal enum class Rule(val mark: String) {
        LEADING_PUNCTUATION("1"),
        LEADING_RUN("3"),
        LRM_NUMBER("4"),
        QUOTE("5"),
        DASH_ELLIPSIS("6"),
        DASH_TO_FRONT("7"),
        LATIN_SEGMENT("8"),
        NUMBERS_REVERSED("9")
    }

    /** The repaired text and the rules that changed it (empty if the line was left as is). */
    internal class LineRepair(val text: CharSequence, val rules: List<Rule> = emptyList()) {
        val marks: String get() = if (rules.isEmpty()) MARK_UNCHANGED else rules.joinToString("+") { it.mark }
    }

    private const val CARRIAGE_RETURN = '\r'
    private const val LRM = '\u200E'
    private const val ELLIPSIS = '\u2026'
    private const val SOF_PASUQ = '\u05C3'
    private const val MAQAF = '\u05BE'
    private const val ARABIC_COMMA = '\u060C'
    private const val ARABIC_SEMICOLON = '\u061B'
    private const val ARABIC_QUESTION_MARK = '\u061F'
    private const val URDU_FULL_STOP = '\u06D4'
    private const val ARABIC_DECIMAL_SEPARATOR = '\u066B'
    private const val ARABIC_THOUSANDS_SEPARATOR = '\u066C'

    fun fixCueText(
        cue: Cue,
        boundarySwapped: Boolean = false,
        numbersMoved: Boolean = false,
        numbersReversed: Boolean = false
    ): Cue {
        val text = cue.text ?: return cue
        val fixed = fixText(text, boundarySwapped, numbersMoved, numbersReversed) ?: return cue
        return cue.buildUpon().setText(fixed).build()
    }

    fun fixTimedCues(cues: List<CuesWithTiming>): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        val boundarySwapped = FORCE_SWAPPED_TRACK || trackHasSwappedBoundaries(cues)
        val numbersMoved = boundarySwapped && trackHasMovedNumbers(cues)
        val numbersReversed = boundarySwapped && trackHasReversedNumbers(cues)

        val fixedEntries = cues.map { fixEntry(it, boundarySwapped, numbersMoved, numbersReversed) }
        val anyChanged = fixedEntries.indices.any { fixedEntries[it] !== cues[it] }
        return if (anyChanged) fixedEntries else cues
    }

    private fun fixEntry(
        entry: CuesWithTiming,
        boundarySwapped: Boolean,
        numbersMoved: Boolean,
        numbersReversed: Boolean
    ): CuesWithTiming {
        val original = entry.cues
        var fixedCues: ArrayList<Cue>? = null
        for (index in original.indices) {
            val fixed = fixCueText(original[index], boundarySwapped, numbersMoved, numbersReversed)
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

    private fun trackHasSwappedBoundaries(cues: List<CuesWithTiming>): Boolean =
        looksLikeSwappedBoundaries(cues.asSequence().flatMap { it.cues.asSequence() }.mapNotNull { it.text })

    /**
     * Recognises files whose boundary characters were stored in visual order by two telltale
     * signs that a correct file practically never has: an RTL line that starts with sentence
     * punctuation (".שלום"), and a line that ends with an LRM followed by a number. They must
     * make up at least 1% of the RTL lines.
     */
    internal fun looksLikeSwappedBoundaries(texts: Sequence<CharSequence>): Boolean {
        var rtlLines = 0
        var telltaleLines = 0
        for (text in texts) {
            for (line in text.splitByNewlines()) {
                if (!containsStrongRtl(line)) continue
                rtlLines++
                if (startsWithSentencePunctuation(line) || endsWithLrmNumber(line)) telltaleLines++
            }
        }
        return telltaleLines >= 5 && telltaleLines * 100 >= rtlLines
    }

    private fun startsWithSentencePunctuation(line: CharSequence): Boolean {
        var start = 0
        while (start < line.length && (line[start].isWhitespace() || isBidiControl(line[start]))) start++
        if (start >= line.length) return false
        val isEllipsis = line[start] == '.' && start + 1 < line.length && line[start + 1] == '.'
        return isSentencePunctuation(line[start]) && !isEllipsis
    }

    private fun endsWithLrmNumber(line: CharSequence): Boolean {
        var end = line.contentEnd()
        while (end > 0 && (line[end - 1].isWhitespace() || line[end - 1] == '\u200F')) end--
        var start = end
        while (start > 0 && (line[start - 1].isDigit() || isNumberSeparator(line[start - 1]))) start--
        return start < end && start > 0 && line[start - 1] == LRM
    }

    private fun trackHasReversedNumbers(cues: List<CuesWithTiming>): Boolean =
        looksLikeReversedNumbers(cues.asSequence().flatMap { it.cues.asSequence() }.mapNotNull { it.text })

    /**
     * Some files store every number digit-reversed ("9102" for 2019). They are recognised by their
     * four-digit numbers: reversed years clearly outnumber years that are already in order.
     */
    internal fun looksLikeReversedNumbers(texts: Sequence<CharSequence>): Boolean {
        var reversedYears = 0
        var forwardYears = 0
        for (text in texts) {
            for (match in FOUR_DIGITS.findAll(text)) {
                val digits = match.value
                when {
                    isYear(digits) -> forwardYears++
                    isYear(digits.reversed()) -> reversedYears++
                }
            }
        }
        return reversedYears >= 3 && reversedYears > 2 * forwardYears
    }

    private val FOUR_DIGITS = Regex("(?<!\\d)\\d{4}(?!\\d)")

    private fun isYear(digits: String): Boolean =
        digits[0] == '1' && (digits[1] == '8' || digits[1] == '9') || digits.startsWith("20")

    // ---------------------------------------------------------------------------------------
    // Per-cue processing
    // ---------------------------------------------------------------------------------------

    private fun fixText(
        text: CharSequence,
        boundarySwapped: Boolean,
        numbersMoved: Boolean,
        numbersReversed: Boolean
    ): CharSequence? {
        val lines = text.splitByNewlines()
        val out = newBuilder(text, extraCapacity = 8)
        var changed = false

        for (index in lines.indices) {
            if (index > 0) out.append('\n')
            var line = lines[index]
            if (line.isEmpty()) continue

            if (boundarySwapped) {
                val repair = repairLine(line, numbersMoved, numbersReversed)
                if (repair.text !== line) changed = true
                line = repair.text
                if (DEBUG_MODE) {
                    line = insertDebugMark(line, repair.marks)
                    changed = true
                }
            } else if (DEBUG_MODE && containsStrongRtl(line)) {
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

    /** Applies the repair rules to a line. Lines without RTL letters are left alone. */
    internal fun repairLine(line: CharSequence, numbersMoved: Boolean, numbersReversed: Boolean = false): LineRepair {
        if (!containsStrongRtl(line)) return LineRepair(line)

        // Bidi marks around the line (RLM at both ends, and a trailing '\r') are set aside while
        // the rules run, so they don't hide the real first and last characters.
        var start = 0
        while (start < line.length && isBidiControl(line[start])) start++
        var end = line.length
        while (end > start && (isBidiControl(line[end - 1]) || line[end - 1] == CARRIAGE_RETURN)) end--
        if (start == end) return LineRepair(line)

        val core = if (start == 0 && end == line.length) line else line.subSequence(start, end)
        var repair = applyRules(core, numbersMoved)
        if (numbersReversed && Rule.NUMBERS_REVERSED !in disabledRules) repair = reverseNumbers(repair)
        if (repair.rules.isEmpty()) return LineRepair(line)
        if (core === line) return repair

        val text = buildLike(line) {
            appendSlice(line, 0, start)
            append(repair.text)
            appendSlice(line, end, line.length)
        }
        return LineRepair(text, repair.rules)
    }

    private fun applyRules(line: CharSequence, numbersMoved: Boolean): LineRepair {
        if (Rule.LRM_NUMBER !in disabledRules) restoreLeadingNumber(line, numbersMoved)?.let { return it }
        if (Rule.QUOTE !in disabledRules) restoreLeadingQuote(line, numbersMoved)?.let { return it }
        if (Rule.LATIN_SEGMENT !in disabledRules) restoreTrailingLatinSegment(line, numbersMoved)?.let { return it }
        if (numbersMoved && Rule.LEADING_RUN !in disabledRules && startsWithNumber(line)) {
            val moved = moveLeadingRunToEnd(line)
            return if (moved === line) LineRepair(line) else LineRepair(moved, listOf(Rule.LEADING_RUN))
        }
        return repairPunctuation(line)
    }

    // ---------------------------------------------------------------------------------------
    // Rule: digit-reversed numbers ("9102" -> "2019"), only for tracks that store them that way
    // ---------------------------------------------------------------------------------------

    private fun reverseNumbers(repair: LineRepair): LineRepair {
        val text = repair.text
        var builder: Appendable? = null
        var copied = 0
        var i = 0
        while (i < text.length) {
            if (!text[i].isDigit()) {
                i++
                continue
            }
            val start = i++
            while (i < text.length && (text[i].isDigit() ||
                    (isNumberSeparator(text[i]) && i + 1 < text.length && text[i + 1].isDigit()))
            ) i++
            val end = spacedNumberEnd(text, start, i) ?: i
            i = end
            if (end - start < 2) continue

            val original = text.subSequence(start, end).toString().replace(" ", "")
            val reversed = original.reversed()
            if (reversed == original) continue

            val target = builder ?: newBuilder(text).also { builder = it }
            target.appendSlice(text, copied, start)
            target.append(reversed)
            copied = end
        }
        val target = builder ?: return repair
        target.appendSlice(text, copied, text.length)
        return LineRepair(finish(target), repair.rules + Rule.NUMBERS_REVERSED)
    }

    /**
     * Reversing a number with a separator leaves a space next to the separator in these files:
     * "213, 12" is "21,312" and "31 :22" is "22:13". Returns the end of such a number that starts
     * at [start] and whose first group ends at [groupEnd], or null if it isn't one.
     */
    private fun spacedNumberEnd(text: CharSequence, start: Int, groupEnd: Int): Int? {
        val groupLength = groupEnd - start
        if (!(start until groupEnd).all { text[it].isDigit() }) return null
        val (separatorLength, maxDigitsAfter, minDigitsAfter) = when {
            groupLength == 3 && text.startsWith(", ", groupEnd) -> Triple(2, 3, 1)
            groupLength == 2 && text.startsWith(" :", groupEnd) -> Triple(2, 2, 2)
            else -> return null
        }
        val digitsStart = groupEnd + separatorLength
        var digitsEnd = digitsStart
        while (digitsEnd < text.length && text[digitsEnd].isDigit()) digitsEnd++
        val digits = digitsEnd - digitsStart
        return if (digits in minDigitsAfter..maxDigitsAfter) digitsEnd else null
    }

    // ---------------------------------------------------------------------------------------
    // Rule: number moved to the end behind an LRM ("גלונים ‎70" -> "70 גלונים")
    // ---------------------------------------------------------------------------------------

    private data class TrailingNumber(
        val body: CharSequence,
        val number: CharSequence,
        val hasCarriageReturn: Boolean
    )

    private fun restoreLeadingNumber(line: CharSequence, numbersMoved: Boolean): LineRepair? {
        val trailing = splitTrailingLrmNumber(line) ?: return null
        val body = applyRules(trailing.body, numbersMoved)
        val text = buildLike(line) {
            append(trailing.number)
            append(' ')
            append(body.text)
            if (trailing.hasCarriageReturn) append(CARRIAGE_RETURN)
        }
        return LineRepair(text, listOf(Rule.LRM_NUMBER) + body.rules)
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

    private fun restoreLeadingQuote(line: CharSequence, numbersMoved: Boolean): LineRepair? {
        val quoteIndex = displacedOpeningQuoteIndex(line)
        if (quoteIndex < 0) return null

        val body = applyRules(line.subSequence(0, quoteIndex), numbersMoved)
        val text = buildLike(line) {
            appendSlice(line, quoteIndex, quoteIndex + 1)
            append(body.text)
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
        return LineRepair(text, listOf(Rule.QUOTE) + body.rules)
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
     * True if the first quote of the line, right after any leading punctuation, was really a
     * closing quote: the other quotes leave an opening quote unclosed ('"בעונה של "פולאאוט'),
     * or there are no other quotes at all. An apostrophe or geresh there ("!'אאוץ") counts when it
     * is the only one outside words.
     */
    private fun leadingQuoteIsDisplacedClosing(line: CharSequence): Boolean {
        val end = line.contentEnd()
        var index = 0
        while (index < end && (isBoundaryPunctuation(line[index]) ||
                line[index].isWhitespace() || isBidiControl(line[index]))
        ) index++
        if (index >= end || isInsideWord(line, index)) return false

        if (isApostrophe(line[index])) return countApostrophesOutsideWords(line, end) == 1
        if (!isQuote(line[index])) return false
        val others = quoteBalance(line, end = end, skip = index)
        return others.count == 0 || (others.unclosedOpeners > 0 && others.unmatchedClosers == 0)
    }

    private fun countApostrophesOutsideWords(line: CharSequence, end: Int): Int =
        (0 until end).count { isApostrophe(line[it]) && !isInsideWord(line, it) }

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
    // Rule: non-RTL text and a dash moved to the front ("Ariel046 - נקרע" -> "נקרע - Ariel046")
    // ---------------------------------------------------------------------------------------

    private class LatinSegment(val segmentEnd: Int, val restStart: Int)

    private fun restoreTrailingLatinSegment(line: CharSequence, numbersMoved: Boolean): LineRepair? {
        val segment = findLeadingLatinSegment(line) ?: return null
        val rest = applyRules(line.subSequence(segment.restStart, line.length), numbersMoved)
        val text = buildLike(line) {
            append(rest.text)
            appendSlice(line, segment.segmentEnd, segment.restStart)
            appendSlice(line, 0, segment.segmentEnd)
        }
        return LineRepair(text, listOf(Rule.LATIN_SEGMENT) + rest.rules)
    }

    /**
     * Finds "<non-RTL text> - <RTL text>": text without RTL letters (a name, a site), a dash
     * surrounded by whitespace, and RTL text after it. A line that starts with a dash is dialogue
     * and never matches.
     */
    private fun findLeadingLatinSegment(line: CharSequence): LatinSegment? {
        var dash = 1
        while (dash < line.length - 1 &&
            !(isDash(line[dash]) && line[dash - 1].isWhitespace() && line[dash + 1].isWhitespace())
        ) dash++
        if (dash >= line.length - 1) return null

        var segmentEnd = dash
        while (segmentEnd > 0 && line[segmentEnd - 1].isWhitespace()) segmentEnd--
        var restStart = dash + 1
        while (restStart < line.length && line[restStart].isWhitespace()) restStart++
        if (segmentEnd == 0 || restStart >= line.length) return null

        val segment = line.subSequence(0, segmentEnd)
        if (isDash(segment[0]) || containsStrongRtl(segment) || segment.none { it.isLetterOrDigit() }) return null
        if (!containsStrongRtl(line.subSequence(restStart, line.length))) return null
        return LatinSegment(segmentEnd, restStart)
    }

    // ---------------------------------------------------------------------------------------
    // Rule: punctuation and dashes moved to the wrong edge
    // ---------------------------------------------------------------------------------------

    private fun repairPunctuation(line: CharSequence): LineRepair {
        var source = line
        val rules = ArrayList<Rule>(2)

        if (hasDashAtBothEnds(line)) {
            // "- text -" is a symmetric decoration and stays as is, unless punctuation was
            // displaced to right after the opening dash ("- ...text -").
            if (Rule.DASH_ELLIPSIS in disabledRules) return LineRepair(line)
            source = swapDashWithFollowingPunctuation(line)
            if (source === line) return LineRepair(line)
            rules.add(Rule.DASH_ELLIPSIS)
        }

        val dashMoved = if (Rule.DASH_TO_FRONT in disabledRules) null else moveTrailingDashToFront(source)
        if (dashMoved != null) return LineRepair(dashMoved, rules + Rule.DASH_TO_FRONT)

        if (Rule.LEADING_PUNCTUATION in disabledRules) return LineRepair(source, rules)
        val moved = moveLeadingPunctuationToEnd(source)
        return if (moved === source) LineRepair(source, rules) else LineRepair(moved, rules + Rule.LEADING_PUNCTUATION)
    }

    /** "- ...text -" -> "... -text -" (also for a lone apostrophe). Returns the same instance if there is no match. */
    private fun swapDashWithFollowingPunctuation(line: CharSequence): CharSequence {
        val end = line.contentEnd()
        var dashIndex = 0
        while (dashIndex < end && (line[dashIndex].isWhitespace() || isBidiControl(line[dashIndex]))) dashIndex++
        if (dashIndex >= end || !isDash(line[dashIndex])) return line

        var punctuationStart = dashIndex + 1
        while (punctuationStart < end && line[punctuationStart].isWhitespace()) punctuationStart++
        val loneApostrophe = countApostrophesOutsideWords(line, end) == 1
        var punctuationEnd = punctuationStart
        while (punctuationEnd < end &&
            (isSentencePunctuation(line[punctuationEnd]) || (loneApostrophe && isApostrophe(line[punctuationEnd])))
        ) punctuationEnd++
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

        val quotesAreMovable = leadingQuoteIsDisplacedClosing(line)
        fun isMovableQuote(c: Char) = quotesAreMovable && (isQuote(c) || isApostrophe(c))
        fun isMovable(c: Char) = isBoundaryPunctuation(c) || isMovableQuote(c)

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
            // Quotes and geresh belong to the word, so they come first; the rest follows them.
            for (i in 0 until runEnd) {
                if (isMovableQuote(line[i])) appendSlice(line, i, i + 1)
            }
            appendSlice(line, spaceStart, runEnd)
            for (i in 0 until runEnd) {
                if (isBoundaryPunctuation(line[i]) && !isMovableQuote(line[i])) appendSlice(line, i, i + 1)
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
        isBoundaryPunctuation(c) || c.isDigit() || c.isWhitespace() || isBidiControl(c) ||
            c == ARABIC_DECIMAL_SEPARATOR || c == ARABIC_THOUSANDS_SEPARATOR

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
        return end + 1 < line.length && isHebrewOrArabicLetter(line[end + 1])
    }

    private fun isHebrewOrArabicLetter(c: Char): Boolean = c.code in 0x0590..0x06FF ||
        c.code in 0x0750..0x077F || c.code in 0x08A0..0x08FF ||
        c.code in 0xFB1D..0xFDFF || c.code in 0xFE70..0xFEFF

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
        '.', ',', '?', '!', '-', ':', ';', ELLIPSIS, ')', '(', SOF_PASUQ,
        ARABIC_COMMA, ARABIC_SEMICOLON, ARABIC_QUESTION_MARK, URDU_FULL_STOP -> true
        else -> false
    }

    /** Boundary punctuation without dashes and brackets. */
    private fun isSentencePunctuation(c: Char): Boolean = when (c) {
        '.', ',', '?', '!', ':', ';', ELLIPSIS, SOF_PASUQ,
        ARABIC_COMMA, ARABIC_SEMICOLON, ARABIC_QUESTION_MARK, URDU_FULL_STOP -> true
        else -> false
    }

    private fun isDash(c: Char): Boolean =
        c == '-' || c == MAQAF || c == '\u2010' || c == '\u2011'

    private fun isQuote(c: Char): Boolean =
        c == '"' || c == '\u05F4' || c == '\u201C' || c == '\u201D'

    /** Apostrophe and geresh: ' ׳ ’ */
    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '\u05F3' || c == '\u2019'

    /** Separators allowed inside a number: "1,000", "3.14", "12:30", "1/2", "12-345-67", "1990-2000". */
    private fun isNumberSeparator(c: Char): Boolean =
        c == ',' || c == '.' || c == ':' || c == '/' || isDash(c) ||
            c == ARABIC_DECIMAL_SEPARATOR || c == ARABIC_THOUSANDS_SEPARATOR

    private fun isBidiControl(c: Char): Boolean =
        c == LRM || c == '\u200F' || c == '\u061C' ||
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF'

    // ---------------------------------------------------------------------------------------
    // Text utilities
    // ---------------------------------------------------------------------------------------

    private fun CharSequence.startsWith(prefix: String, offset: Int): Boolean =
        offset + prefix.length <= length && prefix.indices.all { this[offset + it] == prefix[it] }

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

    private fun insertDebugMark(line: CharSequence, mark: String): CharSequence {
        val middle = line.length / 2
        return buildLike(line) {
            appendSlice(line, 0, middle)
            append(mark)
            appendSlice(line, middle, line.length)
        }
    }
}

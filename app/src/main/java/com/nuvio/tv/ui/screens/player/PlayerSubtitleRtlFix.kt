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
 * Repairs RTL subtitle files whose edge characters (punctuation, dashes, quotes, numbers, Latin
 * names) were stored in visual order and therefore sit on the wrong side of the line.
 */
internal object PlayerSubtitleRtlFix {

    private val bidiFormatter = BidiFormatter.getInstance(/* rtlContext = */ false)

    /**
     * Debug aid for lines that are not displayed correctly.
     *
     * Set to `true`: every RTL line gets a mark in its middle, made of the codes of the rules
     * applied to it (see [Rule]; several rules are joined, e.g. "5+1"), or [MARK_UNCHANGED] if no
     * rule changed the line. Lines without RTL letters get no mark.
     *
     * To report a problem, send the original line (copy-paste) from the .srt file together with the line as
     * shown in the player, mark included. The mark identifies the rule that produced the output.
     *
     * To isolate a rule, list it in [disabledRules]; to process a track that was not detected as
     * corrupted, set [FORCE_SWAPPED_TRACK].
     */
    private const val DEBUG_MODE = false

    /** Skips track detection and treats every track as corrupted. */
    private const val FORCE_SWAPPED_TRACK = false

    /** Rules to skip, for isolating a problem. */
    private val disabledRules: Set<Rule> = emptySet()

    /** Mark of a line that no rule changed. */
    private const val MARK_UNCHANGED = "2"

    /** Repair rules and the debug mark each one leaves (several marks: "5+1"). */
    internal enum class Rule(val mark: String) {
        LEADING_PUNCTUATION("1"), // punctuation stored at the front moves to the end
        LEADING_RUN("3"),         // leading punctuation and numbers move to the end
        LRM_NUMBER("4"),          // number stored at the end behind an LRM moves to the front
        QUOTE("5"),               // opening quote stored at the end moves to the front
        DASH_ELLIPSIS("6"),       // "- ...text -" is reordered before the dash rule
        DASH_TO_FRONT("7"),       // trailing dash moves to the front
        LATIN_SEGMENT("8"),       // name or site stored at the front moves to the end
        NUMBERS_REVERSED("9"),    // digit-reversed numbers are reversed back
        MIXED_RUNS("0"),          // RTL / Latin / RTL runs stored in visual order are reversed
        SPACING("10"),            // space before sentence punctuation is removed
        DOUBLE_DASH("11")         // "- - text" becomes "- text -"
    }

    /** Repaired text and the rules that changed it; no rules means the line is unchanged. */
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
        numbersReversed: Boolean = false,
        dialogueDashesIntact: Boolean = false,
        quoteEdgesIntact: Boolean = false
    ): Cue {
        val text = cue.text ?: return cue
        val fixed = fixText(
            text, boundarySwapped, numbersMoved, numbersReversed, dialogueDashesIntact, quoteEdgesIntact
        ) ?: return cue
        return cue.buildUpon().setText(fixed).build()
    }

    fun fixTimedCues(cues: List<CuesWithTiming>): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        val kind = if (FORCE_SWAPPED_TRACK) TrackKind.SWAPPED else classifyTrack(cues)
        val boundarySwapped = kind != TrackKind.CORRECT
        val dialogueDashesIntact = kind == TrackKind.PARTIALLY_SWAPPED
        val quoteEdgesIntact = dialogueDashesIntact && quoteEdgesBalanced(cues)
        val numbersMoved = boundarySwapped && trackHasMovedNumbers(cues)
        val numbersReversed = boundarySwapped && trackHasReversedNumbers(cues)

        val fixedEntries = cues.map {
            fixEntry(it, boundarySwapped, numbersMoved, numbersReversed, dialogueDashesIntact, quoteEdgesIntact)
        }
        val anyChanged = fixedEntries.indices.any { fixedEntries[it] !== cues[it] }
        return if (anyChanged) fixedEntries else cues
    }

    private fun fixEntry(
        entry: CuesWithTiming,
        boundarySwapped: Boolean,
        numbersMoved: Boolean,
        numbersReversed: Boolean,
        dialogueDashesIntact: Boolean,
        quoteEdgesIntact: Boolean
    ): CuesWithTiming {
        val original = entry.cues
        var fixedCues: ArrayList<Cue>? = null
        for (index in original.indices) {
            val fixed = fixCueText(
                original[index], boundarySwapped, numbersMoved, numbersReversed, dialogueDashesIntact, quoteEdgesIntact
            )
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

    /** A line starting with a number, a hyphen and a word (".45-שלום") does not occur in a correct file. */
    private fun trackHasMovedNumbers(cues: List<CuesWithTiming>): Boolean =
        cues.any { entry ->
            entry.cues.any { cue ->
                cue.text?.splitByNewlines()?.any { startsWithHyphenatedNumber(it) } == true
            }
        }

    /** How much of a track is stored in visual order. */
    internal enum class TrackKind { CORRECT, PARTIALLY_SWAPPED, SWAPPED }

    private fun classifyTrack(cues: List<CuesWithTiming>): TrackKind =
        classifyTrack(cues.asSequence().flatMap { it.cues.asSequence() }.mapNotNull { it.text })

    /**
     * Detects corrupted tracks by two signs a correct file practically never has: an RTL line
     * starting with sentence punctuation, or a line ending with an LRM and a number. Needs at
     * least 5 such lines and 1% of the RTL lines. From 20% on the whole track is corrupted;
     * below that only a few lines are, and the other lines (such as dialogue dashes) are correct.
     */
    internal fun classifyTrack(texts: Sequence<CharSequence>): TrackKind {
        var rtlLines = 0
        var telltaleLines = 0
        for (text in texts) {
            for (line in text.splitByNewlines()) {
                if (!containsStrongRtl(line)) continue
                rtlLines++
                if (startsWithSentencePunctuation(line) || endsWithLrmNumber(line)) telltaleLines++
            }
        }
        return when {
            telltaleLines < 5 || telltaleLines * 100 < rtlLines -> TrackKind.CORRECT
            telltaleLines * 5 >= rtlLines -> TrackKind.SWAPPED
            else -> TrackKind.PARTIALLY_SWAPPED
        }
    }

    private fun quoteEdgesBalanced(cues: List<CuesWithTiming>): Boolean =
        quoteEdgesBalanced(cues.asSequence().flatMap { it.cues.asSequence() }.mapNotNull { it.text })

    /**
     * In a correct track a quotation spanning several lines opens on one line and closes on a
     * later one, so lines with a lone opening quote and lines with a lone closing quote are about
     * equally many. A mirrored track has far more of one kind.
     */
    internal fun quoteEdgesBalanced(texts: Sequence<CharSequence>): Boolean {
        var opening = 0
        var closing = 0
        for (text in texts) {
            for (line in text.splitByNewlines()) {
                if (!containsStrongRtl(line)) continue
                val quotes = line.indices.filter { isQuote(line[it]) && !isInsideWord(line, it) }
                if (quotes.size != 1) continue
                if (looksLikeOpeningQuote(line, quotes[0])) opening++
                else if (looksLikeClosingQuote(line, quotes[0])) closing++
            }
        }
        return Math.abs(opening - closing) <= maxOf(2, (opening + closing) / 5)
    }

    internal fun looksLikeSwappedBoundaries(texts: Sequence<CharSequence>): Boolean =
        classifyTrack(texts) != TrackKind.CORRECT

    private fun startsWithSentencePunctuation(line: CharSequence): Boolean {
        var start = 0
        while (start < line.length && (line[start].isWhitespace() || isBidiControl(line[start]))) start++
        if (start >= line.length) return false
        val isEllipsis = line[start] == '.' && start + 1 < line.length && line[start + 1] == '.'
        return isSentencePunctuation(line[start]) && !isEllipsis
    }

    /** A line that starts with a dialogue dash followed by text, and does not end with a dash. */
    private fun isDialogueLine(line: CharSequence): Boolean {
        val end = line.contentEnd()
        var dash = 0
        while (dash < end && (line[dash].isWhitespace() || isBidiControl(line[dash]))) dash++
        if (dash + 1 >= end || !isDash(line[dash])) return false
        var next = dash + 1
        while (next < end && (line[next].isWhitespace() || isBidiControl(line[next]))) next++
        if (next >= end) return false
        var last = end - 1
        while (last > next && (line[last].isWhitespace() || isBidiControl(line[last]))) last--
        val startsWithText = line[next].isLetterOrDigit() || isQuote(line[next]) || isApostrophe(line[next]) ||
            line[next] == '(' || line[next] == '[' || line[next] == ELLIPSIS ||
            (line[next] == '.' && next + 1 < end && line[next + 1] == '.')
        return startsWithText && !isDash(line[last])
    }

    /**
     * A line with a single quote at one edge: the opening or closing quote of a quotation that
     * spans several lines. Lines with punctuation in front of the quote are not matched.
     */
    private fun isMultiLineQuoteEdge(line: CharSequence): Boolean {
        val end = line.contentEnd()
        var first = 0
        while (first < end && (line[first].isWhitespace() || isBidiControl(line[first]))) first++
        var last = end - 1
        while (last > first && (line[last].isWhitespace() || isBidiControl(line[last]))) last--
        if (first >= last) return false

        val quotes = (first..last).filter { isQuote(line[it]) && !isInsideWord(line, it) }
        if (quotes.size != 1) return false
        return when (quotes[0]) {
            first -> (line[first + 1].isLetterOrDigit() || line[first + 1] == '(') && !isDash(line[last])
            last -> !isSentencePunctuation(line[first]) && !isDash(line[first])
            else -> false
        }
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

    /** Detects digit-reversed numbers ("9102" for 2019): reversed years clearly outnumber ordinary ones. */
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

    // --- Per-cue processing ---

    private fun fixText(
        text: CharSequence,
        boundarySwapped: Boolean,
        numbersMoved: Boolean,
        numbersReversed: Boolean,
        dialogueDashesIntact: Boolean,
        quoteEdgesIntact: Boolean
    ): CharSequence? {
        val lines = text.splitByNewlines()
        val out = newBuilder(text, extraCapacity = 8)
        var changed = false

        for (index in lines.indices) {
            if (index > 0) out.append('\n')
            var line = lines[index]
            if (line.isEmpty()) continue

            if (boundarySwapped) {
                val repair = repairLine(line, numbersMoved, numbersReversed, dialogueDashesIntact, quoteEdgesIntact)
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

    /** Applies the repair rules to one line; lines without RTL letters are returned unchanged. */
    internal fun repairLine(
        line: CharSequence,
        numbersMoved: Boolean,
        numbersReversed: Boolean = false,
        dialogueDashesIntact: Boolean = false,
        quoteEdgesIntact: Boolean = false
    ): LineRepair {
        if (!containsStrongRtl(line)) return LineRepair(line)

        // Bidi marks and a trailing CR are set aside so they don't hide the real line edges.
        var start = 0
        while (start < line.length && isBidiControl(line[start])) start++
        var end = line.length
        while (end > start && (isBidiControl(line[end - 1]) || line[end - 1] == CARRIAGE_RETURN)) end--
        if (start == end) return LineRepair(line)

        val core = if (start == 0 && end == line.length) line else line.subSequence(start, end)
        if (dialogueDashesIntact && isDialogueLine(core)) return LineRepair(line)
        if (quoteEdgesIntact && isMultiLineQuoteEdge(core)) return LineRepair(line)
        var repair = applyRules(core, numbersMoved)
        if (numbersReversed && Rule.NUMBERS_REVERSED !in disabledRules) repair = reverseNumbers(repair)
        if (Rule.SPACING !in disabledRules) repair = removeSpaceBeforePunctuation(repair)
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
        if (Rule.MIXED_RUNS !in disabledRules) reorderMixedRuns(line)?.let { return it }
        if (Rule.DOUBLE_DASH !in disabledRules) restoreDoubleDash(line)?.let { return it }
        if (numbersMoved && Rule.LEADING_RUN !in disabledRules) restoreLeadingRun(line)?.let { return it }
        return repairPunctuation(line)
    }

    // --- Rule: digit-reversed numbers ---

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
     * Reversed numbers with a separator carry a stray space ("213, 12" is "21,312", "31 :22" is
     * "22:13"). Returns the end of such a number starting at [start] whose first group ends at
     * [groupEnd], or null.
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

    // --- Rule: space before sentence punctuation ---

    private fun removeSpaceBeforePunctuation(repair: LineRepair): LineRepair {
        val text = repair.text
        var builder: Appendable? = null
        var copied = 0
        var i = 0
        while (i < text.length) {
            if (!text[i].isWhitespace()) {
                i++
                continue
            }
            val spaceStart = i
            while (i < text.length && text[i].isWhitespace()) i++
            var punctuationEnd = i
            while (punctuationEnd < text.length && isSpacingPunctuation(text[punctuationEnd])) punctuationEnd++

            if (punctuationEnd == i || spaceStart == 0) continue
            if (!canPrecedePunctuation(text[spaceStart - 1])) continue
            if (punctuationEnd < text.length && !canFollowPunctuation(text[punctuationEnd])) continue

            val target = builder ?: newBuilder(text).also { builder = it }
            target.appendSlice(text, copied, spaceStart)
            copied = i
            i = punctuationEnd
        }
        val target = builder ?: return repair
        target.appendSlice(text, copied, text.length)
        return LineRepair(finish(target), repair.rules + Rule.SPACING)
    }

    /** Punctuation that ends a sentence or clause: . , ? ! … (not : or ;). */
    private fun isSpacingPunctuation(c: Char): Boolean = isSentencePunctuation(c) && c != ':' && c != ';'

    private fun canPrecedePunctuation(c: Char): Boolean =
        c.isLetterOrDigit() || isQuote(c) || isApostrophe(c) || c == ')' || c == ']' || isSpacingPunctuation(c)

    private fun canFollowPunctuation(c: Char): Boolean =
        c.isWhitespace() || isQuote(c) || isApostrophe(c) || c == ')' || c == ']' || isDash(c)

    // --- Rule: leading number stored at the end behind an LRM ---

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

    /** Splits "<text> LRM<number>" at the end of a line. */
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

    // --- Rule: opening quote stored at the end ---

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
     * Index of the final quote when it is really the opening one: the other quotes contain a
     * closing quote without an opener, or there are none. Otherwise -1.
     */
    private fun displacedOpeningQuoteIndex(line: CharSequence): Int {
        val index = line.contentEnd() - 1
        if (index <= 0 || !isQuote(line[index]) || isInsideWord(line, index)) return -1
        val others = quoteBalance(line, end = index, skip = -1)
        val displaced = others.count == 0 || (others.unmatchedClosers > 0 && others.unclosedOpeners == 0)
        return if (displaced) index else -1
    }

    /**
     * True if the first quote after any leading punctuation is really the closing one: the other
     * quotes leave an opener unclosed, or there are none. A lone apostrophe or geresh counts the same way.
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

    /** Pairs the quotes in [0, end), ignoring [skip] and in-word marks. */
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

    /** A quote between two letters is an in-word mark (gershayim), not a quote. */
    private fun isInsideWord(line: CharSequence, index: Int): Boolean =
        index > 0 && index + 1 < line.length && line[index - 1].isLetter() && line[index + 1].isLetter()

    private fun looksLikeOpeningQuote(line: CharSequence, index: Int): Boolean =
        index + 1 < line.length && line[index + 1].isLetterOrDigit() &&
            (index == 0 || !line[index - 1].isLetterOrDigit())

    private fun looksLikeClosingQuote(line: CharSequence, index: Int): Boolean =
        index > 0 && !line[index - 1].isWhitespace()

    // --- Rule: name or site stored at the front ---

    private class LatinSegment(val segmentEnd: Int, val restStart: Int)

    private fun restoreTrailingLatinSegment(line: CharSequence, numbersMoved: Boolean): LineRepair? {
        findDecorationFrame(line)?.let { frame ->
            val repaired = restoreTrailingLatinSegment(line.subSequence(frame.first, frame.last + 1), numbersMoved)
                ?: return null
            val text = buildLike(line) {
                appendSlice(line, 0, frame.first)
                append(repaired.text)
                appendSlice(line, frame.last + 1, line.length)
            }
            return LineRepair(text, repaired.rules)
        }
        val segment = findLeadingLatinSegment(line) ?: return null
        val rest = applyRules(line.subSequence(segment.restStart, line.length), numbersMoved)

        var punctuationEnd = 0
        while (punctuationEnd < segment.segmentEnd && isSentencePunctuation(line[punctuationEnd])) punctuationEnd++

        val text = buildLike(line) {
            append(rest.text)
            appendSlice(line, segment.segmentEnd, segment.restStart)
            appendSlice(line, punctuationEnd, segment.segmentEnd)
            appendSlice(line, 0, punctuationEnd)
        }
        return LineRepair(text, listOf(Rule.LATIN_SEGMENT) + rest.rules)
    }

    /** Range of the text inside symbol decoration on both sides ("--==< text >==--"), or null. */
    private fun findDecorationFrame(line: CharSequence): IntRange? {
        fun isDecoration(c: Char) = c.isWhitespace() || isDash(c) || c in "=<>*~_|#"
        val end = line.contentEnd()
        var start = 0
        while (start < end && isDecoration(line[start])) start++
        var last = end - 1
        while (last >= start && isDecoration(line[last])) last--
        if (start >= last + 1) return null
        val hasPrefix = (0 until start).any { !line[it].isWhitespace() }
        val hasSuffix = (last + 1 until end).any { !line[it].isWhitespace() }
        return if (hasPrefix && hasSuffix) start..last else null
    }

    /**
     * Finds non-RTL text followed by whitespace, an optional dash and RTL text. Sentence
     * punctuation before that text moves behind it. Lines starting with a dash (dialogue) and
     * text without a letter (numbers) never match.
     */
    private fun findLeadingLatinSegment(line: CharSequence): LatinSegment? {
        var restStart = 0
        while (restStart < line.length && !isStrongRtl(line[restStart].code)) restStart++
        if (restStart == 0 || restStart >= line.length) return null

        var segmentEnd = restStart
        while (segmentEnd > 0 && (line[segmentEnd - 1].isWhitespace() || isDash(line[segmentEnd - 1]))) segmentEnd--
        if (segmentEnd == 0) return null
        if ((segmentEnd until restStart).none { line[it].isWhitespace() }) return null

        if (isDash(line[0]) || (0 until segmentEnd).none { line[it].isLetter() }) return null
        return LatinSegment(segmentEnd, restStart)
    }

    // --- Rule: RTL and Latin runs stored in visual order ---

    private class Run(val start: Int, val end: Int, val isRtl: Boolean)

    /**
     * Reverses the order of the runs in "RTL Latin RTL" text stored in visual order. Each RTL run
     * also gets its leading punctuation moved to its end. Edge dashes stay in place; the rule
     * applies only when both edges have a dash or neither has one.
     */
    private fun reorderMixedRuns(line: CharSequence): LineRepair? {
        val end = line.contentEnd()
        var coreStart = 0
        while (coreStart < end && (line[coreStart].isWhitespace() || isDash(line[coreStart]))) coreStart++
        var coreEnd = end
        while (coreEnd > coreStart && (line[coreEnd - 1].isWhitespace() || isDash(line[coreEnd - 1]))) coreEnd--
        if (coreStart == coreEnd) return null

        val prefixHasDash = (0 until coreStart).any { isDash(line[it]) }
        val suffixHasDash = (coreEnd until end).any { isDash(line[it]) }
        if (prefixHasDash != suffixHasDash) return null

        val runs = splitIntoRuns(line, coreStart, coreEnd)
        if (runs.size < 3 || !runs.first().isRtl || !runs.last().isRtl) return null

        val text = buildLike(line) {
            appendSlice(line, 0, coreStart)
            for (i in runs.indices.reversed()) {
                val run = runs[i]
                val part = line.subSequence(run.start, run.end)
                append(if (run.isRtl) moveLeadingPunctuationToEnd(part) else part)
                if (i > 0) appendSlice(line, runs[i - 1].end, run.start)
            }
            appendSlice(line, coreEnd, line.length)
        }
        return LineRepair(text, listOf(Rule.MIXED_RUNS))
    }

    /** Groups words into alternating RTL and Latin runs; words without letters join the previous run. */
    private fun splitIntoRuns(line: CharSequence, start: Int, end: Int): List<Run> {
        val runs = ArrayList<Run>()
        var i = start
        while (i < end) {
            if (line[i].isWhitespace()) {
                i++
                continue
            }
            val wordStart = i
            while (i < end && !line[i].isWhitespace()) i++
            val word = line.subSequence(wordStart, i)
            val isRtl = containsStrongRtl(word)
            val isLatin = !isRtl && word.any { it.isLetter() }
            val last = runs.lastOrNull()
            when {
                last != null && (!isRtl && !isLatin || last.isRtl == isRtl) -> runs[runs.size - 1] = Run(last.start, i, last.isRtl)
                !isRtl && !isLatin -> runs.add(Run(wordStart, i, isRtl = false))
                else -> runs.add(Run(wordStart, i, isRtl))
            }
        }
        return runs
    }

    // --- Rule: punctuation and dashes at the wrong edge ---

    private fun repairPunctuation(line: CharSequence): LineRepair {
        var source = line
        val rules = ArrayList<Rule>(2)

        if (hasDashAtBothEnds(line)) {
            // A symmetric "- text -" stays unless punctuation follows the opening dash.
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

    /** Reorders "- ...text -" to "... -text -" (also with a lone apostrophe); otherwise returns [line]. */
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
     * Moves a trailing dash to the front and the leading punctuation to the end
     * (".text-" -> "-text."). Returns null if the line has another shape.
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
     * True if an opening bracket in the leading run is really the closing one, stored mirrored at
     * the front: the rest of the line leaves a bracket unclosed, or has no brackets. A bracket
     * that pairs with one later in the line is a real opening bracket and stays.
     */
    private fun leadingBracketIsDisplacedClosing(line: CharSequence, end: Int): Boolean {
        var runEnd = 0
        while (runEnd < end && (isBoundaryPunctuation(line[runEnd]) ||
                line[runEnd].isWhitespace() || isBidiControl(line[runEnd]))
        ) runEnd++
        if ((0 until runEnd).none { line[it] == '(' }) return false

        var openers = 0
        var unmatchedClosers = 0
        for (i in 0 until end) {
            when {
                line[i] == '(' && i >= runEnd -> openers++
                line[i] == ')' -> if (openers > 0) openers-- else unmatchedClosers++
            }
        }
        val restHasBrackets = (runEnd until end).any { line[it] == '(' || line[it] == ')' }
        return unmatchedClosers == 0 && (openers > 0 || !restHasBrackets)
    }

    /** Reorders '"?text"' to '"text?"': a "?" or "!" after the opening quote belongs inside the pair. */
    private fun swapQuotePair(line: CharSequence, end: Int): CharSequence? {
        if (end < 4 || !isQuote(line[0]) || !isQuote(line[end - 1])) return null
        var marksEnd = 1
        while (marksEnd < end - 1 && (line[marksEnd] == '?' || line[marksEnd] == '!')) marksEnd++
        if (marksEnd == 1 || marksEnd >= end - 1) return null
        if (isInsideWord(line, end - 1) || quoteBalance(line, end = end - 1, skip = 0).count != 0) return null

        return buildLike(line) {
            appendSlice(line, 0, 1)
            appendSlice(line, marksEnd, end - 1)
            appendSlice(line, 1, marksEnd)
            appendSlice(line, end - 1, line.length)
        }
    }

    /**
     * Moves leading punctuation to the end (".text" -> "text."). Whitespace and bidi marks in
     * the run are dropped, spaces next to the moved text stay with it. A lone quote counts as
     * punctuation, paired quotes stay. Returns [line] if the run has no punctuation.
     */
    private fun moveLeadingPunctuationToEnd(line: CharSequence): CharSequence {
        if (line.isEmpty()) return line
        val end = line.contentEnd()
        if (end == 0) return line
        swapQuotePair(line, end)?.let { return it }

        val quotesAreMovable = leadingQuoteIsDisplacedClosing(line)
        val bracketIsMovable = leadingBracketIsDisplacedClosing(line, end)
        fun isMovableQuote(c: Char) = quotesAreMovable && (isQuote(c) || isApostrophe(c))
        fun isMovable(c: Char) =
            if (c == '(') bracketIsMovable else isBoundaryPunctuation(c) || isMovableQuote(c)

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
            // Quotes, geresh and closing brackets attach to the word first, then the other punctuation.
            for (i in 0 until runEnd) {
                if (isMovableQuote(line[i])) appendSlice(line, i, i + 1)
                else if (line[i] == '(' && bracketIsMovable) append(')')
            }
            appendSlice(line, spaceStart, runEnd)
            for (i in 0 until runEnd) {
                val c = line[i]
                if (c != '(' && isMovable(c) && !isMovableQuote(c)) appendSlice(line, i, i + 1)
            }
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
    }

    // --- Rule: displaced closing dash ---

    /** "- - text" -> "- text -": the closing dash was stored at the front, next to the opening one. */
    private fun restoreDoubleDash(line: CharSequence): LineRepair? {
        val end = line.contentEnd()
        if (end < 3 || !isDash(line[0])) return null
        var second = 1
        while (second < end && (line[second].isWhitespace() || isBidiControl(line[second]))) second++
        if (second >= end || !isDash(line[second])) return null
        if (hasDashAtBothEnds(line)) return null

        val text = buildLike(line) {
            appendSlice(line, second, end)
            append(' ')
            appendSlice(line, 0, 1)
            if (line.endsWithCarriageReturn()) append(CARRIAGE_RETURN)
        }
        return LineRepair(text, listOf(Rule.DOUBLE_DASH))
    }

    // --- Rule: leading run of punctuation and numbers ---

    /**
     * Moves a leading number (with its punctuation) to the end of the text. A line that already
     * ends with a number keeps its leading one. Edge dashes of a "- text -" line stay in place.
     */
    private fun restoreLeadingRun(line: CharSequence): LineRepair? {
        val end = line.contentEnd()
        fun isEdge(c: Char) = c.isWhitespace() || isDash(c) || isBidiControl(c)
        var coreStart = 0
        while (coreStart < end && isEdge(line[coreStart])) coreStart++
        var coreEnd = end
        while (coreEnd > coreStart && isEdge(line[coreEnd - 1])) coreEnd--
        if (coreStart == coreEnd || line[coreEnd - 1].isDigit()) return null

        val framed = hasDashAtBothEnds(line)
        val core = if (framed) line.subSequence(coreStart, coreEnd) else line
        if (!startsWithNumber(core) || isPhoneLikeNumber(core)) return null
        val moved = moveLeadingRunToEnd(core)
        if (moved === core) return LineRepair(line)

        val text = if (framed) {
            buildLike(line) {
                appendSlice(line, 0, coreStart)
                append(moved)
                appendSlice(line, coreEnd, line.length)
            }
        } else {
            moved
        }
        return LineRepair(text, listOf(Rule.LEADING_RUN))
    }

    /**
     * Moves the leading run of punctuation and numbers to the end. The chunks are reversed, each
     * number stays intact ("?12-text" -> "text-12?").
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

    /** Splits the run into single characters; a whole number ("1,000") is one chunk. */
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

    // --- Line inspection ---

    /** End index of the number after any leading non-alphanumerics, or -1. */
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

    /** A phone-style number: a dash inside and another hyphen right after it ("1-800-word"). */
    private fun isPhoneLikeNumber(line: CharSequence): Boolean {
        var start = 0
        while (start < line.length && !line[start].isLetterOrDigit()) start++
        val end = leadingNumberEnd(line)
        return (start until end).any { isDash(line[it]) } && end < line.length && isDash(line[end])
    }

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
            if (isStrongRtl(codePoint)) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun isStrongRtl(codePoint: Int): Boolean {
        if (codePoint < 0x0590) return false
        if (codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFEFF) return true
        return when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_ARABIC_NUMBER -> true
            else -> false
        }
    }

    // --- Character classes ---

    private fun isBoundaryPunctuation(c: Char): Boolean = when (c) {
        '.', ',', '?', '!', '-', ':', ';', ELLIPSIS, ')', '(', SOF_PASUQ,
        ARABIC_COMMA, ARABIC_SEMICOLON, ARABIC_QUESTION_MARK, URDU_FULL_STOP -> true
        else -> false
    }

    /** Boundary punctuation excluding dashes and brackets. */
    private fun isSentencePunctuation(c: Char): Boolean = when (c) {
        '.', ',', '?', '!', ':', ';', ELLIPSIS, SOF_PASUQ,
        ARABIC_COMMA, ARABIC_SEMICOLON, ARABIC_QUESTION_MARK, URDU_FULL_STOP -> true
        else -> false
    }

    private fun isDash(c: Char): Boolean =
        c == '-' || c == MAQAF || c == '\u2010' || c == '\u2011'

    private fun isQuote(c: Char): Boolean =
        c == '"' || c == '\u05F4' || c == '\u201C' || c == '\u201D'

    /** Apostrophe and geresh. */
    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '\u05F3' || c == '\u2019'

    /** Characters allowed inside a number ("1,000", "12:30", "1990-2000"). */
    private fun isNumberSeparator(c: Char): Boolean =
        c == ',' || c == '.' || c == ':' || c == '/' || isDash(c) ||
            c == ARABIC_DECIMAL_SEPARATOR || c == ARABIC_THOUSANDS_SEPARATOR

    private fun isBidiControl(c: Char): Boolean =
        c == LRM || c == '\u200F' || c == '\u061C' ||
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\uFEFF'

    // --- Text utilities ---

    private fun CharSequence.startsWith(prefix: String, offset: Int): Boolean =
        offset + prefix.length <= length && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun CharSequence.endsWithCarriageReturn(): Boolean = lastOrNull() == CARRIAGE_RETURN

    /** End index of the line content, excluding a trailing CR. */
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

    /** Span-preserving builder for Spanned input, StringBuilder otherwise. */
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

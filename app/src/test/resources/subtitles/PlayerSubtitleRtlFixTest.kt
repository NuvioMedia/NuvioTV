package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSubtitleRtlFix.Rule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PlayerSubtitleRtlFixTest {

    private class Case(
        val input: String,
        val expected: String,
        val rules: List<Rule>,
        val numbersMoved: Boolean = false,
        val numbersReversed: Boolean = false,
        val dialogueDashesIntact: Boolean = false,
        val quoteEdgesIntact: Boolean = false
    )

    /**
     * Hand-verified lines, one group per rule. They use made-up sentences on purpose: each case
     * shows the corruption pattern, not a line from a specific movie. A change that breaks any of
     * them is a regression.
     */
    private val cases = listOf(
        // LEADING_PUNCTUATION: punctuation stored at the front belongs at the end
        Case(".שלום", "שלום.", listOf(Rule.LEADING_PUNCTUATION)),
        Case("?מה שלומך", "מה שלומך?", listOf(Rule.LEADING_PUNCTUATION)),
        Case("...ואז הלכנו", "ואז הלכנו...", listOf(Rule.LEADING_PUNCTUATION)),
        Case("- שלום", "שלום -", listOf(Rule.LEADING_PUNCTUATION)),
        Case(".שלום\r", "שלום.\r", listOf(Rule.LEADING_PUNCTUATION)),
        Case(".הגענו אל ביה\"ס בזמן", "הגענו אל ביה\"ס בזמן.", listOf(Rule.LEADING_PUNCTUATION)),

        // Dashes: a symmetric "- text -" stays, a displaced dash goes back to the front
        Case("- דנה לוי -", "- דנה לוי -", emptyList()),
        Case("- הפתיחה ב-2008-", "- הפתיחה ב-2008-", emptyList()),
        Case(
            "... -זה לא סוף העולם -",
            "- זה לא סוף העולם...-",
            listOf(Rule.DASH_TO_FRONT)
        ),
        Case(
            "- ...זה לא סוף העולם -",
            "- זה לא סוף העולם...-",
            listOf(Rule.DASH_ELLIPSIS, Rule.DASH_TO_FRONT)
        ),
        Case("- 'השוק הגדול -", "- השוק הגדול'-", listOf(Rule.DASH_ELLIPSIS, Rule.DASH_TO_FRONT)),
        Case("- 'שלום' -", "- 'שלום' -", emptyList()),

        // LRM_NUMBER: a number moved to the end behind an LRM goes back to the front
        Case("מטרים \u200E70", "70 מטרים", listOf(Rule.LRM_NUMBER)),
        Case("מטר. תודה \u200E50", "50 מטר. תודה", listOf(Rule.LRM_NUMBER)),
        Case("מספר הרכב \u200E12-345-67", "12-345-67 מספר הרכב", listOf(Rule.LRM_NUMBER)),
        Case("בשנים \u200E1990-2000", "1990-2000 בשנים", listOf(Rule.LRM_NUMBER)),
        Case("בדקו את הדוחות, חדרים 1 ו-2", "בדקו את הדוחות, חדרים 1 ו-2", emptyList()),
        Case("גובה 1,000. תודה", "גובה 1,000. תודה", emptyList()),

        // QUOTE: a lone quote or an unpaired quote moved to the other edge
        Case("\"שלום\"", "\"שלום\"", emptyList()),
        Case("זה היה יום טוב\"", "\"זה היה יום טוב", listOf(Rule.QUOTE)),
        Case("\"אל הבית דרך הגן", "אל הבית דרך הגן\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case(":חברת שמש\" מציגים\"", "\"חברת שמש\" מציגים:", listOf(Rule.QUOTE, Rule.LEADING_PUNCTUATION)),
        Case(".\"הנסיך הקטן והדרקון\"", "\"הנסיך הקטן והדרקון\".", listOf(Rule.LEADING_PUNCTUATION)),
        Case("\"בעונה הקרובה של \"הסדרה", "בעונה הקרובה של \"הסדרה\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case(",\"ל\"שיר הנושא", "ל\"שיר הנושא\",", listOf(Rule.LEADING_PUNCTUATION)),

        // A quote pair with "?" or "!" after the opening quote: the mark belongs inside the quote
        Case("\"?מה זה\"", "\"מה זה?\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case("\"!תודה רבה\"", "\"תודה רבה!\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case("\"?מה זה\"-", "-\"מה זה?\"", listOf(Rule.DASH_TO_FRONT)),

        // Brackets: a leading "(" is a mirrored closing bracket unless it pairs with one later on
        Case("(מה שלומך? (בספרדית", "מה שלומך? (בספרדית)", listOf(Rule.LEADING_PUNCTUATION)),
        Case("(א ב. (ג ד", "א ב. (ג ד)", listOf(Rule.LEADING_PUNCTUATION)),
        Case("(שלום", "שלום)", listOf(Rule.LEADING_PUNCTUATION)),
        Case(".(כן, אולי משהו (הכלאה בין א לב", "כן, אולי משהו (הכלאה בין א לב).", listOf(Rule.LEADING_PUNCTUATION)),
        Case("(באנגלית, גם: שלום)", "(באנגלית, גם: שלום)", emptyList()),
        Case(".(אוז - טקסט)", "(אוז - טקסט).", listOf(Rule.LEADING_PUNCTUATION)),

        // Apostrophe and geresh stay attached to the word
        Case("!'אאוץ", "אאוץ'!", listOf(Rule.LEADING_PUNCTUATION)),
        Case("'אאוץ", "אאוץ'", listOf(Rule.LEADING_PUNCTUATION)),
        Case("'שלום'", "'שלום'", emptyList()),
        Case("ג'ו אמר שלום", "ג'ו אמר שלום", emptyList()),

        // LATIN_SEGMENT: a name or site stored at the front belongs at the end
        Case("John - תורגם על ידי", "תורגם על ידי - John", listOf(Rule.LATIN_SEGMENT)),
        Case("example.com - בלעדי לאתר", "בלעדי לאתר - example.com", listOf(Rule.LATIN_SEGMENT)),
        Case("[Site](https://example.com) - בלעדי לאתר", "בלעדי לאתר - [Site](https://example.com)", listOf(Rule.LATIN_SEGMENT)),
        Case("Jane תרגום ועריכה על ידי", "תרגום ועריכה על ידי Jane", listOf(Rule.LATIN_SEGMENT)),
        Case("!Fox צוות", "צוות Fox!", listOf(Rule.LATIN_SEGMENT)),
        Case("\u200FJohn - שלום\u200F", "\u200Fשלום - John\u200F", listOf(Rule.LATIN_SEGMENT)),
        Case("--==< John צוות >==--", "--==< צוות John >==--", listOf(Rule.LATIN_SEGMENT)),
        Case("- John צוות -", "- צוות John -", listOf(Rule.LATIN_SEGMENT)),
        Case("--  תרגום וסנכרון  --", "--  תרגום וסנכרון  --", emptyList()),
        Case("--==< צוות >==--", "--==< צוות >==--", emptyList()),
        Case("שלום - Hello", "שלום - Hello", emptyList()),
        Case("Hello - there", "Hello - there", emptyList()),
        Case("iMri & thebarak", "iMri & thebarak", emptyList()),
        Case("12 ביוני", "12 ביוני", emptyList()),
        Case("abc123 :תיקון חלקי", "abc123 :תיקון חלקי", emptyList()),

        // MIXED_RUNS: RTL / Latin / RTL runs stored in visual order are reversed
        Case("- :גאים להציג Alpha צוות -", "- צוות Alpha גאים להציג: -", listOf(Rule.MIXED_RUNS)),
        Case("ג ד Fox א ב", "א ב Fox ג ד", listOf(Rule.MIXED_RUNS)),
        Case("א Fox ב Bar ג", "ג Bar ב Fox א", listOf(Rule.MIXED_RUNS)),
        Case("\u200F- :גאים להציג Alpha צוות -\u200F", "\u200F- צוות Alpha גאים להציג: -\u200F", listOf(Rule.MIXED_RUNS)),
        Case("שלום Hello", "שלום Hello", emptyList()),

        // Lines wrapped in RLM marks: the marks are kept and don't hide the line edges
        Case(
            "\u200F:חברת שמש\" מציגים\"\u200F",
            "\u200F\"חברת שמש\" מציגים:\u200F",
            listOf(Rule.QUOTE, Rule.LEADING_PUNCTUATION)
        ),
        Case(
            "\u200F.\"הנסיך הקטן והדרקון\"\u200F",
            "\u200F\"הנסיך הקטן והדרקון\".\u200F",
            listOf(Rule.LEADING_PUNCTUATION)
        ),
        Case("\u200F.שלום עולם\u200F", "\u200Fשלום עולם.\u200F", listOf(Rule.LEADING_PUNCTUATION)),
        Case("\u200Fמטרים \u200E70\u200F\r", "\u200F70 מטרים\u200F\r", listOf(Rule.LRM_NUMBER)),
        Case("\u200Fשלום עולם\u200F", "\u200Fשלום עולם\u200F", emptyList()),

        // SPACING: no space before , . ? ! ?! ...
        Case("! אמא", "אמא!", listOf(Rule.LEADING_PUNCTUATION, Rule.SPACING)),
        Case("?! מה זה", "מה זה?!", listOf(Rule.LEADING_PUNCTUATION, Rule.SPACING)),
        Case("... מה קרה", "מה קרה...", listOf(Rule.LEADING_PUNCTUATION, Rule.SPACING)),
        Case("שלום , עולם", "שלום, עולם", listOf(Rule.SPACING)),
        Case("שלום עולם !", "שלום עולם!", listOf(Rule.SPACING)),
        Case("שלום, עולם. מה קורה?", "שלום, עולם. מה קורה?", emptyList()),
        Case("- שלום", "שלום -", listOf(Rule.LEADING_PUNCTUATION)),
        Case("גובה 3 .5 מטר", "גובה 3 .5 מטר", emptyList()),

        // LEADING_RUN: punctuation and numbers moved to the front, numbers stay intact
        Case("?12-בית ספר", "בית ספר-12?", listOf(Rule.LEADING_RUN), numbersMoved = true),
        Case(".45-זה טוב", "זה טוב-45.", listOf(Rule.LEADING_RUN), numbersMoved = true),
        Case("?1990-2000 בערך", "בערך 1990-2000?", listOf(Rule.LEADING_RUN), numbersMoved = true),

        // DOUBLE_DASH: the closing dash was stored next to the opening one
        Case("- \u200F- 21 במאי, 2019", "- 21 במאי, 2019 -", listOf(Rule.DOUBLE_DASH)),
        Case("-- שלום עולם --", "-- שלום עולם --", emptyList()),

        // LEADING_RUN with edge dashes, and a leading number that is not moved
        Case("- 2020 ,יום ראשון -", "- יום ראשון, 2020 -", listOf(Rule.LEADING_RUN), numbersMoved = true),
        Case("- 10:30 בשעה -", "- בשעה 10:30 -", listOf(Rule.LEADING_RUN), numbersMoved = true),
        Case("- 21 במאי, 2019 -", "- 21 במאי, 2019 -", emptyList(), numbersMoved = true),
        Case("- 1-800-שירות -", "- 1-800-שירות -", emptyList(), numbersMoved = true),

        // NUMBERS_REVERSED: digit-reversed numbers, only in tracks detected as such
        Case("הבית נבנה ב-0691", "הבית נבנה ב-1960", listOf(Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case("הבית נבנה ב-0691", "הבית נבנה ב-0691", emptyList()),
        Case("- 12 במאי, 9102 -", "- 21 במאי, 2019 -", listOf(Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case(".התחילו כבר ב-3591", "התחילו כבר ב-1953.", listOf(Rule.LEADING_PUNCTUATION, Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case(".ביצענו כבר 271 בדיקות", "ביצענו כבר 172 בדיקות.", listOf(Rule.LEADING_PUNCTUATION, Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case("יש 5 ו-33 ו-121", "יש 5 ו-33 ו-121", emptyList(), numbersReversed = true),
        Case(".במחוז רשומים 213, 12 רכבים", "במחוז רשומים 21,312 רכבים.", listOf(Rule.LEADING_PUNCTUATION, Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case("- בשעה 31 :22 -", "- בשעה 22:13 -", listOf(Rule.NUMBERS_REVERSED), numbersReversed = true),

        // Arabic
        Case(".مرحبا بالعالم", "مرحبا بالعالم.", listOf(Rule.LEADING_PUNCTUATION)),
        Case("؟كيف حالك", "كيف حالك؟", listOf(Rule.LEADING_PUNCTUATION)),
        Case("،مرحبا بكم", "مرحبا بكم،", listOf(Rule.LEADING_PUNCTUATION)),
        Case("- مرحبا -", "- مرحبا -", emptyList()),
        Case("\"مرحبا بك", "مرحبا بك\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case("مرحبا \u200E٧٠", "٧٠ مرحبا", listOf(Rule.LRM_NUMBER)),
        Case(".٣٫٥-كان", "كان-٣٫٥.", listOf(Rule.LEADING_RUN), numbersMoved = true),

        // Partially corrupted tracks: correct dialogue dashes stay, corrupted lines are still repaired
        Case("- אל תקנה לה כלום.", "- אל תקנה לה כלום.", emptyList(), dialogueDashesIntact = true),
        Case("- ...ואז הלכנו", "- ...ואז הלכנו", emptyList(), dialogueDashesIntact = true),
        Case("\u200F- אל תקנה לה כלום.\u200F", "\u200F- אל תקנה לה כלום.\u200F", emptyList(), dialogueDashesIntact = true),
        Case("\u200F  - אל תקנה לה כלום.\u200F", "\u200F  - אל תקנה לה כלום.\u200F", emptyList(), dialogueDashesIntact = true),
        Case(".תניח לזה -", "- תניח לזה.", listOf(Rule.DASH_TO_FRONT), dialogueDashesIntact = true),
        Case(".שלום", "שלום.", listOf(Rule.LEADING_PUNCTUATION), dialogueDashesIntact = true),
        Case("\"אה, לא, אנחנו מאוהבים.", "\"אה, לא, אנחנו מאוהבים.", emptyList(), dialogueDashesIntact = true, quoteEdgesIntact = true),
        Case("היא בסדר גמור.\"", "היא בסדר גמור.\"", emptyList(), dialogueDashesIntact = true, quoteEdgesIntact = true),
        Case("\u200F\"אה, לא, אנחנו מאוהבים.\u200F", "\u200F\"אה, לא, אנחנו מאוהבים.\u200F", emptyList(), dialogueDashesIntact = true, quoteEdgesIntact = true),
        Case("היא בסדר גמור.\"", "\"היא בסדר גמור.", listOf(Rule.QUOTE), dialogueDashesIntact = true),
        Case("\".הברורה של הלילה", "הברורה של הלילה\".", listOf(Rule.LEADING_PUNCTUATION), dialogueDashesIntact = true),
        Case("\"!כן, זה עובד\"", "\"כן, זה עובד!\"", listOf(Rule.LEADING_PUNCTUATION), dialogueDashesIntact = true),
        Case("- שלום", "שלום -", listOf(Rule.LEADING_PUNCTUATION)),

        // Lines without RTL letters are never touched
        Case(".Hello there", ".Hello there", emptyList()),
        Case("- Hello there", "- Hello there", emptyList()),
        Case("...and then", "...and then", emptyList())
    )

    @Test
    fun handVerifiedCases() {
        val failures = cases.mapNotNull { case ->
            val repair = PlayerSubtitleRtlFix.repairLine(
                case.input, case.numbersMoved, case.numbersReversed, case.dialogueDashesIntact, case.quoteEdgesIntact
            )
            val text = repair.text.toString()
            if (text == case.expected && repair.rules == case.rules) null
            else "input   : ${case.input}\n  expected: ${case.expected}  ${case.rules}\n  actual  : $text  ${repair.rules}"
        }
        if (failures.isNotEmpty()) fail("${failures.size} case(s) changed:\n" + failures.joinToString("\n"))
    }

    @Test
    fun detectsSwappedBoundaries() {
        val swapped = (1..20).map { ".שלום עולם $it" } + (1..30).map { "שלום עולם $it" }
        val correct = listOf("- مَن أنت؟", "- (لوك بانكول)", "...היא לא יותר מאשליה", "- שלום.", "שלום עולם.") +
            (1..100).map { "שלום עולם $it." }
        val lrmNumbers = (1..6).map { "גלונים \u200E7$it" } + (1..100).map { "שלום עולם $it." }
        assertEquals(true, PlayerSubtitleRtlFix.looksLikeSwappedBoundaries(swapped.asSequence()))
        assertEquals(false, PlayerSubtitleRtlFix.looksLikeSwappedBoundaries(correct.asSequence()))
        assertEquals(true, PlayerSubtitleRtlFix.looksLikeSwappedBoundaries(lrmNumbers.asSequence()))
    }

    @Test
    fun detectsBalancedQuoteEdges() {
        val opening = (1..10).map { "\"שלום עולם $it" }
        val closing = (1..10).map { "שלום עולם $it\"" }
        assertEquals(true, PlayerSubtitleRtlFix.quoteEdgesBalanced((opening + closing).asSequence()))
        assertEquals(true, PlayerSubtitleRtlFix.quoteEdgesBalanced(emptySequence()))
        assertEquals(false, PlayerSubtitleRtlFix.quoteEdgesBalanced((closing + opening.take(1)).asSequence()))
    }

    @Test
    fun classifiesTracks() {
        val corrupted = { n: Int -> (1..n).map { ".שלום עולם $it" } }
        val correct = { n: Int -> (1..n).map { "- שלום עולם $it." } }
        val kind = { lines: List<String> -> PlayerSubtitleRtlFix.classifyTrack(lines.asSequence()) }
        assertEquals(PlayerSubtitleRtlFix.TrackKind.SWAPPED, kind(corrupted(80) + correct(20)))
        assertEquals(PlayerSubtitleRtlFix.TrackKind.PARTIALLY_SWAPPED, kind(corrupted(10) + correct(300)))
        assertEquals(PlayerSubtitleRtlFix.TrackKind.CORRECT, kind(corrupted(2) + correct(300)))
        assertEquals(PlayerSubtitleRtlFix.TrackKind.CORRECT, kind(correct(300)))
    }

    @Test
    fun detectsReversedNumbersByYears() {
        val reversed = sequenceOf("שנת 9102", "ב-3591", "ב-0691", "ב-0202")
        val forward = sequenceOf("שנת 2019", "ב-1953", "ב-1960", "ב-2020")
        val few = sequenceOf("שנת 9102", "ב-3591")
        assertEquals(true, PlayerSubtitleRtlFix.looksLikeReversedNumbers(reversed))
        assertEquals(false, PlayerSubtitleRtlFix.looksLikeReversedNumbers(forward))
        assertEquals(false, PlayerSubtitleRtlFix.looksLikeReversedNumbers(few))
    }

    /**
     * Runs every line of every .srt in src/test/resources/subtitles and compares the result with
     * snapshot.txt. Any difference is listed, so you see exactly which lines a code change touched.
     * Accept intended changes by deleting snapshot.txt (or running with -DupdateSnapshot=true).
     */
    @Test
    fun snapshotOfRealSubtitleFiles() {
        val dir = File("src/test/resources/subtitles")
        val snapshotFile = File(dir, "snapshot.txt")
        val actual = buildSnapshot(dir)

        if (!snapshotFile.exists() || System.getProperty("updateSnapshot") == "true") {
            snapshotFile.writeText(actual.joinToString("\n"), Charsets.UTF_8)
            return
        }
        val expected = snapshotFile.readText(Charsets.UTF_8).split("\n")
        val changed = (expected.toSet() - actual.toSet()) + (actual.toSet() - expected.toSet())
        if (changed.isNotEmpty()) {
            fail("${changed.size} snapshot entries differ (input | numbersMoved | numbersReversed | swapped | output | marks):\n" +
                changed.take(40).joinToString("\n"))
        }
        assertEquals(expected.size, actual.size)
    }

    private fun buildSnapshot(dir: File): List<String> {
        val entries = sortedSetOf<String>()
        val tags = Regex("</?[a-zA-Z][^>]*>")
        dir.listFiles { file -> file.extension == "srt" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val blocks = file.readText(Charsets.UTF_8).replace("\r\n", "\n").split("\n\n")
            val lines = blocks.flatMap { block -> block.split("\n").drop(2) }
                .map { it.replace(tags, "") }
                .filter { it.isNotEmpty() }
            val kind = PlayerSubtitleRtlFix.classifyTrack(lines.asSequence())
            val swapped = kind != PlayerSubtitleRtlFix.TrackKind.CORRECT
            val intact = kind == PlayerSubtitleRtlFix.TrackKind.PARTIALLY_SWAPPED
            val quotesIntact = intact && PlayerSubtitleRtlFix.quoteEdgesBalanced(lines.asSequence())
            val numbersReversed = swapped && PlayerSubtitleRtlFix.looksLikeReversedNumbers(lines.asSequence())
            for (line in lines) {
                for (numbersMoved in listOf(false, true)) {
                    val repair = if (swapped) {
                        PlayerSubtitleRtlFix.repairLine(line, numbersMoved, numbersReversed, intact, quotesIntact)
                    } else {
                        PlayerSubtitleRtlFix.LineRepair(line)
                    }
                    entries.add("$line | $numbersMoved | $numbersReversed | $kind | ${repair.text} | ${repair.marks}")
                }
            }
        }
        return entries.toList()
    }
}

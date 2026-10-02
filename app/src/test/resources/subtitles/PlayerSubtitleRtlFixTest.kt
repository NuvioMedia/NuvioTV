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
        val numbersReversed: Boolean = false
    )

    /** Hand-verified lines. A fix that breaks any of them is a regression. */
    private val cases = listOf(
        // Leading punctuation -> end
        Case(".שלום", "שלום.", listOf(Rule.LEADING_PUNCTUATION)),
        Case("...ואז", "ואז...", listOf(Rule.LEADING_PUNCTUATION)),
        Case("- שלום", "שלום -", listOf(Rule.LEADING_PUNCTUATION)),
        Case(".שלום\r", "שלום.\r", listOf(Rule.LEADING_PUNCTUATION)),
        Case(".קראתי את הדו\"חות ממפגשי הטיפול שלך", "קראתי את הדו\"חות ממפגשי הטיפול שלך.", listOf(Rule.LEADING_PUNCTUATION)),

        // Dashes
        Case("- אלברט איינשטיין -", "- אלברט איינשטיין -", emptyList()),
        Case(
            "... -היא לא יותר מאשליה עקבית ועיקשת -",
            "- היא לא יותר מאשליה עקבית ועיקשת...-",
            listOf(Rule.DASH_TO_FRONT)
        ),
        Case(
            "- ...היא לא יותר מאשליה עקבית ועיקשת -",
            "- היא לא יותר מאשליה עקבית ועיקשת...-",
            listOf(Rule.DASH_ELLIPSIS, Rule.DASH_TO_FRONT)
        ),

        // Number moved to the end behind an LRM
        Case("גלונים \u200E70", "70 גלונים", listOf(Rule.LRM_NUMBER)),
        Case("גלון. עבור \u200E50", "50 גלון. עבור", listOf(Rule.LRM_NUMBER)),
        Case("בדקו דלק, פורטיס 1 ו-2", "בדקו דלק, פורטיס 1 ו-2", emptyList()),
        Case("גובה 1,000. עבור", "גובה 1,000. עבור", emptyList()),

        // Quotes
        Case("\"דנקרק\"", "\"דנקרק\"", emptyList()),
        Case("אבל היה ניצחון\"", "\"אבל היה ניצחון", listOf(Rule.QUOTE)),
        Case("\"אל העתיד דרך הלילה", "אל העתיד דרך הלילה\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case(":מתוקה קטנה\" מציגים\"", "\"מתוקה קטנה\" מציגים:", listOf(Rule.QUOTE, Rule.LEADING_PUNCTUATION)),
        Case(".\"גרוגנק הברברי וחורבת אבן האודם\"", "\"גרוגנק הברברי וחורבת אבן האודם\".", listOf(Rule.LEADING_PUNCTUATION)),
        Case("\"בעונה הקרובה של \"פולאאוט", "בעונה הקרובה של \"פולאאוט\"", listOf(Rule.LEADING_PUNCTUATION)),

        // Lines without RTL letters are never touched
        Case(".Hello there", ".Hello there", emptyList()),
        Case("- Hello there", "- Hello there", emptyList()),
        Case("...and then", "...and then", emptyList()),

        // Arabic
        Case(".مرحبا بالعالم", "مرحبا بالعالم.", listOf(Rule.LEADING_PUNCTUATION)),
        Case("؟كيف حالك", "كيف حالك؟", listOf(Rule.LEADING_PUNCTUATION)),
        Case("،مرحبا بكم", "مرحبا بكم،", listOf(Rule.LEADING_PUNCTUATION)),
        Case("- مرحبا -", "- مرحبا -", emptyList()),
        Case("\"مرحبا بك", "مرحبا بك\"", listOf(Rule.LEADING_PUNCTUATION)),
        Case("مرحبا \u200E٧٠", "٧٠ مرحبا", listOf(Rule.LRM_NUMBER)),
        Case(".٣٫٥-كان", "كان-٣٫٥.", listOf(Rule.LEADING_RUN), numbersMoved = true),

        // Apostrophe / geresh and quotes stay attached to the word
        Case("!'אאוץ", "אאוץ'!", listOf(Rule.LEADING_PUNCTUATION)),
        Case("'אאוץ", "אאוץ'", listOf(Rule.LEADING_PUNCTUATION)),
        Case("'שלום'", "'שלום'", emptyList()),
        Case("ג'ורג' אמר שלום", "ג'ורג' אמר שלום", emptyList()),
        Case(",\"ל\"הרוזן ממונטה כריסטו", "ל\"הרוזן ממונטה כריסטו\",", listOf(Rule.LEADING_PUNCTUATION)),

        Case("- 'קניון וולבריג -", "- קניון וולבריג'-", listOf(Rule.DASH_ELLIPSIS, Rule.DASH_TO_FRONT)),
        Case("- 'שלום' -", "- 'שלום' -", emptyList()),
        Case("- הפתיחה ב-2008-", "- הפתיחה ב-2008-", emptyList()),

        // Lines wrapped in RLM marks
        Case(
            "\u200F:מתוקה קטנה\" מציגים\"\u200F",
            "\u200F\"מתוקה קטנה\" מציגים:\u200F",
            listOf(Rule.QUOTE, Rule.LEADING_PUNCTUATION)
        ),
        Case(
            "\u200F.\"גרוגנק הברברי וחורבת אבן האודם\"\u200F",
            "\u200F\"גרוגנק הברברי וחורבת אבן האודם\".\u200F",
            listOf(Rule.LEADING_PUNCTUATION)
        ),
        Case("\u200F.מתוקה קטנה שלי\u200F", "\u200Fמתוקה קטנה שלי.\u200F", listOf(Rule.LEADING_PUNCTUATION)),
        Case("\u200Fגלונים \u200E70\u200F\r", "\u200F70 גלונים\u200F\r", listOf(Rule.LRM_NUMBER)),
        Case("\u200Fשלום עולם\u200F", "\u200Fשלום עולם\u200F", emptyList()),

        // Non-RTL text and a dash at the front move to the end
        Case("Ariel046 - נקרע, תוקן וסונכרן לגירסא זו ע\"י", "נקרע, תוקן וסונכרן לגירסא זו ע\"י - Ariel046", listOf(Rule.LATIN_SEGMENT)),
        Case("Www.Torec.Net - בלעדית לאתר", "בלעדית לאתר - Www.Torec.Net", listOf(Rule.LATIN_SEGMENT)),
        Case("[Www.Torec.Net](https://Www.Torec.Net) - בלעדית לאתר", "בלעדית לאתר - [Www.Torec.Net](https://Www.Torec.Net)", listOf(Rule.LATIN_SEGMENT)),
        Case("\u200FAriel046 - נקרע\u200F", "\u200Fנקרע - Ariel046\u200F", listOf(Rule.LATIN_SEGMENT)),
        Case("שלום - Hello", "שלום - Hello", emptyList()),
        Case("Hello - there", "Hello - there", emptyList()),

        // Digit-reversed numbers (only when the track is detected as such)
        Case("הבנייה אושרה לראשונה ב-0691", "הבנייה אושרה לראשונה ב-1960", listOf(Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case("הבנייה אושרה לראשונה ב-0691", "הבנייה אושרה לראשונה ב-0691", emptyList()),
        Case("- 12 ביוני, 9102 -", "- 21 ביוני, 2019 -", listOf(Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case(".החלו כבר ב-3591", "החלו כבר ב-1953.", listOf(Rule.LEADING_PUNCTUATION, Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case(".ביצענו כבר 271 תשאולים", "ביצענו כבר 172 תשאולים.", listOf(Rule.LEADING_PUNCTUATION, Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case("יש 5 ו-33 ו-121", "יש 5 ו-33 ו-121", emptyList(), numbersReversed = true),
        Case(".במחוז וינדן לבדו רשומים 213, 12 רכבים", "במחוז וינדן לבדו רשומים 21,312 רכבים.", listOf(Rule.LEADING_PUNCTUATION, Rule.NUMBERS_REVERSED), numbersReversed = true),
        Case("- בשעה 31 :22 -", "- בשעה 22:13 -", listOf(Rule.NUMBERS_REVERSED), numbersReversed = true),

        // Dashes inside numbers (plates, year ranges)
        Case("מספר הרכב \u200E12-345-67", "12-345-67 מספר הרכב", listOf(Rule.LRM_NUMBER)),
        Case("בשנים \u200E1990-2000", "1990-2000 בשנים", listOf(Rule.LRM_NUMBER)),
        Case("?1990-2000 כל", "כל 1990-2000?", listOf(Rule.LEADING_RUN), numbersMoved = true),

        // Dark-style: punctuation and numbers moved to the front
        Case("?33-כל ה", "כל ה-33?", listOf(Rule.LEADING_RUN), numbersMoved = true),
        Case(".80-זה מתקדם", "זה מתקדם-80.", listOf(Rule.LEADING_RUN), numbersMoved = true)
    )

    @Test
    fun handVerifiedCases() {
        val failures = cases.mapNotNull { case ->
            val repair = PlayerSubtitleRtlFix.repairLine(case.input, case.numbersMoved, case.numbersReversed)
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
        val tags = Regex("<[^>]+>")
        dir.listFiles { file -> file.extension == "srt" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val blocks = file.readText(Charsets.UTF_8).replace("\r\n", "\n").split("\n\n")
            val lines = blocks.flatMap { block -> block.split("\n").drop(2) }
                .map { it.replace(tags, "") }
                .filter { it.isNotEmpty() }
            val swapped = PlayerSubtitleRtlFix.looksLikeSwappedBoundaries(lines.asSequence())
            val numbersReversed = swapped && PlayerSubtitleRtlFix.looksLikeReversedNumbers(lines.asSequence())
            for (line in lines) {
                for (numbersMoved in listOf(false, true)) {
                    val repair = if (swapped) {
                        PlayerSubtitleRtlFix.repairLine(line, numbersMoved, numbersReversed)
                    } else {
                        PlayerSubtitleRtlFix.LineRepair(line)
                    }
                    entries.add("$line | $numbersMoved | $numbersReversed | $swapped | ${repair.text} | ${repair.marks}")
                }
            }
        }
        return entries.toList()
    }
}

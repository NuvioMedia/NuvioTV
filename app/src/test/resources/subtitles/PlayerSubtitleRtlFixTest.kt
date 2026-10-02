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
        val numbersMoved: Boolean = false
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
            val repair = PlayerSubtitleRtlFix.repairLine(case.input, case.numbersMoved)
            val text = repair.text.toString()
            if (text == case.expected && repair.rules == case.rules) null
            else "input   : ${case.input}\n  expected: ${case.expected}  ${case.rules}\n  actual  : $text  ${repair.rules}"
        }
        if (failures.isNotEmpty()) fail("${failures.size} case(s) changed:\n" + failures.joinToString("\n"))
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
            fail("${changed.size} snapshot entries differ (input | numbersMoved | output | marks):\n" +
                changed.take(40).joinToString("\n"))
        }
        assertEquals(expected.size, actual.size)
    }

    private fun buildSnapshot(dir: File): List<String> {
        val entries = sortedSetOf<String>()
        dir.listFiles { file -> file.extension == "srt" }.orEmpty().sortedBy { it.name }.forEach { file ->
            val blocks = file.readText(Charsets.UTF_8).replace("\r\n", "\n").split("\n\n")
            for (block in blocks) {
                for (line in block.split("\n").drop(2)) {
                    if (line.isEmpty()) continue
                    for (numbersMoved in listOf(false, true)) {
                        val repair = PlayerSubtitleRtlFix.repairLine(line, numbersMoved)
                        entries.add("$line | $numbersMoved | ${repair.text} | ${repair.marks}")
                    }
                }
            }
        }
        return entries.toList()
    }
}

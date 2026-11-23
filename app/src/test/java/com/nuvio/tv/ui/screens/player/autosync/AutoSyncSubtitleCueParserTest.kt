package com.nuvio.tv.ui.screens.player.autosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncSubtitleCueParserTest {
    @Test
    fun ttmlUsesDeclaredFrameRate() {
        val cues = AutoSyncSubtitleCueParser.parse(
            text = """
                <tt xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25">
                  <body><div>
                    <p begin="00:00:10:12" end="00:00:12:00">Frame rated</p>
                  </div></body>
                </tt>
            """.trimIndent(),
            sourceUrl = "subtitle.ttml",
        )

        val cue = cues.singleOrNull()
        assertNotNull(cue)
        assertEquals(10_480L, cue!!.startTimeMs)
        assertEquals(12_000L, cue.endTimeMs)
    }

    @Test
    fun ttmlUsesFrameRateMultiplier() {
        val cues = AutoSyncSubtitleCueParser.parse(
            text = """
                <tt xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
                    ttp:frameRate="30"
                    ttp:frameRateMultiplier="1000 1001">
                  <body><div>
                    <p begin="00:00:10:15" end="00:00:11:00">Fractional frame rate</p>
                  </div></body>
                </tt>
            """.trimIndent(),
            sourceUrl = "subtitle.ttml",
        )

        val cue = cues.singleOrNull()
        assertNotNull(cue)
        assertEquals(10_500L, cue!!.startTimeMs)
        assertEquals(11_000L, cue.endTimeMs)
    }

    @Test
    fun assWithEmbeddedFontsAndIndentedEventsParsesAndRetimes() {
        val ass = buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("[Fonts]")
            appendLine("fontname: embedded.ttf")
            appendLine("!!%[not-a-header]abcdef1234567890")
            appendLine("  [Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            appendLine("Dialogue: 0,0:00:05.00,0:00:07.50,Default,,0,0,0,,Hello world")
            appendLine("Dialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,Second line")
        }

        val cues = AutoSyncSubtitleCueParser.parse(ass)
        assertEquals(2, cues.size)
        assertEquals(5_000L, cues[0].startTimeMs)
        assertEquals(10_000L, cues[1].startTimeMs)

        val timeline = AutoSyncTimelineRetimeResult(
            cues = cues.map {
                AutoSyncRetimedCue(it.startTimeMs, it.endTimeMs, it.startTimeMs + 1_500L, it.endTimeMs + 1_500L)
            },
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 1.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = 2,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentScale = 1.0,
            alignmentInterceptMs = 1_500.0,
        )
        val retimed = AutoSyncSubtitleTextRetimer.retime(ass, timeline)
        assertNotNull(retimed)
        assertTrue(retimed!!.contains("fontname: embedded.ttf"))
        val reparsed = AutoSyncSubtitleCueParser.parse(retimed)
        assertEquals(2, reparsed.size)
        assertEquals(6_500L, reparsed[0].startTimeMs)
        assertEquals(11_500L, reparsed[1].startTimeMs)
    }

    @Test
    fun hasAssEventsHeaderHandlesRawCrLf() {
        val raw = "[Script Info]\r\nScriptType: v4.00+\r\n\r\n[Events]\r\nFormat: Start, End, Text\r\n"
        assertTrue(AutoSyncSubtitleCueParser.hasAssEventsHeader(raw))
    }

    @Test
    fun bulkSectionSkipFindsIndentedHeaderAfterCarriageReturnLines() {
        val raw = "[Fonts]\rfontname: a.ttf\rAB[CD\r  [Events]\rFormat: Start, End, Text\r"
        val found = AutoSyncSubtitleCueParser.findNextAssSectionAfterBulk(raw, raw.indexOf('\r') + 1)
        assertEquals(raw.indexOf("  [Events]"), found)
    }

    @Test
    fun bulkSectionSkipFindsIndentedHeaderAfterCrLfLines() {
        val raw = "[Fonts]\r\nfontname: a.ttf\r\nAB[CD\r\n  [Events]\r\nFormat: Start, End, Text\r\n"
        val found = AutoSyncSubtitleCueParser.findNextAssSectionAfterBulk(raw, raw.indexOf('\n') + 1)
        assertEquals(raw.indexOf("  [Events]"), found)
    }
}

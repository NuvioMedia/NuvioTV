package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubtitleCuePositionTest {
    @Test
    fun `cues placed at the top or middle keep their position`() {
        val top = Cue.Builder().setText("Top").setLine(0.08f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
        val middle = Cue.Builder().setText("Middle").setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE).build()
        val firstLine = Cue.Builder().setText("Line 0").setLine(0f, Cue.LINE_TYPE_NUMBER).build()

        assertSame(top, PlayerSubtitleUtils.normalizeBottomCuePosition(top))
        assertSame(middle, PlayerSubtitleUtils.normalizeBottomCuePosition(middle))
        assertSame(firstLine, PlayerSubtitleUtils.normalizeBottomCuePosition(firstLine))
    }

    @Test
    fun `cues placed at the bottom follow the subtitle position setting`() {
        val bottom = Cue.Builder().setText("Bottom").setLine(0.92f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        val lastLine = Cue.Builder().setText("Last line").setLine(-1f, Cue.LINE_TYPE_NUMBER).build()

        for (cue in listOf(bottom, lastLine)) {
            val normalized = PlayerSubtitleUtils.normalizeBottomCuePosition(cue)
            assertEquals(Cue.DIMEN_UNSET, normalized.line)
            assertEquals(Cue.TYPE_UNSET, normalized.lineType)
            assertEquals(Cue.TYPE_UNSET, normalized.lineAnchor)
            assertEquals(cue.text.toString(), normalized.text.toString())
        }
    }

    @Test
    fun `unpositioned cues still merge when a positioned cue is on screen`() {
        val top = Cue.Builder().setText("Top").setLine(0.08f, Cue.LINE_TYPE_FRACTION).build()
        val first = Cue.Builder().setText("First").build()
        val second = Cue.Builder().setText("Second").build()

        val result = PlayerSubtitleUtils.mergeOverlappingCues(listOf(first, top, second))

        assertEquals(listOf("Top", "First\nSecond"), result.map { it.text.toString() })
        assertEquals(0.08f, result[0].line)
        assertEquals(Cue.DIMEN_UNSET, result[1].line)
    }

    @Test
    fun `a single unpositioned cue next to a positioned one is left alone`() {
        val cues = listOf(
            Cue.Builder().setText("Top").setLine(0.08f, Cue.LINE_TYPE_FRACTION).build(),
            Cue.Builder().setText("Bottom").build()
        )
        assertSame(cues, PlayerSubtitleUtils.mergeOverlappingCues(cues))
    }
}

package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSubtitleTextTest {

    @Test
    fun `italic tags become italic text`() {
        val cues = parseMpvSubtitleText("""{\i1}Italic line{\i0}\NPlain line""")

        assertEquals("Italic line\nPlain line", cues.bottom.text)
        val italic = cues.bottom.spanStyles.single()
        assertEquals(FontStyle.Italic, italic.item.fontStyle)
        assertEquals(0, italic.start)
        assertEquals("Italic line".length, italic.end)
    }

    @Test
    fun `top aligned events render at the top`() {
        val cues = parseMpvSubtitleText("Dialogue\n{\\an8}Sign")

        assertEquals("Sign", cues.top.text)
        assertEquals("Dialogue", cues.bottom.text)
    }

    @Test
    fun `other override tags are dropped without changing the style`() {
        val cues = parseMpvSubtitleText("""{\bord2\blur3\an2}Plain {\b1}bold{\b0}""")

        assertEquals("Plain bold", cues.bottom.text)
        assertTrue(cues.top.isEmpty())
        assertEquals(FontWeight.Bold, cues.bottom.spanStyles.single().item.fontWeight)
    }

    @Test
    fun `plain text and blank input are handled`() {
        assertEquals("Hello\nthere", parseMpvSubtitleText(" Hello\nthere ").bottom.text)
        assertTrue(parseMpvSubtitleText("").isEmpty)
    }
}

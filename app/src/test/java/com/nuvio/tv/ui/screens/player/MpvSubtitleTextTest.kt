package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSubtitleTextTest {

    @Test
    fun `italic tags become italic text`() {
        val cues = parseMpvSubtitleText("""{\i1}Italic line{\i0}\NPlain line""")

        assertEquals("Italic line\nPlain line", cues.bottomText.text)
        val italic = cues.bottomText.spanStyles.single()
        assertEquals(FontStyle.Italic, italic.item.fontStyle)
        assertEquals(0, italic.start)
        assertEquals("Italic line".length, italic.end)
    }

    @Test
    fun `top aligned events render at the top`() {
        val cues = parseMpvSubtitleText("Dialogue\n{\\an8}Sign")

        assertEquals("Sign", cues.topText.text)
        assertEquals("Dialogue", cues.bottomText.text)
    }

    @Test
    fun `other override tags are dropped without changing the style`() {
        val cues = parseMpvSubtitleText("""{\bord2\blur3\an2}Plain {\b1}bold{\b0}""")

        assertEquals("Plain bold", cues.bottomText.text)
        assertTrue(cues.topText.isEmpty())
        assertEquals(FontWeight.Bold, cues.bottomText.spanStyles.single().item.fontWeight)
    }

    @Test
    fun `plain text and blank input are handled`() {
        assertEquals("Hello\nthere", parseMpvSubtitleText(" Hello\nthere ").bottomText.text)
        assertTrue(parseMpvSubtitleText("").isEmpty)
    }

    @Test
    fun `alignment tags place events in all nine positions`() {
        val cues = parseMpvSubtitleText("{\\an9}Top right\n{\\an4}Middle left\nDialogue")

        assertEquals(
            listOf(
                Triple("Top right", SubtitleVerticalPosition.TOP, SubtitleHorizontalPosition.END),
                Triple("Middle left", SubtitleVerticalPosition.MIDDLE, SubtitleHorizontalPosition.START),
                Triple("Dialogue", SubtitleVerticalPosition.BOTTOM, SubtitleHorizontalPosition.CENTER)
            ),
            cues.blocks.map { Triple(it.text.text, it.vertical, it.horizontal) }
        )
    }

    @Test
    fun `color tags use the ass blue green red order and reset clears them`() {
        val cues = parseMpvSubtitleText("""{\c&H0000FF&}Red{\r} plain {\1c&H00FF00&}green""")

        assertEquals("Red plain green", cues.bottomText.text)
        assertEquals(
            listOf(Color.Red, Color.Green),
            cues.bottomText.spanStyles.map { it.item.color }
        )
        assertEquals("Red".length, cues.bottomText.spanStyles.first().end)
    }

    @Test
    fun `an empty color tag resets to the default color`() {
        val cues = parseMpvSubtitleText("""{\c&H00FFFF&}John:{\c} where are you?""")

        assertEquals("John: where are you?", cues.bottomText.text)
        val yellow = cues.bottomText.spanStyles.single()
        assertEquals(Color.Yellow, yellow.item.color)
        assertEquals("John:".length, yellow.end)
    }

    @Test
    fun `color tags without the closing ampersand still apply`() {
        val cues = parseMpvSubtitleText("""{\1c&H0000FF}Red""")

        assertEquals(Color.Red, cues.bottomText.spanStyles.single().item.color)
    }
}

package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMojibakeSanitizerTest {

    @Test
    fun replacesMusicalNotesMojibake() {
        assertEquals("♪ lalala ♪", SubtitleMojibakeSanitizer.sanitize("â™ª lalala â™ª").toString())
        assertEquals("♫ song playing ♫", SubtitleMojibakeSanitizer.sanitize("â™« song playing â™«").toString())
        assertEquals("♪ melody ♪", SubtitleMojibakeSanitizer.sanitize("â™ melody â™").toString())
    }

    @Test
    fun replacesQuotesAndPunctuationMojibake() {
        assertEquals("It’s great!", SubtitleMojibakeSanitizer.sanitize("Itâ€™s great!").toString())
        assertEquals("‘Hello’", SubtitleMojibakeSanitizer.sanitize("â€˜Helloâ€™").toString())
        assertEquals("“Quote”", SubtitleMojibakeSanitizer.sanitize("â€œQuoteâ€").toString())
        assertEquals("“Quote”", SubtitleMojibakeSanitizer.sanitize("â€œQuoteâ€\u009D").toString())
        assertEquals("Wait – what — why…", SubtitleMojibakeSanitizer.sanitize("Wait â€“ what â€” whyâ€¦").toString())
    }

    @Test
    fun replacesSpanishPunctuationMojibake() {
        assertEquals("¿Cómo estás? ¡Bien!", SubtitleMojibakeSanitizer.sanitize("Â¿Cómo estás? Â¡Bien!").toString())
        assertEquals("«Hola»", SubtitleMojibakeSanitizer.sanitize("Â«HolaÂ»").toString())
        assertEquals("Hello world", SubtitleMojibakeSanitizer.sanitize("HelloÂ world").toString())
    }

    @Test
    fun leavesCleanTextUnchanged() {
        val clean = "Hello, world! 123 ♪ ♫ “test”"
        assertEquals(clean, SubtitleMojibakeSanitizer.sanitize(clean).toString())
    }

    @Test
    fun stripsReplacementCharacters() {
        assertEquals("Hello world", SubtitleMojibakeSanitizer.sanitize("Hello \uFFFDworld\uFFFD").toString())
    }

    @Test
    fun sanitizesCueWhilePreservingProperties() {
        val cue = Cue.Builder()
            .setText("â™ª Music playing â™ª")
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .build()

        val sanitizedCue = SubtitleMojibakeSanitizer.sanitizeCue(cue)
        assertEquals("♪ Music playing ♪", sanitizedCue.text.toString())
        assertEquals(0.8f, sanitizedCue.line, 0.001f)
    }
}

package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSdhFilterTest {

    private fun filter(text: String) = SubtitleSdhFilter.filterPlainText(text)

    @Test
    fun linesThatEndInAColonStay() {
        assertEquals("He said:", filter("He said:"))
        assertEquals("Here's what I think:", filter("Here's what I think:"))
        assertEquals("And the winner is:", filter("And the winner is:"))
        assertEquals("You know what he told me:\nnever again.", filter("You know what he told me:\nnever again."))
        assertEquals("John:", filter("John:"))
    }

    @Test
    fun aSentenceWithAColonInsideStaysWhole() {
        assertEquals("Remember this: run.", filter("Remember this: run."))
        assertEquals("He said: \"Get out.\"", filter("He said: \"Get out.\""))
        assertEquals("I'll tell you what: no.", filter("I'll tell you what: no."))
    }

    @Test
    fun capitalLabelsGo() {
        assertNull(filter("JOHN:"))
        assertEquals("Hello.", filter("JOHN: Hello."))
        assertEquals("Once upon a time", filter("NARRATOR: Once upon a time"))
        assertEquals("Get down!", filter("MAN #2: Get down!"))
        assertEquals("Come in.", filter("DR. HOUSE (V.O.): Come in."))
        assertEquals("Ready?", filter("JOHN:\nReady?"))
    }

    @Test
    fun aNameLabelGoesWhenSpeechFollows() {
        assertEquals("Hello", filter("John: Hello"))
        assertEquals("Yes, sir.", filter("Mr. Smith: Yes, sir."))
        assertEquals("Over here.", filter("Officer Brown: Over here."))
    }

    @Test
    fun dialogueDashesKeepTheirDash() {
        assertEquals("- Where are you?\n- Outside.", filter("- JOHN: Where are you?\n- Mary: Outside."))
    }

    @Test
    fun bracketsAndChevronsStillGo() {
        assertNull(filter("[MUSIC]"))
        assertNull(filter("(door creaks)"))
        assertEquals("Hello.", filter("[John]: Hello."))
        assertEquals("Over here.", filter(">> ANNA: Over here."))
        assertEquals("Run!", filter("[EXPLOSION] Run!"))
    }

    @Test
    fun theLabelRule() {
        assertTrue(SubtitleSdhFilter.isSpeakerLabel("JOHN", textFollows = false))
        assertTrue(SubtitleSdhFilter.isSpeakerLabel("[john]", textFollows = false))
        assertTrue(SubtitleSdhFilter.isSpeakerLabel("John", textFollows = true))
        assertFalse(SubtitleSdhFilter.isSpeakerLabel("John", textFollows = false))
        assertFalse(SubtitleSdhFilter.isSpeakerLabel("He said", textFollows = true))
        assertFalse(SubtitleSdhFilter.isSpeakerLabel("The One Who Knocks Twice", textFollows = true))
        assertFalse(SubtitleSdhFilter.isSpeakerLabel("10", textFollows = true))
    }
}

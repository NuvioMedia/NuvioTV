package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtDocumentTest {
    @Test
    fun `parses and preserves multiline Hebrew text`() {
        val document = SrtDocument.parse(
            "\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\nשלום עולם\r\nשורה שניה\r\n\r\n"
        )

        assertEquals(1, document.cues.size)
        assertEquals(1_250L, document.cues.single().startMs)
        assertEquals(3_500L, document.cues.single().endMs)
        assertEquals("שלום עולם\nשורה שניה", document.cues.single().text)
        assertEquals(
            "1\n00:00:01,250 --> 00:00:03,500\nשלום עולם\nשורה שניה\n\n",
            document.encode()
        )
    }

    @Test
    fun `ignores malformed and empty cues`() {
        val document = SrtDocument.parse(
            "1\nnot timing\nBad\n\n2\n00:00:05,000 --> 00:00:04,000\nBackwards\n\n"
        )
        assertEquals(emptyList<SrtCue>(), document.cues)
    }
}

package com.nuvio.tv.core.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XmlTvParserTest {
    @Test
    fun parsesPortugueseNamesAndNormalizesOffsetTimesToUtc() {
        val result = XmlTvParser.parse(
            """<?xml version="1.0" encoding="UTF-8"?>
                |<tv>
                |  <channel id="br.novela"><display-name lang="en">Soap</display-name><display-name lang="pt-BR">Novela Brasil</display-name><icon src="https://cdn.example/channel.png"/></channel>
                |  <programme channel="br.novela" start="20260101180000 -0300" stop="20260101190000 -0300">
                |    <title lang="en">Drama</title><title lang="pt-BR">Capítulo especial</title>
                |    <desc lang="pt-BR">Edição de hoje</desc><category>Novela</category>
                |  </programme>
                |</tv>
            """.trimMargin(),
            sourceId = "guide-br",
        )

        assertEquals(1, result.channels.size)
        assertEquals("Novela Brasil", result.channels.single().displayName)
        assertNull(result.channels.single().streamUrl)
        assertEquals("Capítulo especial", result.programs.single().title)
        assertEquals("Edição de hoje", result.programs.single().description)
        assertEquals("Novela", result.programs.single().category)
        assertEquals(1_767_301_200_000L, result.programs.single().startEpochMillis)
        assertEquals(1_767_304_800_000L, result.programs.single().endEpochMillis)
    }

    @Test
    fun skipsInvalidIntervalsAndKeepsValidProgrammes() {
        val result = XmlTvParser.parse(
            """<tv>
                |<programme channel="news" start="20260101210000 +0000" stop="20260101200000 +0000"><title>Invalid</title></programme>
                |<programme channel="news" start="20260101210000 +0000" stop="20260101220000 +0000"><title>Valid</title></programme>
                |</tv>""".trimMargin(),
            sourceId = "guide",
        )
        assertEquals(1, result.programs.size)
        assertEquals("Valid", result.programs.single().title)
        assertEquals(1, result.skippedEntries)
    }

    @Test
    fun rejectsDoctypeAndExternalEntities() {
        val xml = """<!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tv><channel id="x"><display-name>&xxe;</display-name></channel></tv>"""
        assertThrows(LiveTvPlaylistFormatException::class.java) {
            XmlTvParser.parse(xml, sourceId = "guide")
        }
    }
}

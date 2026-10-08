package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.MatroskaAfrProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaChapterParserTest {

    private fun element(id: Long, vararg children: ByteArray): ByteArray =
        MatroskaAfrProbe.buildElement(id, children.fold(ByteArray(0)) { acc, child -> acc + child })

    private fun uint(id: Long, value: Long): ByteArray {
        var width = 1
        while (width < 8 && value ushr (width * 8) != 0L) width++
        return MatroskaAfrProbe.buildElement(id, ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() })
    }

    private fun text(id: Long, value: String): ByteArray = MatroskaAfrProbe.buildElement(id, value.toByteArray())

    private fun atom(startMs: Long, title: String?, hidden: Boolean = false, enabled: Boolean = true): ByteArray {
        val parts = mutableListOf(uint(0x91L, startMs * 1_000_000L))
        if (hidden) parts += uint(0x98L, 1L)
        if (!enabled) parts += uint(0x4598L, 0L)
        if (title != null) parts += element(0x80L, text(0x85L, title), text(0x437CL, "eng"))
        return element(0xB6L, *parts.toTypedArray())
    }

    private fun chapters(vararg editions: ByteArray): ByteArray =
        element(MatroskaChapterParser.ID_CHAPTERS, *editions)

    @Test
    fun `chapters of the only edition with titles and times`() {
        val parsed = MatroskaChapterParser.parseChapters(
            chapters(element(0x45B9L, atom(0L, "Opening"), atom(90_500L, "Part A"), atom(1_200_000L, null)))
        )
        assertEquals(listOf(0L, 90_500L, 1_200_000L), parsed.map { it.startMs })
        assertEquals(listOf("Opening", "Part A", null), parsed.map { it.title })
    }

    @Test
    fun `hidden and disabled chapters are left out`() {
        val parsed = MatroskaChapterParser.parseChapters(
            chapters(element(0x45B9L, atom(0L, "A"), atom(10_000L, "Hidden", hidden = true), atom(20_000L, "Off", enabled = false), atom(30_000L, "B")))
        )
        assertEquals(listOf("A", "B"), parsed.map { it.title })
    }

    @Test
    fun `the default edition wins over the first one`() {
        val first = element(0x45B9L, atom(0L, "first"))
        val default = element(0x45B9L, uint(0x45DBL, 1L), atom(0L, "default"), atom(5_000L, "default 2"))
        assertEquals("default", MatroskaChapterParser.parseChapters(chapters(first, default)).first().title)
    }

    @Test
    fun `a hidden edition is skipped`() {
        val hidden = element(0x45B9L, uint(0x45BDL, 1L), atom(0L, "hidden"))
        val shown = element(0x45B9L, atom(0L, "shown"))
        assertEquals("shown", MatroskaChapterParser.parseChapters(chapters(hidden, shown)).first().title)
    }

    @Test
    fun `not a chapters element gives nothing`() {
        assertTrue(MatroskaChapterParser.parseChapters(element(0x45B9L, atom(0L, "x"))).isEmpty())
    }

    @Test
    fun `layout finds chapters before the first cluster`() {
        val ebml = element(MatroskaAfrProbe.ID_EBML, uint(0x4282L, 0L))
        val chaptersElement = chapters(element(0x45B9L, atom(0L, "A"), atom(1_000L, "B")))
        val info = element(MatroskaAfrProbe.ID_INFO, uint(0x2AD7B1L, 1_000_000L))
        val payload = info + chaptersElement + element(MatroskaAfrProbe.ID_CLUSTER, uint(0xE7L, 0L))
        val file = ebml + MatroskaAfrProbe.buildSegmentUnknownSize(payload)

        val layout = MatroskaChapterParser.topLevelLayout(file)!!
        val chaptersAt = layout.chaptersPosition!!
        assertEquals((file.size - payload.size).toLong(), layout.segmentDataStart)
        assertEquals(layout.segmentDataStart + info.size, chaptersAt)
        assertNull(layout.seekHeadPosition)
    }

    @Test
    fun `seek head gives chapters position relative to the segment`() {
        val seek = element(
            MatroskaAfrProbe.ID_SEEK,
            MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_SEEK_ID, MatroskaAfrProbe.elementIdBytes(MatroskaChapterParser.ID_CHAPTERS)),
            uint(MatroskaAfrProbe.ID_SEEK_POSITION, 123_456_789L)
        )
        val positions = MatroskaChapterParser.seekHeadPositions(element(MatroskaAfrProbe.ID_SEEK_HEAD, seek))
        assertEquals(123_456_789L, positions[MatroskaChapterParser.ID_CHAPTERS])
    }

    @Test
    fun `not matroska has no layout`() {
        assertNull(MatroskaChapterParser.topLevelLayout("....ftypisom".toByteArray() + ByteArray(64)))
    }
}

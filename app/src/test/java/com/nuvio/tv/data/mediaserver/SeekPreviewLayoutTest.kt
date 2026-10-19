package com.nuvio.tv.data.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewLayoutTest {
    // 10x10 sheets, one thumbnail every 10 seconds, 250 thumbnails -> 3 sheets.
    private val layout = SeekPreviewLayout(
        key = "item/source/320",
        thumbWidth = 320,
        thumbHeight = 180,
        columns = 10,
        rows = 10,
        thumbnailCount = 250,
        intervalMs = 10_000
    )

    @Test
    fun `position maps to thumbnail by interval`() {
        assertEquals(0, layout.thumbnailIndexAt(0))
        assertEquals(0, layout.thumbnailIndexAt(9_999))
        assertEquals(1, layout.thumbnailIndexAt(10_000))
        assertEquals(42, layout.thumbnailIndexAt(425_000))
    }

    @Test
    fun `position is clamped to the available thumbnails`() {
        assertEquals(0, layout.thumbnailIndexAt(-5_000))
        assertEquals(249, layout.thumbnailIndexAt(99_999_999))
    }

    @Test
    fun `thumbnail maps to sheet and cell`() {
        assertEquals(0, layout.sheetIndexOf(99))
        assertEquals(1, layout.sheetIndexOf(100))
        assertEquals(2, layout.sheetIndexOf(249))
        assertEquals(SeekPreviewCell(column = 0, row = 0), layout.cellOf(0))
        assertEquals(SeekPreviewCell(column = 9, row = 0), layout.cellOf(9))
        assertEquals(SeekPreviewCell(column = 0, row = 1), layout.cellOf(10))
        assertEquals(SeekPreviewCell(column = 2, row = 4), layout.cellOf(142))
    }

    @Test
    fun `invalid layouts are rejected`() {
        assertTrue(layout.isValid)
        assertFalse(layout.copy(intervalMs = 0).isValid)
        assertFalse(layout.copy(columns = 0).isValid)
        assertFalse(layout.copy(thumbnailCount = 0).isValid)
        assertFalse(layout.copy(thumbWidth = 0).isValid)
    }
}

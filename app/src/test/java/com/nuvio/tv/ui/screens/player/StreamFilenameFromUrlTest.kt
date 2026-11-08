package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFilenameFromUrlTest {

    @Test
    fun `takes the last path segment and drops the query`() {
        assertEquals(
            "Show.S01E02.1080p.mkv",
            streamFilenameFromUrl("https://cdn.example.com/dl/abc/Show.S01E02.1080p.mkv?token=x.y")
        )
    }

    @Test
    fun `a path without a file extension gives no filename`() {
        assertNull(streamFilenameFromUrl("https://debrid.example.com/download/12345?file=1"))
    }

    @Test
    fun `a url ending in a slash gives no filename`() {
        assertNull(streamFilenameFromUrl("https://cdn.example.com/stream/"))
    }
}

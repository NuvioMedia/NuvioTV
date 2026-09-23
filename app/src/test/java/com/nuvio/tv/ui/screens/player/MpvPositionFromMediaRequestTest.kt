package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPositionFromMediaRequestTest {

    @Test
    fun `previous file is not from the request after an episode switch`() {
        assertFalse(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", "https://h/e14.mkv"))
    }

    @Test
    fun `nothing loaded yet is not from the request`() {
        assertFalse(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", null))
    }

    @Test
    fun `requested url counts`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", "https://h/e15.mkv"))
        assertTrue(isMpvPositionFromMediaRequest("https://h/e15.mkv", null, "https://h/e15.mkv"))
    }

    @Test
    fun `reload of the same url counts`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e15.mkv", "https://h/e15.mkv"))
    }

    @Test
    fun `playlist entry path counts once it differs from the old path`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/list.m3u", "https://h/e14.mkv", "https://h/e15.mkv"))
    }

    @Test
    fun `no request counts`() {
        assertTrue(isMpvPositionFromMediaRequest(null, null, null))
    }

    @Test
    fun `empty path is not from the request`() {
        assertFalse(isMpvPositionFromMediaRequest("https://h/e15.mkv", "https://h/e14.mkv", ""))
    }

    @Test
    fun `reload of a loaded playlist counts once a path is reported`() {
        assertTrue(isMpvPositionFromMediaRequest("https://h/list.m3u", null, "https://h/e14.mkv"))
    }

    @Test
    fun `new request records the current path`() {
        assertEquals("https://h/e14.mkv", mpvPathBaselineForRequest("https://h/e15.mkv", "https://h/e14.mkv", null, "https://h/e14.mkv"))
        assertNull(mpvPathBaselineForRequest("https://h/e15.mkv", null, null, ""))
    }

    @Test
    fun `repeat of a pending request keeps its baseline`() {
        assertEquals("https://h/e14.mkv", mpvPathBaselineForRequest("https://h/e15.mkv", "https://h/e15.mkv", "https://h/e14.mkv", "https://h/e14.mkv"))
    }

    @Test
    fun `repeat of a loaded request drops its baseline`() {
        assertNull(mpvPathBaselineForRequest("https://h/list.m3u", "https://h/list.m3u", "https://h/e13.mkv", "https://h/e14.mkv"))
    }
}

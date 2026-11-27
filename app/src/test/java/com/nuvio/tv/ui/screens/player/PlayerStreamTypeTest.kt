package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStreamTypeTest {
    @Test fun `the torrent engine is P2P even on its loopback address`() {
        assertEquals(PlayerStreamType.P2P, streamTypeOf(true, false, "movie", "http://127.0.0.1:8090/stream/abc"))
    }

    @Test fun `server and cloud playback are named before the link`() {
        assertEquals(PlayerStreamType.SERVER, streamTypeOf(false, true, "movie", "https://plex.example/video.mkv"))
        assertEquals(PlayerStreamType.CLOUD, streamTypeOf(false, false, "cloud", "https://cdn.example/file.mkv"))
    }

    @Test fun `files on the device are local and other links are direct`() {
        assertEquals(PlayerStreamType.LOCAL, streamTypeOf(false, false, "movie", "file:///storage/emulated/0/Movie.mkv"))
        assertEquals(PlayerStreamType.LOCAL, streamTypeOf(false, false, "movie", "content://media/external/video/1"))
        assertEquals(PlayerStreamType.DIRECT, streamTypeOf(false, false, "series", "https://cdn.example/Show.S01E01.mkv"))
    }

    @Test fun `a direct link names its addon`() {
        assertEquals("Direct · Torrentio", streamTypeLabel(PlayerStreamType.DIRECT, "Torrentio"))
        assertEquals("Direct", streamTypeLabel(PlayerStreamType.DIRECT, null))
        assertEquals("P2P", streamTypeLabel(PlayerStreamType.P2P, "Torrentio"))
    }
}

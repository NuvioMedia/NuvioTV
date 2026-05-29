package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Test

class TorboxFileSelectorTest {
    private val selector = TorboxFileSelector()

    @Test
    fun `selects file by torbox file id first`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "wrong.mkv", size = 20),
                file(id = 9, name = "right.mkv", size = 10)
            ),
            resolve = resolve(fileIdx = 9),
            season = null,
            episode = null
        )

        assertEquals(9, selected?.id)
    }

    @Test
    fun `falls back to filename match`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "sample.mkv", size = 300),
                file(id = 2, name = "show.s01e02.1080p.mkv", size = 200)
            ),
            resolve = resolve(fileIdx = 88, filename = "show.s01e02.1080p.mkv"),
            season = null,
            episode = null
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `falls back to episode pattern before largest video`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "show.s01e01.mkv", size = 800),
                file(id = 2, name = "show.s01e02.mkv", size = 300)
            ),
            resolve = resolve(fileIdx = null),
            season = 1,
            episode = 2
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `falls back to largest playable video`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "small.txt", size = 900),
                file(id = 2, name = "small.mkv", size = 200),
                file(id = 3, name = "large.mp4", size = 500)
            ),
            resolve = resolve(fileIdx = null),
            season = null,
            episode = null
        )

        assertEquals(3, selected?.id)
    }

    private fun file(id: Int, name: String, size: Long): TorboxTorrentFileDto = TorboxTorrentFileDto(
        id = id,
        name = name,
        shortName = null,
        absolutePath = null,
        mimeType = null,
        size = size
    )

    private fun resolve(
        fileIdx: Int?,
        filename: String? = null
    ): StreamClientResolve = StreamClientResolve(
        type = "debrid",
        infoHash = "hash",
        fileIdx = fileIdx,
        magnetUri = "magnet:?xt=urn:btih:hash",
        sources = null,
        torrentName = "show",
        filename = filename,
        mediaType = "series",
        mediaId = "tt1:1:2",
        mediaOnlyId = "tt1",
        title = "show",
        season = 1,
        episode = 2,
        service = "torbox",
        serviceIndex = 0,
        serviceExtension = null,
        isCached = true
    )
}

package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridFileSelectorParityTest {
    @Test
    fun `torbox selector prefers filename match before provider file id`() {
        val files = listOf(
            torboxFile(id = 0, name = "Request High Bitrate Stuff in Here.txt", size = 1),
            torboxFile(
                id = 85,
                name = "The Office US S01-S09/The.Office.US.S01E01.Pilot.1080p.BluRay.Remux.mkv",
                size = 5_303_936_915
            ),
            torboxFile(
                id = 1,
                name = "The Office US S01-S09/The.Office.US.S08E13.Jury.Duty.1080p.BluRay.Remux.mkv",
                size = 5_859_312_140
            )
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(
                fileIdx = 1,
                season = 1,
                episode = 1,
                filename = "The.Office.US.S01E01.Pilot.1080p.BluRay.Remux.mkv"
            ),
            season = 1,
            episode = 1
        )

        assertEquals(85, selected?.id)
    }

    @Test
    fun `torbox selector treats fileIdx as source list index before provider file id`() {
        val files = listOf(
            torboxFile(id = 0, name = "Request High Bitrate Stuff in Here.txt", size = 1),
            torboxFile(id = 85, name = "Show.S01E01.mkv", size = 500),
            torboxFile(id = 1, name = "Show.S08E13.mkv", size = 900)
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(fileIdx = 1),
            season = null,
            episode = null
        )

        assertEquals(85, selected?.id)
    }

    @Test
    fun `torbox selector uses episode pattern before broad title`() {
        val files = listOf(
            torboxFile(id = 1, name = "The.Office.US.S08E13.Jury.Duty.mkv", size = 900),
            torboxFile(id = 85, name = "The.Office.US.S01E01.Pilot.mkv", size = 500)
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(
                season = 1,
                episode = 1,
                title = "The Office"
            ),
            season = 1,
            episode = 1
        )

        assertEquals(85, selected?.id)
    }

    @Test
    fun `real debrid selector matches episode pattern before largest file`() {
        val files = listOf(
            RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 1_000),
            RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 2_000)
        )

        val selected = RealDebridFileSelector().selectFile(
            files = files,
            resolve = resolve(season = 1, episode = 1),
            season = null,
            episode = null
        )

        assertEquals(1, selected?.id)
    }

    private fun torboxFile(id: Int, name: String, size: Long): TorboxTorrentFileDto =
        TorboxTorrentFileDto(
            id = id,
            name = name,
            shortName = null,
            absolutePath = null,
            mimeType = null,
            size = size
        )

    private fun resolve(
        fileIdx: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        filename: String? = null,
        title: String? = null
    ): StreamClientResolve =
        StreamClientResolve(
            type = "debrid",
            infoHash = "hash",
            fileIdx = fileIdx,
            magnetUri = "magnet:?xt=urn:btih:hash",
            sources = null,
            torrentName = "show",
            filename = filename,
            mediaType = "series",
            mediaId = "tt1:1:1",
            mediaOnlyId = "tt1",
            title = title,
            season = season,
            episode = episode,
            service = DebridProviders.TORBOX_ID,
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
}

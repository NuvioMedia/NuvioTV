package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DebridFileSelectionTest(private val provider: String) {
    @Test
    fun `filename takes priority over raw filename and episode`() {
        val files = listOf(file(4, "Show.S01E01.mkv"), file(8, "Show.S01E02.mkv"))
        val metadata = resolve(filename = "Show.S01E02.mkv").copy(
            stream = StreamClientResolveStream(raw = rawFilename("Show.S01E01.mkv")),
        )

        assertSelected(8, files, metadata, season = 1, episode = 1)
        assertSelected(4, files, metadata.copy(filename = null))
        assertSelected(4, files, metadata.copy(filename = "missing.mkv"))
    }

    @Test
    fun `filename matching preserves unicode punctuation and extension`() {
        val files = listOf(
            file(1, "作品一.mkv"),
            file(2, "作品二.mp4"),
            file(3, "作品二.mkv"),
            file(4, "Show.S01E02.mkv"),
            file(5, "Show-S01E02.mkv"),
        )

        assertSelected(3, files, resolve(filename = "作品二.mkv"))
        assertSelected(5, files, resolve(filename = "Show-S01E02.mkv"))
    }

    @Test
    fun `full path distinguishes repeated basenames and handles windows separators`() {
        val files = listOf(
            file(1, "Show/Season 1/Episode 02.mkv"),
            file(2, "Show/Season 2/Episode 02.mkv"),
        )

        assertSelected(2, files, resolve(filename = "Season 2\\Episode 02.mkv"))
        assertSelected(2, files, resolve(filename = "/Show/Season 2/Episode 02.mkv"))
        assertSelected(null, files, resolve(filename = "Episode 02.mkv", fileIdx = 0))
    }

    @Test
    fun `full path suffix matches only at folder boundaries`() {
        val files = listOf(
            file(1, "AnotherShow/episode.mkv"),
            file(2, "Pack/Show/episode.mkv"),
        )

        assertSelected(2, files, resolve(filename = "Show/episode.mkv"))
    }

    @Test
    fun `exact filename case wins before case insensitive fallback`() {
        val files = listOf(
            file(1, "Season 4/Attack on Titan - 02.mkv"),
            file(2, "Season 1/Attack On Titan - 02.mkv"),
        )

        assertSelected(2, files, resolve(filename = "Attack On Titan - 02.mkv"))
        assertSelected(null, files, resolve(filename = "ATTACK ON TITAN - 02.MKV"))
        assertSelected(2, files.takeLast(1), resolve(filename = "ATTACK ON TITAN - 02.MKV"))
    }

    @Test
    fun `episode fallback respects number boundaries and ignores parent folder`() {
        val files = listOf(
            file(1, "Show.S01E02/Show.S01E20.mkv"),
            file(2, "Show.1x20.mkv"),
            file(3, "Show.1x02.mkv"),
        )

        assertSelected(3, files, resolve(season = 1, episode = 2))
        assertSelected(null, files.take(2), resolve(fileIdx = 0), season = 1, episode = 2)
    }

    @Test
    fun `episode fallback handles unpadded uppercase and padded numbers`() {
        for (name in listOf("Show.S1E2.MKV", "Show.s01e02.mkv", "Show.1x2.mp4", "Show.01x02.mkv")) {
            val files = listOf(file(1, "Show.S11E02.mkv"), file(2, "Show.S01E21.mkv"), file(3, name))
            assertSelected(3, files, resolve(filename = "missing.mkv"), season = 1, episode = 2)
        }
    }

    @Test
    fun `missing or ambiguous episodes do not fall back to another file`() {
        val files = listOf(
            file(1, "Show.S01E01.mkv"),
            file(2, "Show.S01E02.720p.mkv"),
            file(3, "Show.S01E02.1080p.mkv"),
        )

        assertSelected(null, files, resolve(fileIdx = 0), season = 1, episode = 3)
        assertSelected(null, files, resolve(fileIdx = 0), season = 1, episode = 2)
        assertSelected(null, files, resolve(filename = "missing.mkv", fileIdx = 0))
    }

    @Test
    fun `display description does not select the first dual audio file`() {
        val files = listOf(file(1, "Show.S01E01 [Dual Audio].mkv"), file(2, "Show.S01E02 [Dual Audio].mkv"))
        val metadata = resolve().copy(
            torrentName = "Show.S01E02.mkv\nDubbed / Dual Audio",
            title = "Show.S01E01 [Dual Audio].mkv",
        )

        assertSelected(2, files, metadata, season = 1, episode = 2)
    }

    @Test
    fun `invalid torrent indexes are not shifted or interpreted as provider ids`() {
        val files = listOf(file(5, "first.mkv"), file(9, "second.mkv"))

        for (index in listOf(-1, 2, 9)) {
            assertSelected(null, files, resolve(fileIdx = index))
        }
        assertEquals(5, select(files, resolve(fileIdx = 0)))
        assertEquals(9, select(files, resolve(fileIdx = 1)))
    }

    @Test
    fun `torrent index includes non video entries`() {
        val files = listOf(file(8, "readme.txt"), file(12, "film.mkv"))

        assertEquals(12, select(files, resolve(fileIdx = 1)))
        assertNull(select(files, resolve(fileIdx = 0)))
    }

    @Test
    fun `archive only and empty lists are not playable`() {
        assertSelected(null, emptyList(), resolve())
        assertSelected(null, listOf(file(1, "Show.zip")), resolve())
    }

    private fun assertSelected(
        expected: Int?,
        files: List<TorboxTorrentFileDto>,
        metadata: StreamClientResolve,
        season: Int? = null,
        episode: Int? = null,
    ) {
        for (ordered in listOf(files, files.reversed())) {
            assertEquals(expected, select(ordered, metadata, season, episode))
        }
    }

    private fun select(
        files: List<TorboxTorrentFileDto>,
        metadata: StreamClientResolve,
        season: Int? = null,
        episode: Int? = null,
    ): Int? = when (provider) {
        "torbox" -> TorboxFileSelector().selectFile(files, metadata, season, episode)?.id
        "realdebrid" -> RealDebridFileSelector().selectFile(
            files.map { RealDebridTorrentFileDto(id = it.id, path = it.name, bytes = it.size) },
            metadata, season, episode,
        )?.id
        "premiumize" -> PremiumizeDirectDownloadFileSelector().selectFile(
            files.map { PremiumizeDirectDownloadFileDto(path = it.name, size = it.size, link = "https://example.com/${it.id}") },
            metadata, season, episode,
        )?.link?.substringAfterLast('/')?.toInt()
        else -> error(provider)
    }

    private fun file(id: Int, path: String) = TorboxTorrentFileDto(
        id = id,
        name = path,
        shortName = path.substringAfterLast('/'),
        size = 1000L - id,
    )

    private fun resolve(
        fileIdx: Int? = null,
        filename: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = StreamClientResolve(
        type = "torrent",
        infoHash = "hash",
        fileIdx = fileIdx,
        magnetUri = null,
        sources = null,
        torrentName = null,
        filename = filename,
        mediaType = "series",
        mediaId = null,
        mediaOnlyId = null,
        title = null,
        season = season,
        episode = episode,
        service = provider,
        serviceIndex = null,
        serviceExtension = null,
        isCached = true,
    )

    private fun rawFilename(filename: String) = StreamClientResolveRaw(
        torrentName = null,
        filename = filename,
        size = null,
        folderSize = null,
        tracker = null,
        indexer = null,
        network = null,
        parsed = null,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers() = listOf("torbox", "realdebrid", "premiumize")
    }
}

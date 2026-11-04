package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DirectDebridStreamPreparerTest {
    private val preparer = DirectDebridStreamPreparer(dataStore = mockk(), resolver = mockk())

    @Test
    fun `replaces debrid streams with the same resolve fields in every addon, ignoring case`() {
        val original = debridStream(addonName = "Addon A", infoHash = "abcdef", filename = "Movie.mkv")
        val sameTorrent = debridStream(addonName = "Addon B", infoHash = "ABCDEF", filename = "movie.MKV")
        val otherTorrent = debridStream(addonName = "Addon A", infoHash = "123456", filename = "Movie.mkv")
        val untouchedGroup = AddonStreams("Addon C", null, listOf(otherTorrent.copy(addonName = "Addon C")))
        val prepared = original.copy(url = PREPARED_URL)

        val updated = preparer.replacePreparedStream(
            groups = listOf(
                AddonStreams("Addon A", null, listOf(original, otherTorrent)),
                AddonStreams("Addon B", "logo-b", listOf(sameTorrent.copy(addonLogo = "logo-b"))),
                untouchedGroup
            ),
            original = original,
            prepared = prepared
        )

        assertEquals(PREPARED_URL, updated[0].streams[0].url)
        assertSame(otherTorrent, updated[0].streams[1])
        assertEquals(PREPARED_URL, updated[1].streams[0].url)
        assertEquals("Addon B", updated[1].streams[0].addonName)
        assertEquals("logo-b", updated[1].streams[0].addonLogo)
        assertSame(untouchedGroup, updated[2])
    }

    @Test
    fun `replaces torrent streams without resolve data only when every key field matches`() {
        val original = torrentStream(addonName = "Torrents", infoHash = "abcdef", title = "Movie 1080p")
        val sameIgnoringCase = torrentStream(addonName = "TORRENTS", infoHash = "ABCDEF", title = "movie 1080P")
        val otherTitle = torrentStream(addonName = "Torrents", infoHash = "abcdef", title = "Movie 2160p")
        val otherAddon = torrentStream(addonName = "Other", infoHash = "abcdef", title = "Movie 1080p")
        val prepared = original.copy(url = PREPARED_URL)

        val updated = preparer.replacePreparedStream(
            groups = listOf(AddonStreams("Torrents", null, listOf(original, sameIgnoringCase, otherTitle, otherAddon))),
            original = original,
            prepared = prepared
        ).single().streams

        assertEquals(PREPARED_URL, updated[0].url)
        assertEquals(PREPARED_URL, updated[1].url)
        assertSame(otherTitle, updated[2])
        assertSame(otherAddon, updated[3])
    }

    private fun debridStream(addonName: String, infoHash: String, filename: String): Stream =
        torrentStream(addonName = addonName, infoHash = null, title = "Title").copy(
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = infoHash,
                fileIdx = 0,
                magnetUri = null,
                sources = null,
                torrentName = "Torrent",
                filename = filename,
                mediaType = "movie",
                mediaId = "tt1",
                mediaOnlyId = "tt1",
                title = "Title",
                season = null,
                episode = null,
                service = "torbox",
                serviceIndex = 0,
                serviceExtension = null,
                isCached = true
            )
        )

    private fun torrentStream(addonName: String, infoHash: String?, title: String): Stream = Stream(
        name = "Name",
        title = title,
        description = null,
        url = null,
        ytId = null,
        infoHash = infoHash,
        fileIdx = 1,
        externalUrl = null,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null
    )

    private companion object {
        const val PREPARED_URL = "https://cdn.example/movie.mkv"
    }
}

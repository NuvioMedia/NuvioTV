package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedSourceRefreshCandidateTest {
    private val failedUrl = "https://cdn.example.com/dld/old?token=expired"
    private val expected = CachedSourceIdentity(
        addonName = "Torrents",
        streamName = "4K",
        infoHash = null,
        filename = null,
        bingeGroup = null
    )

    private fun stream(
        name: String = "4K",
        url: String? = null,
        infoHash: String? = null,
        addonName: String = "Torrents",
        filename: String? = null,
        debridResolved: Boolean = false
    ) = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = filename?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = it)
        },
        addonName = addonName,
        addonLogo = null,
        debridResolved = debridResolved
    )

    @Test
    fun torrentThatNeedsDebridResolveIsACandidate() {
        val torrent = stream(infoHash = "abc")

        assertEquals(torrent, findCachedSourceRefreshCandidate(listOf(torrent), failedUrl, expected))
    }

    @Test
    fun preparedDebridStreamWithTheFailedUrlIsStillACandidate() {
        val prepared = stream(url = failedUrl, infoHash = "abc", debridResolved = true)

        assertEquals(prepared, findCachedSourceRefreshCandidate(listOf(prepared), failedUrl, expected))
    }

    @Test
    fun addonStreamWithTheFailedUrlIsSkipped() {
        val same = stream(url = failedUrl)

        assertNull(findCachedSourceRefreshCandidate(listOf(same), failedUrl, expected))
    }

    @Test
    fun sameTorrentWinsOverAnotherStreamWithTheSameName() {
        val other = stream(infoHash = "other")
        val same = stream(name = "4K HDR", infoHash = "abc", filename = "Movie.mkv")

        val candidate = findCachedSourceRefreshCandidate(
            listOf(other, same),
            failedUrl,
            expected.copy(infoHash = "ABC", filename = "Movie.mkv")
        )

        assertEquals(same, candidate)
    }

    @Test
    fun otherAddonIsNeverACandidate() {
        val other = stream(url = "https://other.example/new.mkv", addonName = "Other")

        assertNull(findCachedSourceRefreshCandidate(listOf(other), failedUrl, expected))
    }
}

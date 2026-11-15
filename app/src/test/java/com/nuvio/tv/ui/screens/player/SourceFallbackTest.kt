package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFallbackTest {
    private val settings = PlayerSettings(
        streamAutoPlayFallbackOnFailure = true,
        streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM
    )
    private val candidates = (1..5).map { index -> stream(name = "Source $index", url = "https://example.com/source-$index.mkv") }

    private fun stream(
        name: String = "4K",
        url: String? = null,
        infoHash: String? = null,
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
        behaviorHints = null,
        addonName = "Test",
        addonLogo = null,
        debridResolved = debridResolved
    )

    @Test
    fun resolvedDebridCopyHasTheSameKeyAsTheTorrent() {
        val torrent = stream(infoHash = "ABC")
        val resolved = stream(url = "https://cdn.example.com/dld/file?token=1", infoHash = "abc", debridResolved = true)

        assertEquals(torrent.sourceFallbackKey(), resolved.sourceFallbackKey())
    }

    @Test
    fun differentLinksHaveDifferentKeys() {
        assertNotEquals(
            stream(url = "https://example.com/a.mkv").sourceFallbackKey(),
            stream(url = "https://example.com/b.mkv").sourceFallbackKey()
        )
    }

    @Test
    fun fallbackAppliesOnlyWithAutoSelectionAndTheInternalPlayer() {
        val enabled = PlayerSettings(streamAutoPlayFallbackOnFailure = true, streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)

        assertTrue(enabled.streamAutoPlayFallbackActive)
        assertFalse(enabled.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL).streamAutoPlayFallbackActive)
        assertFalse(enabled.copy(playerPreference = PlayerPreference.EXTERNAL).streamAutoPlayFallbackActive)
        assertFalse(enabled.copy(streamAutoPlayFallbackOnFailure = false).streamAutoPlayFallbackActive)
    }

    @Test
    fun nextCandidateIsTheFirstMatchNotTriedYet() {
        val next = next(candidates, triedKeys = setOf(candidates[0].sourceFallbackKey()))

        assertEquals(candidates[1], next)
    }

    @Test
    fun otherCopiesOfTheFailedTorrentAreSkipped() {
        val sameTorrent = stream(name = "Same", url = "https://example.com/same.mkv", infoHash = HASH)

        assertEquals(candidates[0], next(listOf(sameTorrent, candidates[0]), failedInfoHash = HASH))
    }

    @Test
    fun noCandidateWhenEverySourceWasTried() {
        assertNull(next(candidates, triedKeys = candidates.map { it.sourceFallbackKey() }.toSet()))
    }

    @Test
    fun regexModeOnlyFallsBackToMatchingSources() {
        val regexSettings = settings.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "Source 3")

        assertEquals(candidates[2], next(candidates, settings = regexSettings))
    }

    private fun next(
        streams: List<Stream>,
        settings: PlayerSettings = this.settings,
        triedKeys: Set<String> = emptySet(),
        failedInfoHash: String? = null
    ) = nextSourceFallbackCandidate(
        streams = streams,
        settings = settings,
        installedAddonNames = setOf("Test"),
        triedKeys = triedKeys,
        failedAddonName = "Test",
        failedInfoHash = failedInfoHash
    )

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
    }
}

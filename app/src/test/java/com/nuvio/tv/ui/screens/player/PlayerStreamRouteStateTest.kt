package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerStreamRouteStateTest {

    // Route values as the NavController hands them to the first episode's player.
    private fun firstEpisodeRoute() = SavedStateHandle(
        mapOf(
            "streamUrl" to "https://debrid.example/ep3.mkv",
            "title" to "Show",
            "contentId" to "tt1234567",
            "contentType" to "series",
            "videoId" to "tt1234567:1:3",
            "season" to "1",
            "episode" to "3",
            "episodeTitle" to "Episode%203",
            "bingeGroup" to "addon|1080p",
            "startFromBeginning" to "true",
            "infoHash" to "",
            "fileIdx" to ""
        )
    )

    private fun stream(
        url: String? = "https://debrid.example/ep6.mkv",
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String>? = null
    ) = Stream(
        name = "1080p WEB",
        title = "Show S01E06",
        description = "Size 1.2 GB",
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = "addon|1080p",
            countryWhitelist = null,
            proxyHeaders = null,
            videoHash = "abc123",
            videoSize = 1_288_490_188L,
            filename = "Show S01E06 [1080p] 100%.mkv"
        ),
        addonName = "Addon & Co",
        addonLogo = null,
        sources = sources
    )

    private fun restore(values: Map<String, String>): PlayerNavigationArgs {
        val handle = firstEpisodeRoute()
        values.forEach { (key, value) -> handle[key] = value }
        return PlayerNavigationArgs.from(handle)
    }

    @Test
    fun `restored player opens the episode switched to in place`() {
        val args = restore(
            playerStreamRouteValues(
                stream = stream(),
                streamUrl = "https://debrid.example/ep6.mkv",
                headers = mapOf("Authorization" to "Bearer a+b=="),
                videoId = "tt1234567:1:6",
                season = 1,
                episode = 6,
                episodeTitle = "Six + Seven: 100% Done"
            )
        )

        assertEquals("https://debrid.example/ep6.mkv", args.streamUrl)
        assertEquals("tt1234567:1:6", args.videoId)
        assertEquals(1, args.initialSeason)
        assertEquals(6, args.initialEpisode)
        assertEquals("Six + Seven: 100% Done", args.initialEpisodeTitle)
        assertEquals(
            "Bearer a+b==",
            Json.parseToJsonElement(args.headersJson!!).jsonObject["Authorization"]!!.jsonPrimitive.content
        )
        assertEquals("1080p WEB", args.streamName)
        assertEquals("Show S01E06 [1080p] 100%.mkv", args.filename)
        assertEquals("abc123", args.videoHash)
        assertEquals(1_288_490_188L, args.videoSize)
        assertEquals("Addon & Co", args.addonName)
        assertEquals("Size 1.2 GB", args.streamDescription)
        assertNull(args.infoHash)
        assertNull(args.fileIdx)
        // Content identity comes from the original launch and stays untouched.
        assertEquals("tt1234567", args.contentId)
        assertEquals("Show", args.title)
    }

    @Test
    fun `restored player resumes saved progress even if the first episode started over`() {
        val args = restore(
            playerStreamRouteValues(
                stream = stream(),
                streamUrl = "https://debrid.example/ep6.mkv",
                headers = emptyMap(),
                videoId = "tt1234567:1:6",
                season = 1,
                episode = 6,
                episodeTitle = null
            )
        )

        assertFalse(args.startFromBeginning)
        assertNull(args.headersJson)
        assertNull(args.initialEpisodeTitle)
    }

    @Test
    fun `restored torrent episode keeps the torrent launch form`() {
        val args = restore(
            playerStreamRouteValues(
                stream = stream(
                    url = null,
                    infoHash = "0123456789abcdef0123456789abcdef01234567",
                    fileIdx = 4,
                    sources = listOf("tracker:udp://tracker.example:1337/announce", "dht:0123")
                ),
                streamUrl = "torrent://0123456789abcdef0123456789abcdef01234567",
                headers = emptyMap(),
                videoId = "tt1234567:1:6",
                season = 1,
                episode = 6,
                episodeTitle = null
            )
        )

        assertEquals("torrent://0123456789abcdef0123456789abcdef01234567", args.streamUrl)
        assertEquals("0123456789abcdef0123456789abcdef01234567", args.infoHash)
        assertEquals(4, args.fileIdx)
        assertEquals(
            listOf("tracker:udp://tracker.example:1337/announce", "dht:0123"),
            Json.parseToJsonElement(args.sourcesJson!!).jsonArray.map { it.jsonPrimitive.content }
        )
    }
}

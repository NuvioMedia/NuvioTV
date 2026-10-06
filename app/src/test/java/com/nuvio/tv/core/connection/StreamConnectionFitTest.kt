package com.nuvio.tv.core.connection

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamConnectionFitTest {

    private val gb = 1_000_000_000L

    @Test
    fun `average bitrate comes from size and runtime`() {
        // 9 GB over 120 minutes = 10 Mbps.
        assertEquals(10.0, stream(sizeBytes = 9 * gb).averageBitrateMbps(runtimeMinutes = 120)!!, 0.001)
    }

    @Test
    fun `average bitrate prefers the resolved file size`() {
        val stream = stream(sizeBytes = 90 * gb).copy(clientResolve = resolved(size = 9 * gb, folderSize = 90 * gb))

        assertEquals(10.0, stream.averageBitrateMbps(runtimeMinutes = 120)!!, 0.001)
    }

    @Test
    fun `average bitrate is unknown when data is missing or implausible`() {
        assertNull(stream(sizeBytes = null).averageBitrateMbps(runtimeMinutes = 120))
        assertNull(stream(sizeBytes = 9 * gb).averageBitrateMbps(runtimeMinutes = 0))
        assertNull(stream(sizeBytes = 10_000_000).averageBitrateMbps(runtimeMinutes = 120))
        // A 400 GB "episode" is a mislabeled pack, not a 1000+ Mbps stream.
        assertNull(stream(sizeBytes = 400 * gb).averageBitrateMbps(runtimeMinutes = 45))
    }

    @Test
    fun `addon order is kept when every stream fits`() {
        // ~95 Mbps connection, 120 min movie: nothing is too heavy, so nothing moves.
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 95.0)
        val streams = listOf(
            stream(name = "1gb", sizeBytes = 1 * gb),
            stream(name = "unknown", sizeBytes = null),
            stream(name = "5gb", sizeBytes = 5 * gb),
        )

        assertSame(streams, fit.apply(streams))
    }

    @Test
    fun `only heavy streams move, and both parts keep their order`() {
        // 30 Mbps connection, 120 min runtime: anything above 20 Mbps average is demoted.
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 30.0)
        val remux = stream(name = "remux", sizeBytes = 60 * gb) // 66.7 Mbps
        val uhd = stream(name = "uhd", sizeBytes = 20 * gb) // 22.2 Mbps
        val hd = stream(name = "hd", sizeBytes = 9 * gb) // 10 Mbps
        val unknown = stream(name = "unknown", sizeBytes = null)
        val small = stream(name = "small", sizeBytes = 2 * gb)

        val ordered = fit.apply(listOf(remux, hd, uhd, unknown, small))

        assertEquals(listOf("hd", "unknown", "small", "remux", "uhd"), ordered.map { it.name })
    }

    @Test
    fun `stream exactly at the headroom limit still fits`() {
        // 9 GB over 120 min = 10 Mbps; 10 x 1.5 = 15 Mbps.
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 15.0)
        val streams = listOf(stream(name = "edge", sizeBytes = 9 * gb), stream(name = "small", sizeBytes = 1 * gb))

        assertSame(streams, fit.apply(streams))
    }

    @Test
    fun `order is untouched when heavy streams are already last or everything is heavy`() {
        val streams = listOf(stream(name = "a", sizeBytes = 9 * gb), stream(name = "b", sizeBytes = 40 * gb))

        assertSame(streams, StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 20.0).apply(streams))
        assertSame(streams, StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 2.0).apply(streams))
    }

    @Test
    fun `partition is idempotent and keeps duplicates`() {
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 30.0)
        val heavy = stream(name = "heavy", sizeBytes = 60 * gb)
        val light = stream(name = "light", sizeBytes = 2 * gb)

        val once = fit.apply(listOf(heavy, light, heavy, light))

        assertEquals(listOf("light", "light", "heavy", "heavy"), once.map { it.name })
        assertSame(once, fit.apply(once))
    }

    @Test
    fun `plugin merges give the same result whether or not the list was already ordered`() {
        // Plugin completions merge new results into the displayed list, then sort by label and
        // partition again. A stable partition makes that equal to partitioning the raw merge.
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 30.0)
        val first = listOf(
            stream(name = "a-heavy", sizeBytes = 60 * gb),
            stream(name = "b-light", sizeBytes = 2 * gb),
        )
        val second = listOf(
            stream(name = "a-light", sizeBytes = 1 * gb),
            stream(name = "b-heavy", sizeBytes = 50 * gb),
        )
        val byName = compareBy<Stream> { it.name }

        val incremental = fit.apply((fit.apply(first) + second).sortedWith(byName))
        val direct = fit.apply((first + second).sortedWith(byName))

        assertEquals(direct.map { it.name }, incremental.map { it.name })
    }

    @Test
    fun `groups are ordered independently and unchanged groups are kept`() {
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 30.0)
        val unchanged = AddonStreams("A", null, listOf(stream(name = "a", sizeBytes = 9 * gb)))
        val reordered = AddonStreams(
            "B",
            null,
            listOf(stream(name = "remux", sizeBytes = 60 * gb), stream(name = "hd", sizeBytes = 9 * gb))
        )

        val ordered = fit.applyToGroups(listOf(unchanged, reordered))

        assertSame(unchanged, ordered[0])
        assertEquals(listOf("hd", "remux"), ordered[1].streams.map { it.name })
    }

    @Test
    fun `group list is returned as is when nothing moves`() {
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 100.0)
        val groups = listOf(
            AddonStreams("A", null, listOf(stream(name = "a", sizeBytes = 9 * gb), stream(name = "b", sizeBytes = 4 * gb))),
            AddonStreams("B", null, listOf(stream(name = "c", sizeBytes = 2 * gb))),
        )

        assertSame(groups, fit.applyToGroups(groups))
    }

    private fun stream(name: String = "stream", sizeBytes: Long?) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://cdn.example.com/$name.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeBytes,
        ),
        addonName = "Addon",
        addonLogo = null,
    )

    private fun resolved(size: Long, folderSize: Long) = StreamClientResolve(
        type = null, infoHash = null, fileIdx = null, magnetUri = null, sources = null,
        torrentName = null, filename = null, mediaType = null, mediaId = null, mediaOnlyId = null,
        title = null, season = null, episode = null, service = null, serviceIndex = null,
        serviceExtension = null, isCached = null,
        stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(
                torrentName = null, filename = null, size = size, folderSize = folderSize,
                tracker = null, indexer = null, network = null, parsed = null
            )
        )
    )
}

package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.runtime.DeviceVideoCodecCapabilities
import com.nuvio.tv.core.runtime.DeviceVideoDecoderCapability
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorTest {

    private fun choose(scored: List<Stream>, mode: StreamAutoPlayMode) = StreamAutoPlaySelector.selectAutoPlayStream(
        scored, mode, "", StreamAutoPlaySource.ALL_SOURCES, setOf("AddonA"), emptySet(), emptySet()
    )

    @Test fun `quality and balanced choose different sources independent of provider order`() {
        val hd = stream("AddonA", url = "https://example.com/hd").copy(qualityValue = 1080)
        val uhd = hd.copy(url = "https://example.com/uhd", qualityValue = 2160)
        for (sources in listOf(listOf(hd, uhd), listOf(uhd, hd))) {
            assertEquals(uhd, choose(sources, StreamAutoPlayMode.BEST_QUALITY))
            assertEquals(hd, choose(sources, StreamAutoPlayMode.BALANCED))
        }
    }

    @Test fun `best quality avoids a declared codec and resolution absent from device decoders`() {
        val av1FourK = withParsedVideo("AV1", "2160p")
        val hevc1080 = withParsedVideo("HEVC", "1080p")
        val codecs = DeviceVideoCodecCapabilities(
            decoders = listOf(
                DeviceVideoDecoderCapability("video/hevc", 3840, 2160, true),
                DeviceVideoDecoderCapability("video/avc", 1920, 1080, true)
            )
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(av1FourK, hevc1080),
            mode = StreamAutoPlayMode.BEST_QUALITY,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            deviceVideoCodecs = codecs
        )

        assertEquals(hevc1080, selected)
        val incompatibleScore = StreamAutoPlaySelector.scoreSource(av1FourK, StreamAutoPlayMode.BEST_QUALITY, codecs)
        assertEquals(StreamAutoPlaySelector.DecoderSupport.UNSUPPORTED, incompatibleScore.decoderSupport)
        assertEquals(-600, incompatibleScore.decoderCompatibilityPoints)
        assertEquals("video/av01", incompatibleScore.declaredCodecMime)
    }

    @Test fun `known codec without matching decoder gets a soft penalty but remains a fallback`() {
        val unsupported = withParsedVideo("H.264", "1080p")
        val onlyCandidate = listOf(unsupported)
        val codecs = DeviceVideoCodecCapabilities(decoders = emptyList())

        assertEquals(
            unsupported,
            StreamAutoPlaySelector.selectAutoPlayStream(
                streams = onlyCandidate,
                mode = StreamAutoPlayMode.BALANCED,
                regexPattern = "",
                source = StreamAutoPlaySource.ALL_SOURCES,
                installedAddonNames = setOf("AddonA"),
                selectedAddons = emptySet(),
                selectedPlugins = emptySet(),
                deviceVideoCodecs = codecs
            )
        )
        assertEquals(-600, StreamAutoPlaySelector.scoreSource(unsupported, StreamAutoPlayMode.BALANCED, codecs).decoderCompatibilityPoints)
    }

    @Test fun `unknown codec or failed device query stays neutral`() {
        val unknownCodec = withParsedVideo("VVC", "2160p")
        val unknownInventory = DeviceVideoCodecCapabilities(decoders = null)

        assertEquals(null, StreamAutoPlaySelector.declaredVideoMime("VVC"))
        assertEquals(
            StreamAutoPlaySelector.DecoderSupport.UNKNOWN,
            StreamAutoPlaySelector.scoreSource(unknownCodec, StreamAutoPlayMode.BEST_QUALITY, unknownInventory).decoderSupport
        )
        assertEquals(0, StreamAutoPlaySelector.scoreSource(unknownCodec, StreamAutoPlayMode.BEST_QUALITY, unknownInventory).decoderCompatibilityPoints)
        assertEquals(StreamAutoPlaySelector.DecoderSupport.UNKNOWN, StreamAutoPlaySelector.decoderSupport(null, 2160, unknownInventory))
    }

    @Test fun `codec MIME with insufficient advertised dimensions is unsupported`() {
        val tooLarge = DeviceVideoCodecCapabilities(
            decoders = listOf(DeviceVideoDecoderCapability("video/hevc", 1920, 1080, false))
        )
        assertEquals(
            StreamAutoPlaySelector.DecoderSupport.UNSUPPORTED,
            StreamAutoPlaySelector.decoderSupport("video/hevc", 2160, tooLarge)
        )
        assertEquals(
            StreamAutoPlaySelector.DecoderSupport.SOFTWARE_ONLY,
            StreamAutoPlaySelector.decoderSupport("video/hevc", null, tooLarge)
        )
    }

    @Test fun `balanced mode explains software-only decoder while best quality keeps resolution priority`() {
        val hevc4k = withParsedVideo("HEVC", "2160p")
        val softwareOnly = DeviceVideoCodecCapabilities(
            decoders = listOf(DeviceVideoDecoderCapability("video/hevc", 3840, 2160, false))
        )

        val balanced = StreamAutoPlaySelector.scoreSource(hevc4k, StreamAutoPlayMode.BALANCED, softwareOnly)
        val bestQuality = StreamAutoPlaySelector.scoreSource(hevc4k, StreamAutoPlayMode.BEST_QUALITY, softwareOnly)

        assertEquals(StreamAutoPlaySelector.DecoderSupport.SOFTWARE_ONLY, balanced.decoderSupport)
        assertEquals(-160, balanced.decoderCompatibilityPoints)
        assertEquals(0, bestQuality.decoderCompatibilityPoints)
    }

    @Test fun `codec aliases map to Android video MIME types`() {
        assertEquals("video/avc", StreamAutoPlaySelector.declaredVideoMime("H.264 / AVC"))
        assertEquals("video/hevc", StreamAutoPlaySelector.declaredVideoMime("x265 / H.265"))
        assertEquals("video/av01", StreamAutoPlaySelector.declaredVideoMime("av01"))
        assertEquals("video/x-vnd.on2.vp9", StreamAutoPlaySelector.declaredVideoMime("VP9"))
        assertEquals("video/dolby-vision", StreamAutoPlaySelector.declaredVideoMime("Dolby Vision"))
    }

    @Test fun `data saver prefers smaller known files without rewarding unknown size`() {
        val small = stream("AddonA", url = "https://example.com/small").copy(qualityValue = 720,
            behaviorHints = StreamBehaviorHints(null, null, null, null, videoSize = 512L * 1024 * 1024))
        val large = small.copy(url = "https://example.com/large", behaviorHints = small.behaviorHints?.copy(videoSize = 8L * 1024 * 1024 * 1024))
        val unknown = small.copy(url = "https://example.com/unknown", behaviorHints = null)
        assertEquals(small, choose(listOf(unknown, large, small), StreamAutoPlayMode.DATA_SAVER))
    }

    @Test fun `scoring never makes external or uncached sources autoplay candidates`() {
        val available = stream("AddonA", url = "https://example.com/available").copy(qualityValue = 720)
        val external = stream("AddonA", externalUrl = "https://example.com/error").copy(qualityValue = 4320)
        val unavailable = stream("AddonA", infoHash = "hash", cacheState = StreamDebridCacheState.NOT_CACHED).copy(qualityValue = 4320)
        assertEquals(available, choose(listOf(external, unavailable, available), StreamAutoPlayMode.BEST_QUALITY))
    }

    @Test fun `unknown declarations remain neutral and ties preserve provider order`() {
        val first = stream("AddonA", url = "https://example.com/one?token=private", name = "4K HDR HEVC fast")
        val second = first.copy(url = "https://example.com/two")
        val score = StreamAutoPlaySelector.scoreSource(first, StreamAutoPlayMode.BEST_QUALITY)
        assertEquals(0, score.total)
        assertNull(score.declaredHeight)
        assertNull(score.declaredBytes)
        assertEquals(first, choose(listOf(first, second), StreamAutoPlayMode.BEST_QUALITY))
        assertEquals(second, choose(listOf(second, first), StreamAutoPlayMode.BEST_QUALITY))
        assertNull(choose(listOf(first, second), StreamAutoPlayMode.MANUAL))
    }

    @Test fun `smart selection still respects selected addon filters`() {
        val excluded = stream("Other", url = "https://example.com/excluded").copy(qualityValue = 2160)
        val allowed = stream("AddonA", url = "https://example.com/allowed").copy(qualityValue = 720)
        val chosen = StreamAutoPlaySelector.selectAutoPlayStream(listOf(excluded, allowed),
            StreamAutoPlayMode.BEST_QUALITY, "", StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            setOf("AddonA"), setOf("AddonA"), emptySet())
        assertEquals(allowed, chosen)
    }

    @Test fun `oversized and invalid declarations cannot overflow scores`() {
        val source = stream("AddonA", url = "https://example.com/a").copy(qualityValue = Int.MAX_VALUE,
            behaviorHints = StreamBehaviorHints(null, null, null, null, videoSize = Long.MAX_VALUE))
        val score = StreamAutoPlaySelector.scoreSource(source, StreamAutoPlayMode.DATA_SAVER)
        assertEquals(0, score.total)
        assertNull(score.declaredHeight)
    }

    @Test
    fun `orderAddonStreams follows installed addon order and leaves plugins last`() {
        val plugin = addonStreams("Plugin")
        val addonB = addonStreams("AddonB")
        val addonA = addonStreams("AddonA")
        val unknown = addonStreams("UnknownPlugin")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, addonB, addonA, unknown),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(addonA, addonB, plugin, unknown), ordered)
    }

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group"
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        if (AppFeaturePolicy.pluginsEnabled) {
            assertEquals(allowedPluginMatch, selected)
        } else {
            assertEquals(filteredOutAddonMatch, selected)
        }
    }

    @Test
    fun `regex mode still works when bingeGroup missing or no match`() {
        val nonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/a.m3u8",
            name = "720p"
        )
        val regexMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/b.m3u8",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(nonMatch, regexMatch),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "unmatched-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(regexMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode auto-selects matching bingeGroup when prefer enabled`() {
        // Binge-group continuity is intentionally allowed in MANUAL mode so
        // next-episode / resume can skip the picker when a group was locked in.
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(matched, selected)
    }

    @Test
    fun `first stream skips checking and not cached local debrid streams`() {
        val checking = stream(
            addonName = "AddonA",
            name = "Checking",
            infoHash = "abc123",
            cacheState = StreamDebridCacheState.CHECKING
        )
        val notCached = stream(
            addonName = "AddonA",
            name = "Not cached",
            infoHash = "def456",
            cacheState = StreamDebridCacheState.NOT_CACHED
        )
        val unknown = stream(
            addonName = "AddonA",
            name = "Unknown",
            infoHash = "unknown",
            cacheState = StreamDebridCacheState.UNKNOWN
        )
        val cached = stream(
            addonName = "AddonA",
            name = "Cached",
            infoHash = "ghi789",
            cacheState = StreamDebridCacheState.CACHED
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(checking, notCached, unknown, cached),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(cached, selected)
    }

    @Test
    fun `first stream selects a stream that only has a YouTube id`() {
        // The two streams an addon such as Newsio returns for a YouTube video: the bare ytId,
        // then the watch page as an external link.
        val youTube = stream(addonName = "AddonA", name = "Play video in app", ytId = "dQw4w9WgXcQ")
        val watchPage = stream(
            addonName = "AddonA",
            name = "Open in YouTube app",
            externalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(youTube, watchPage),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            assertEquals(youTube, selected)
        } else {
            // Without in-app playback the video would open externally, like the watch page.
            assertNull(selected)
        }
    }

    @Test
    fun `first stream skips a blank YouTube id`() {
        val blank = stream(addonName = "AddonA", name = "Blank", ytId = "  ")
        val direct = stream(addonName = "AddonA", url = "https://example.com/direct.m3u8")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(blank, direct),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(direct, selected)
    }

    @Test
    fun `regex mode selects a matching stream that only has a YouTube id`() {
        val direct = stream(addonName = "AddonA", url = "https://example.com/direct.m3u8", name = "Mirror")
        val youTube = stream(addonName = "AddonA", name = "YouTube", ytId = "dQw4w9WgXcQ")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(direct, youTube),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "youtube",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            assertEquals(youTube, selected)
        } else {
            assertNull(selected)
        }
    }

    @Test
    fun `orderAddonStreams keeps cached local torrent groups in installed addon order`() {
        val regular = addonStreams(
            "AddonA",
            stream(
                addonName = "AddonA",
                url = "https://example.com/regular.m3u8"
            )
        )
        val cachedDebrid = addonStreams(
            "AddonB",
            stream(
                addonName = "AddonB",
                infoHash = "abc123",
                cacheState = StreamDebridCacheState.CACHED
            )
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(regular, cachedDebrid),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(regular, cachedDebrid), ordered)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        ytId: String? = null,
        externalUrl: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null,
        debridCacheStatus = cacheState?.let {
            StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = it
            )
        }
    )

    private fun withParsedVideo(codec: String?, resolution: String?): Stream = stream(
        addonName = "AddonA",
        url = "https://example.com/${codec.orEmpty()}-${resolution.orEmpty()}"
    ).copy(
        clientResolve = StreamClientResolve(
            type = null, infoHash = null, fileIdx = null, magnetUri = null, sources = null,
            torrentName = null, filename = null, mediaType = null, mediaId = null, mediaOnlyId = null,
            title = null, season = null, episode = null, service = null, serviceIndex = null,
            serviceExtension = null, isCached = null,
            stream = StreamClientResolveStream(
                StreamClientResolveRaw(
                    torrentName = null, filename = null, size = null, folderSize = null,
                    tracker = null, indexer = null, network = null,
                    parsed = StreamClientResolveParsed(
                        rawTitle = null, parsedTitle = null, year = null, resolution = resolution,
                        seasons = null, episodes = null, quality = null, hdr = null, codec = codec,
                        audio = null, channels = null, languages = null, group = null, network = null,
                        edition = null, duration = null, bitDepth = null, extended = null,
                        theatrical = null, remastered = null, unrated = null
                    )
                )
            )
        )
    )

    private fun addonStreams(
        addonName: String,
        vararg streams: Stream
    ): AddonStreams = AddonStreams(
        addonName = addonName,
        addonLogo = null,
        streams = streams.toList()
    )
}

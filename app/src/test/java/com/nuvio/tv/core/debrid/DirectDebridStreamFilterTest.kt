package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamSortMode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDebridStreamFilterTest {
    @Test
    fun `keeps only cached supported debrid streams and labels source as instant`() {
        val cachedTorbox = stream(
            name = "Direct 1080p",
            resolve = resolve(type = "debrid", service = "torbox", isCached = true)
        )
        val uncachedTorbox = stream(
            resolve = resolve(type = "debrid", service = "torbox", isCached = false)
        )
        val genericTorrent = stream(
            resolve = resolve(type = "torrent", service = null, isCached = null)
        )
        val unsupportedDebrid = stream(
            resolve = resolve(type = "debrid", service = "futurebox", isCached = true)
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(cachedTorbox, uncachedTorbox, genericTorrent, unsupportedDebrid)
        )

        assertEquals(1, result.size)
        assertEquals("Direct 1080p", result.single().name)
        assertEquals("Torbox Instant", result.single().addonName)
        assertTrue(result.single().isDirectDebrid())
        assertFalse(result.single().isTorrent())
    }

    @Test
    fun `uses provider instant name when source stream has no name`() {
        val result = DirectDebridStreamFilter.filterInstant(
            listOf(stream(name = null, resolve = resolve(type = "debrid", service = "realdebrid", isCached = true)))
        )

        assertEquals("Real-Debrid Instant", result.single().name)
    }

    @Test
    fun `preserves original order by default`() {
        val low = stream(name = "Low", resolve = resolve(resolution = "720p", size = 4))
        val large = stream(name = "Large", resolve = resolve(resolution = "2160p", size = 40))
        val mid = stream(name = "Mid", resolve = resolve(resolution = "1080p", size = 10))

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(low, large, mid),
            DebridSettings()
        )

        assertEquals(listOf("Low", "Large", "Mid"), result.map { it.name })
    }

    @Test
    fun `limits and sorts streams by quality and size`() {
        val streams = listOf(
            stream(resolve = resolve(resolution = "1080p", size = 20)),
            stream(resolve = resolve(resolution = "2160p", size = 10)),
            stream(resolve = resolve(resolution = "2160p", size = 30)),
            stream(resolve = resolve(resolution = "720p", size = 40))
        )

        val result = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC
            )
        )

        assertEquals(listOf(30L, 10L), result.map { it.clientResolve?.stream?.raw?.size })
    }

    @Test
    fun `filters minimum quality dv hdr and codec`() {
        val hdrHevc = stream(
            resolve = resolve(
                resolution = "2160p",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                size = 10
            )
        )
        val dvHevc = stream(
            resolve = resolve(
                resolution = "2160p",
                hdr = listOf("DV", "HDR10"),
                codec = "HEVC",
                size = 20
            )
        )
        val sdrAvc = stream(
            resolve = resolve(
                resolution = "1080p",
                hdr = emptyList(),
                codec = "AVC",
                size = 30
            )
        )
        val hdHevc = stream(
            resolve = resolve(
                resolution = "720p",
                hdr = emptyList(),
                codec = "HEVC",
                size = 40
            )
        )

        val noDvHdrHevc4k = DirectDebridStreamFilter.filterInstant(
            listOf(hdrHevc, dvHevc, sdrAvc, hdHevc),
            DebridSettings(
                streamMinimumQuality = DebridStreamMinimumQuality.P2160,
                streamDolbyVisionFilter = DebridStreamFeatureFilter.EXCLUDE,
                streamHdrFilter = DebridStreamFeatureFilter.ONLY,
                streamCodecFilter = DebridStreamCodecFilter.HEVC
            )
        )

        assertEquals(listOf(10L), noDvHdrHevc4k.map { it.clientResolve?.stream?.raw?.size })

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            listOf(hdrHevc, dvHevc, sdrAvc, hdHevc),
            DebridSettings(streamDolbyVisionFilter = DebridStreamFeatureFilter.ONLY)
        )

        assertEquals(listOf(20L), dvOnly.map { it.clientResolve?.stream?.raw?.size })
    }

    @Test
    fun `filters provided torbox response through dto mapper path`() {
        val response = Moshi.Builder()
            .build()
            .adapter(StreamResponseDto::class.java)
            .fromJson(torboxMegamindResponse)
            ?: error("response not parsed")
        val streams = response.streams.orEmpty()
            .map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }

        assertEquals(7, streams.size)
        assertEquals(7, DirectDebridStreamFilter.filterInstant(streams, DebridSettings()).size)

        val sizeDesc = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamMaxResults = 3,
                streamSortMode = DebridStreamSortMode.SIZE_DESC
            )
        )

        assertEquals(listOf(24_201_782_274L, 22_719_933_168L, 4_571_523_542L), sizeDesc.map { it.streamSize() })

        val qualityTopTwo = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC
            )
        )

        assertEquals(listOf("2160p", "1080p"), qualityTopTwo.map { it.clientResolve?.stream?.raw?.parsed?.resolution })
        assertEquals(4_571_523_542L, qualityTopTwo.first().streamSize())

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamDolbyVisionFilter = DebridStreamFeatureFilter.ONLY)
        )

        assertEquals(listOf(4_571_523_542L), dvOnly.map { it.streamSize() })

        val noDv = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamDolbyVisionFilter = DebridStreamFeatureFilter.EXCLUDE)
        )

        assertEquals(6, noDv.size)
        assertTrue(noDv.none { it.description.orEmpty().contains("DV", ignoreCase = true) })

        val hdrOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamHdrFilter = DebridStreamFeatureFilter.ONLY)
        )

        assertEquals(listOf(4_571_523_542L), hdrOnly.map { it.streamSize() })

        val noHdr = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamHdrFilter = DebridStreamFeatureFilter.EXCLUDE)
        )

        assertEquals(6, noHdr.size)

        val hevcOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamCodecFilter = DebridStreamCodecFilter.HEVC)
        )

        assertEquals(listOf(4_571_523_542L, 3_859_136_613L, 2_946_516_232L), hevcOnly.map { it.streamSize() })

        val avcOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamCodecFilter = DebridStreamCodecFilter.H264)
        )

        assertEquals(4, avcOnly.size)

        val minimum1080 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamMinimumQuality = DebridStreamMinimumQuality.P1080)
        )

        assertEquals(6, minimum1080.size)

        val minimum2160 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamMinimumQuality = DebridStreamMinimumQuality.P2160)
        )

        assertEquals(listOf(4_571_523_542L), minimum2160.map { it.streamSize() })
    }

    @Test
    fun `applies default stream preferences`() {
        val remuxAtmos = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "BluRay REMUX",
                hdr = listOf("HDR10"),
                codec = "HEVC",
                audio = listOf("Atmos", "TrueHD"),
                channels = listOf("7.1"),
                languages = listOf("en"),
                group = "GOOD",
                size = 40_000_000_000
            )
        )
        val webAac = stream(
            resolve = resolve(
                resolution = "2160p",
                quality = "WEB-DL",
                codec = "AVC",
                audio = listOf("AAC"),
                channels = listOf("2.0"),
                languages = listOf("en"),
                group = "NOPE",
                size = 4_000_000_000
            )
        )
        val blurayDts = stream(
            resolve = resolve(
                resolution = "1080p",
                quality = "BluRay",
                codec = "AVC",
                audio = listOf("DTS"),
                channels = listOf("5.1"),
                languages = listOf("hi"),
                group = "GOOD",
                size = 12_000_000_000
            )
        )
        val preferences = DebridStreamPreferences(
            maxResults = 2,
            maxPerResolution = 1,
            sizeMinGb = 5,
            requiredResolutions = listOf(DebridStreamResolution.P2160, DebridStreamResolution.P1080),
            excludedQualities = listOf(DebridStreamQuality.WEB_DL),
            requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_7_1, DebridStreamAudioChannel.CH_5_1),
            excludedEncodes = listOf(DebridStreamEncode.UNKNOWN),
            excludedLanguages = listOf(DebridStreamLanguage.IT),
            requiredReleaseGroups = listOf("GOOD"),
            sortCriteria = listOf(
                DebridStreamSortCriterion(DebridStreamSortKey.AUDIO_TAG, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC)
            )
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(webAac, blurayDts, remuxAtmos),
            DebridSettings(streamPreferences = preferences)
        )

        assertEquals(listOf(40_000_000_000L, 12_000_000_000L), result.map { it.streamSize() })

        val noHdr = DirectDebridStreamFilter.filterInstant(
            listOf(webAac, blurayDts, remuxAtmos),
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    requiredVisualTags = listOf(DebridStreamVisualTag.HDR_ONLY),
                    requiredAudioTags = listOf(DebridStreamAudioTag.ATMOS)
                )
            )
        )

        assertEquals(listOf(40_000_000_000L), noHdr.map { it.streamSize() })
    }

    @Test
    fun `applies every stream preference category to provided oppenheimer response`() {
        val streams = oppenheimerStreams()

        assertEquals(22, streams.size)
        assertEquals(22, DirectDebridStreamFilter.filterInstant(streams, DebridSettings()).size)

        val sizeDesc = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    maxResults = 3,
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(listOf(104_700_022_239L, 96_867_354_460L, 92_910_562_472L), sizeDesc.map { it.streamSize() })

        val perResolution = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    maxPerResolution = 1,
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(
            listOf(104_700_022_239L, 51_056_367_324L, 14_559_208_901L, 13_916_786_260L, 5_371_431_742L, 1_468_475_610L),
            perResolution.map { it.streamSize() }
        )

        val sizeRange = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(sizeMinGb = 10, sizeMaxGb = 20))
        )

        assertEquals(listOf(14_559_208_901L, 13_916_786_260L), sizeRange.map { it.streamSize() })

        val required1440 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredResolutions = listOf(DebridStreamResolution.P1440)))
        )

        assertEquals(listOf(14_559_208_901L, 4_622_252_199L), required1440.map { it.streamSize() })

        val no720 = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(excludedResolutions = listOf(DebridStreamResolution.P720)))
        )

        assertEquals(17, no720.size)
        assertTrue(no720.none { it.description.orEmpty().contains("720p", ignoreCase = true) })

        val webDlOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredQualities = listOf(DebridStreamQuality.WEB_DL)))
        )

        assertEquals(listOf(13_916_786_260L), webDlOnly.map { it.streamSize() })

        val noWebDl = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(excludedQualities = listOf(DebridStreamQuality.WEB_DL)))
        )

        assertEquals(21, noWebDl.size)

        val hdrDvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredVisualTags = listOf(DebridStreamVisualTag.HDR_DV)))
        )

        assertEquals(listOf(92_910_562_472L, 96_867_354_460L, 87_052_038_851L, 89_079_717_868L), hdrDvOnly.map { it.streamSize() })

        val dvOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredVisualTags = listOf(DebridStreamVisualTag.DV_ONLY)))
        )

        assertEquals(listOf(89_100_999_892L), dvOnly.map { it.streamSize() })

        val hdrOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredVisualTags = listOf(DebridStreamVisualTag.HDR_ONLY)))
        )

        assertEquals(listOf(14_559_208_901L, 4_622_252_199L), hdrOnly.map { it.streamSize() })

        val noImax = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(excludedVisualTags = listOf(DebridStreamVisualTag.IMAX)))
        )

        assertEquals(18, noImax.size)

        val ddpOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredAudioTags = listOf(DebridStreamAudioTag.DD_PLUS)))
        )

        assertEquals(listOf(92_910_562_472L, 87_052_038_851L, 13_916_786_260L), ddpOnly.map { it.streamSize() })

        val fiveOneOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredAudioChannels = listOf(DebridStreamAudioChannel.CH_5_1)))
        )

        assertTrue(fiveOneOnly.map { it.streamSize() }.contains(1_112_357_595L))

        val av1Only = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredEncodes = listOf(DebridStreamEncode.AV1)))
        )

        assertEquals(listOf(14_559_208_901L), av1Only.map { it.streamSize() })

        val xvidOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredEncodes = listOf(DebridStreamEncode.XVID)))
        )

        assertEquals(listOf(1_468_475_610L), xvidOnly.map { it.streamSize() })

        val polishOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredLanguages = listOf(DebridStreamLanguage.PL)))
        )

        assertEquals(listOf(96_867_354_460L, 51_056_367_324L, 48_286_797_994L, 1_468_475_610L), polishOnly.map { it.streamSize() })

        val latinoOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredLanguages = listOf(DebridStreamLanguage.LA)))
        )

        assertEquals(listOf(92_910_562_472L, 87_052_038_851L), latinoOnly.map { it.streamSize() })

        val sgfOnly = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(requiredReleaseGroups = listOf("SGF")))
        )

        assertEquals(listOf(96_867_354_460L, 51_056_367_324L), sgfOnly.map { it.streamSize() })

        val noSgf = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(streamPreferences = DebridStreamPreferences(excludedReleaseGroups = listOf("SGF")))
        )

        assertEquals(20, noSgf.size)

        val latinoFirst = DirectDebridStreamFilter.filterInstant(
            streams,
            DebridSettings(
                streamPreferences = DebridStreamPreferences(
                    maxResults = 2,
                    preferredLanguages = listOf(DebridStreamLanguage.LA),
                    sortCriteria = listOf(DebridStreamSortCriterion(DebridStreamSortKey.LANGUAGE, DebridStreamSortDirection.DESC))
                )
            )
        )

        assertEquals(listOf(92_910_562_472L, 87_052_038_851L), latinoFirst.map { it.streamSize() })
    }

    private fun Stream.streamSize(): Long? = clientResolve?.stream?.raw?.size ?: behaviorHints?.videoSize

    private fun oppenheimerStreams(): List<Stream> {
        val response = Moshi.Builder()
            .build()
            .adapter(StreamResponseDto::class.java)
            .fromJson(oppenheimerResponse)
            ?: error("response not parsed")
        return response.streams.orEmpty()
            .map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }
    }

    private fun stream(
        name: String? = "Stream",
        resolve: StreamClientResolve?
    ): Stream = Stream(
        name = name,
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Direct Debrid",
        addonLogo = null,
        clientResolve = resolve
    )

    private fun resolve(
        type: String? = "debrid",
        service: String? = "torbox",
        isCached: Boolean? = true,
        resolution: String? = null,
        quality: String? = null,
        hdr: List<String> = emptyList(),
        codec: String? = null,
        audio: List<String>? = null,
        channels: List<String>? = null,
        languages: List<String>? = null,
        group: String? = null,
        size: Long? = null
    ): StreamClientResolve = StreamClientResolve(
        type = type,
        infoHash = "abc${size ?: ""}",
        fileIdx = size?.toInt() ?: 1,
        magnetUri = "magnet:?xt=urn:btih:abc",
        sources = null,
        torrentName = "Torrent",
        filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
        mediaType = "movie",
        mediaId = "tt1",
        mediaOnlyId = "tt1",
        title = "Title",
        season = null,
        episode = null,
        service = service,
        serviceIndex = 0,
        serviceExtension = null,
        isCached = isCached,
        stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(
                torrentName = "Torrent ${resolution.orEmpty()} ${quality.orEmpty()}",
                filename = "video ${resolution.orEmpty()} ${quality.orEmpty()} ${codec.orEmpty()}.mkv",
                size = size,
                folderSize = size,
                tracker = null,
                indexer = null,
                network = null,
                parsed = StreamClientResolveParsed(
                    rawTitle = null,
                    parsedTitle = null,
                    year = null,
                    resolution = resolution,
                    seasons = null,
                    episodes = null,
                    quality = quality,
                    hdr = hdr,
                    codec = codec,
                    audio = audio,
                    channels = channels,
                    languages = languages,
                    group = group,
                    network = null,
                    edition = null,
                    duration = null,
                    bitDepth = null,
                    extended = null,
                    theatrical = null,
                    remastered = null,
                    unrated = null
                )
            )
        )
    )

    private val oppenheimerResponse = """
        {
          "streams": [
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"b12e2ad004742ae1348e4e22eebe8fcac07c88d7","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"REMUX","codec":"hevc","audio":["DTS Lossless","Dolby Digital Plus"],"channels":["5.1"],"hdr":["DV","HDR10+"],"languages":["multi","en","la","it"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DTS-HD.Master.DDP5.1.DV.x265.mkv","videoSize":92910562472}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ce1bdd5cf0fa18def9264ecfae3815ed5eba61bc","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","audio":["DTS Lossless"],"hdr":["DV","HDR"],"languages":["multi","en","fr","es","it","de","pl","cs"],"group":"SGF","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.2160p.BluRay.Hybrid.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv","videoSize":96867354460}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"e977543316017189e6ea8d6e729d1fd6d7b04bc9","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","title":"Oppenheimer","torrentName":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"REMUX","codec":"hevc","audio":["Dolby Digital Plus"],"channels":["5.1"],"hdr":["DV","HDR10+"],"languages":["multi","en","la","it"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.2160p.REMUX.IMAX.Dolby.Vision.And.HDR10.PLUS.ENG.ITA.LATINO.DDP5.1.DV.x265.MP4-BEN.THE.MEN.mp4","videoSize":87052038851}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"972572ea1d90a6cf8a488fb38de4e439ef20c38a","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","hdr":["DV","HDR"],"languages":["multi","en","it"],"group":"NAHOM","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.IMAX.4K.HDR.DV.2160p BDRemux Ita Eng x265-NAHOM.mkv","videoSize":89079717868}},
            {"name":"TB 2160p cached","description":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"3cddcfafc904a8a7c6449e2fe1f24877d3eacf1f","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","stream":{"raw":{"parsed":{"resolution":"2160p","quality":"BluRay REMUX","codec":"hevc","audio":["DTS Lossless"],"channels":["5.1"],"hdr":["DV"],"group":"FraMeSToR","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv","videoSize":89100999892}},
            {"name":"TB 1440p cached","description":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ecf30429dffab21493b001fbae9e1c69ac86fef7","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","stream":{"raw":{"parsed":{"resolution":"1440p","quality":"HDTV","codec":"av1","audio":["DTS Lossy"],"hdr":["HDR"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.1440p.HDR.DTS-HD.AV1.mkv","videoSize":14559208901}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"e4336787b03acd5e030902df8c2b33aefcda6c4c","sources":[],"filename":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["fr"],"group":"Slay3R","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.FANSUB.VOSTFR.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-Slay3R.mkv","videoSize":41891055382}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"9a71fed91185ee611995c2a8ce374928cf64b575","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"languages":["multi","en","fr","es","it","de","pl","cs"],"group":"SGF","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.Eng.Fre.Ger.Ita.Spa.Cze.Pol.1080p.BluRay.Remux.AVC.DTS-HD.MA-SGF.mkv","videoSize":51056367324}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"4718d09f3c5f3c5a578d1ec61416de7a3747ef97","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"group":"FraMeSToR","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.BluRay.1080p.DTS-HD.MA.5.1.AVC.REMUX-FraMeSToR.mkv","videoSize":42601767435}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ba977cf57b3bae48f73b312e6b715cb48265794d","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["multi","en","es","pl","cs"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.CZ.EN.ES.PL.1080p.Blu-Ray.Remux.AVC.DTS-HD.MA 5.1.mkv","videoSize":48286797994}},
            {"name":"TB 1080p cached","description":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1e5fee8ca23ec735789e2323fdab6f809ae0c302","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","stream":{"raw":{"parsed":{"resolution":"1080p","quality":"BluRay REMUX","codec":"avc","audio":["DTS Lossless"],"channels":["5.1"],"languages":["multi","fr"],"group":"HYPERION","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.MULTI.VF2.1080p.BluRay.Remux.DTS-HD.MA.5.1.AVC-HYPERION.mkv","videoSize":44082903325}},
            {"name":"TB 720p cached","description":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"70af8f3beac7b8d9fd9b1538c2bf7242dbffb7c6","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"WEB-DL","codec":"avc","audio":["Dolby Digital Plus"],"channels":["5.1"],"languages":["multi"],"group":"TheBiscuitMan","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.720p.MA.WEB-DL.DUAL.DD+5.1.H.264-TheBiscuitMan.mkv","videoSize":13916786260}},
            {"name":"TB 720p cached","description":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"7d2e222138aef25ffe38577e4cb9ad04e7d30356","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","title":"Oppenheimer","torrentName":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"avc","audio":["AAC"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.720p.BluRay.x264.AAC-[YTS.MX].mp4","videoSize":1741975811}},
            {"name":"TB 720p cached","description":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"b7b0179a26199b018e0542c635655d49f8292347","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"hevc","group":"PSA","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.720p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv","videoSize":1112357595}},
            {"name":"TB 720p cached","description":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"f19604e63fe4f880177b0b4463f326e01f8657ce","sources":[],"fileIdx":0,"filename":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","title":"Oppenheimer","torrentName":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","stream":{"raw":{"parsed":{"resolution":"720p","quality":"BluRay","codec":"avc","languages":["fr"],"group":"Slay3R","year":2023}}}},"behaviorHints":{"filename":"[ Torrent911.io ] Oppenheimer.2023.VFQ.720p.BluRay.x264-Slay3R.mkv","videoSize":8401763164}},
            {"name":"TB 720p cached","description":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"5f40224807d0642859246f89510c41921528b61c","sources":[],"fileIdx":0,"filename":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","title":"Oppenheimer","torrentName":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","stream":{"raw":{"parsed":{"resolution":"720p","codec":"avc","audio":["Dolby Digital"],"channels":["5.1"],"languages":["multi","en","it"],"group":"MIRCrew","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer (2023) 720p h264 Ac3 5.1 Ita Eng Sub Ita Emg-MIRCrew.mkv","videoSize":3951560261}},
            {"name":"TB 480p cached","description":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"4fceb37f8a7f528bd01b526329e9bc02bacc2f9e","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","title":"Oppenheimer","torrentName":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","stream":{"raw":{"parsed":{"resolution":"480p","quality":"BDRip","codec":"xvid","languages":["pl"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.PL.480p.IMAX.BDRip.XviD-K83.avi","videoSize":1468475610}},
            {"name":"TB unknown cached","description":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"1ebd4a76ccdf04ceb3bedd5ba08000c9cfbf779f","sources":[],"filename":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"REMUX","audio":["DTS Lossless"],"languages":["multi"],"group":"TABGPT"}}}},"behaviorHints":{"filename":"Oppenheimer.UHD.REMUX.MULTI.DTS.HD.MA-TABGPT.mkv","videoSize":89021342424}},
            {"name":"TB unknown cached","description":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"53c49f467121b3fccadb95ce098e84f80e052180","sources":[],"fileIdx":0,"filename":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","title":"Oppenheimer","torrentName":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"BluRay REMUX","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer (2023) - UHD BD-Remux by Wild_Cat.mkv","videoSize":104700022239}},
            {"name":"TB unknown cached","description":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"e9f0213e614fc1b76a50637571e8b9c732933bbf","sources":[],"fileIdx":0,"filename":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","title":"Oppenheimer","torrentName":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","stream":{"raw":{"parsed":{"resolution":"unknown","quality":"BluRay","languages":["es"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer (2023) [Bluray][Esp](wolfmax4k.com).avi","videoSize":2163519374}},
            {"name":"TB unknown cached","description":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"f0da1448c8d29c9d0f1d5d9b374542a1b2c5a1b5","sources":[],"fileIdx":0,"filename":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","title":"Oppenheimer","torrentName":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","stream":{"raw":{"parsed":{"resolution":"unknown","languages":["multi","en","fr","it","de"],"year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.2023.2K.HLG.Eng.Fre.Ger.Ita.mkv","videoSize":4622252199}},
            {"name":"TB unknown cached","description":"Oppenheimer.(2023).[tmdbId=872585].mkv","clientResolve":{"type":"debrid","service":"torbox","isCached":true,"infoHash":"ec50800ca611355438fa429ad60b4b1e6a93def0","sources":[],"filename":"Oppenheimer.(2023).[tmdbId=872585].mkv","title":"Oppenheimer","torrentName":"Oppenheimer.(2023).[tmdbId=872585].mkv","stream":{"raw":{"parsed":{"resolution":"unknown","year":2023}}}},"behaviorHints":{"filename":"Oppenheimer.(2023).[tmdbId=872585].mkv","videoSize":5371431742}}
          ],
          "resolveMode": "client"
        }
    """.trimIndent()

    private val torboxMegamindResponse = """
        {
          "streams": [
            {
              "name": "TB 2160p cached",
              "description": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:c7a807331e1dcdd08e4527f6363cd1e8a109fe01&dn=Megamind%20%282010%29%20UpScaled%202160p%20H265%2010%20bit%20DV%20HDR10%2B%20ita%20eng%20AC3%205.1%20sub%20ita%20eng%20Licdom.mkv",
                "infoHash": "c7a807331e1dcdd08e4527f6363cd1e8a109fe01",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "title": "Megamind",
                "torrentName": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "2160p",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "hdr": ["DV", "HDR10+"],
                      "languages": ["multi", "en", "it"],
                      "year": 2010,
                      "raw_title": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind (2010) UpScaled 2160p H265 10 bit DV HDR10+ ita eng AC3 5.1 sub ita eng Licdom.mkv",
                "videoSize": 4571523542
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:6744954d89d8190643cb80653cf9b5c4b482409f&dn=Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "infoHash": "6744954d89d8190643cb80653cf9b5c4b482409f",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "FiBERHD",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.REMUX.AVC.TRUEHD.7.1-FiBERHD.mkv",
                "videoSize": 24201782274
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:dbbbe7da95f9bac639af2d182746d33d0cea51fb&dn=Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "infoHash": "dbbbe7da95f9bac639af2d182746d33d0cea51fb",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay REMUX",
                      "codec": "avc",
                      "audio": ["TrueHD"],
                      "channels": ["7.1"],
                      "group": "NOGRP",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.Remux.AVC.TrueHD.7.1-NOGRP.mkv",
                "videoSize": 22719933168
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:11802541a66f717fbc58dd72cd267d55a6df0477&dn=Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "infoHash": "11802541a66f717fbc58dd72cd267d55a6df0477",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "WEB-DL",
                      "codec": "avc",
                      "audio": ["AAC"],
                      "channels": ["2.0"],
                      "group": "PiRaTeS",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.ROKU.WEB-DL.AAC.2.0.H.264-PiRaTeS.mkv",
                "videoSize": 3079386380
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:3fa25f80fb3eddf076c9002cc16c2a9801233e4b&dn=Megamind%20%282010%29%201080p%2010bit%20Bluray%20x265%20HEVC%20%5BOrg%20DD%205.1%20Hindi%20%2B%20DD%205.1%20English%5D%20MSubs%20~%20TombDoc.mkv",
                "infoHash": "3fa25f80fb3eddf076c9002cc16c2a9801233e4b",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "title": "Megamind",
                "torrentName": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital"],
                      "channels": ["5.1"],
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind (2010) 1080p 10bit Bluray x265 HEVC [Org DD 5.1 Hindi + DD 5.1 English] MSubs ~ TombDoc.mkv",
                "videoSize": 3859136613
              }
            },
            {
              "name": "TB 1080p cached",
              "description": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:e4d12499cf481aeee9007b806c609917d3799e43&dn=Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "infoHash": "e4d12499cf481aeee9007b806c609917d3799e43",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "title": "Megamind",
                "torrentName": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "1080p",
                      "quality": "BluRay",
                      "codec": "hevc",
                      "audio": ["Dolby Digital Plus"],
                      "channels": ["7.1"],
                      "group": "EDGE2020",
                      "year": 2010,
                      "raw_title": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind.2010.1080p.BluRay.DDP.7.1.x265-EDGE2020.mkv",
                "videoSize": 2946516232
              }
            },
            {
              "name": "TB 720p cached",
              "description": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
              "clientResolve": {
                "type": "debrid",
                "service": "torbox",
                "isCached": true,
                "magnetUri": "magnet:?xt=urn:btih:52c6341e0e4cc4eb0e23bd16f93a00dcad93490c&dn=Megamind%202010%20x264%20720p%20Esub%20BluRay%20Dual%20Audio%20English%20Hindi%20GOPI%20SAHI.mkv",
                "infoHash": "52c6341e0e4cc4eb0e23bd16f93a00dcad93490c",
                "sources": [],
                "fileIdx": 0,
                "filename": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "title": "Megamind",
                "torrentName": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "stream": {
                  "raw": {
                    "parsed": {
                      "resolution": "720p",
                      "quality": "BluRay",
                      "codec": "avc",
                      "languages": ["multi", "en", "hi"],
                      "year": 2010,
                      "raw_title": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv"
                    }
                  }
                }
              },
              "behaviorHints": {
                "filename": "Megamind 2010 x264 720p Esub BluRay Dual Audio English Hindi GOPI SAHI.mkv",
                "videoSize": 796639202
              }
            }
          ],
          "resolveMode": "client"
        }
    """.trimIndent()
}

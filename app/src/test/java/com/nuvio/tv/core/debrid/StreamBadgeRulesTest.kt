package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBadgeRulesTest {
    @Test
    fun `parses fusion badge json and drops unusable filters`() {
        val import = StreamBadgeRulesParser.parse(
            sourceUrl = " https://example.com/badges.json ",
            payload = fusionPayload()
        )

        assertEquals("https://example.com/badges.json", import.sourceUrl)
        assertEquals(2, import.filters.size)
        assertEquals("Dolby Vision", import.filters[0].name)
        assertEquals("#102030", import.filters[0].tagColor)
        assertEquals("filled", import.filters[0].tagStyle)
        assertEquals(1, import.groups.size)
        assertEquals("Quality", import.groups[0].name)
    }

    @Test
    fun `normalizes imports with one active source and import limit`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport("one", active = false),
                badgeImport("two", active = true),
                badgeImport("three", active = true),
                badgeImport("four", active = false)
            )
        ).normalized()

        assertEquals(3, rules.imports.size)
        assertEquals(listOf(false, true, false), rules.imports.map { it.isActive })
    }

    @Test
    fun `matches active badge filters against stream metadata`() {
        val stream = directDebridStream()
        val rules = StreamBadgeRules(
            imports = listOf(
                badgeImport("inactive", active = false, name = "Inactive", pattern = "Movie"),
                StreamBadgeImport(
                    sourceUrl = "active",
                    isActive = true,
                    filters = listOf(
                        StreamBadgeFilter(name = "Dolby Vision", pattern = "DV", imageURL = "https://cdn/dv.png"),
                        StreamBadgeFilter(name = "Atmos", pattern = "Atmos", imageURL = "https://cdn/atmos.png"),
                        StreamBadgeFilter(name = "Disabled", pattern = "Movie", isEnabled = false)
                    )
                )
            )
        )

        val badges = StreamBadgeMatcher.matchedBadges(stream, StreamBadgeMatcher.compile(rules))

        assertEquals(listOf("Dolby Vision", "Atmos"), badges.map { it.name })
    }

    @Test
    fun `formatter exposes matched badges and badge variables`() {
        val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(name = "Dolby Vision", pattern = "DV", imageURL = "https://cdn/dv.png"),
                        StreamBadgeFilter(name = "Atmos", pattern = "Atmos", imageURL = "https://cdn/atmos.png")
                    )
                )
            )
        )
        val settings = DebridSettings(
            streamNameTemplate = "{stream.rseMatched::join(' + ')}",
            streamDescriptionTemplate = "{stream.regexMatched::join(', ')}",
            streamBadgeRules = rules
        )

        val formatted = formatter.format(directDebridStream(), settings)

        assertEquals("Dolby Vision + Atmos", formatted.name)
        assertEquals("Dolby Vision, Atmos", formatted.description)
        assertEquals(listOf("Dolby Vision", "Atmos"), formatted.badges.map { it.name })
    }

    private fun badgeImport(
        sourceUrl: String,
        active: Boolean,
        name: String = "Badge",
        pattern: String = "Badge"
    ): StreamBadgeImport =
        StreamBadgeImport(
            sourceUrl = sourceUrl,
            isActive = active,
            filters = listOf(StreamBadgeFilter(name = name, pattern = pattern))
        )

    private fun directDebridStream(): Stream =
        Stream(
            name = "Movie 2026 DV Atmos",
            title = "Movie",
            description = null,
            url = "https://stream.example/movie.mkv",
            ytId = null,
            infoHash = "abc123",
            fileIdx = 0,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "Movie.2026.DV.Atmos.mkv"
            ),
            addonName = "Fusion",
            addonLogo = null,
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = "abc123",
                fileIdx = 0,
                magnetUri = null,
                sources = null,
                torrentName = "Movie.2026.DV.Atmos",
                filename = "Movie.2026.DV.Atmos.mkv",
                mediaType = "movie",
                mediaId = "tt123",
                mediaOnlyId = "tt123",
                title = "Movie",
                season = null,
                episode = null,
                service = DebridProviders.TORBOX_ID,
                serviceIndex = 0,
                serviceExtension = "TB",
                isCached = true,
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        torrentName = "Movie.2026.DV.Atmos",
                        filename = "Movie.2026.DV.Atmos.mkv",
                        size = 1_000L,
                        folderSize = 1_000L,
                        tracker = null,
                        indexer = null,
                        network = null,
                        parsed = StreamClientResolveParsed(
                            rawTitle = "Movie",
                            parsedTitle = "Movie",
                            year = 2026,
                            resolution = "2160p",
                            seasons = emptyList(),
                            episodes = emptyList(),
                            quality = "WEB-DL",
                            hdr = listOf("DV"),
                            codec = "HEVC",
                            audio = listOf("Atmos"),
                            channels = listOf("5.1"),
                            languages = listOf("en"),
                            group = "Nuvio",
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
        )

    private fun fusionPayload(): String =
        """
        {
          "filters": [
            {
              "id": "dv",
              "groupId": "quality",
              "name": "Dolby Vision",
              "pattern": "DV",
              "imageURL": "https://cdn.example/dv.png",
              "tagColor": "#102030",
              "tagStyle": "filled",
              "textColor": "#ffffff",
              "borderColor": "#405060"
            },
            {
              "id": "atmos",
              "groupId": "quality",
              "name": "Atmos",
              "pattern": "Atmos",
              "imageURL": "https://cdn.example/atmos.png"
            },
            {
              "id": "blank",
              "groupId": "quality",
              "name": "",
              "pattern": "Ignored"
            }
          ],
          "groups": [
            {
              "id": "quality",
              "name": "Quality",
              "color": "#ffffff"
            }
          ]
        }
        """.trimIndent()
}

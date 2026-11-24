package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaMapperTest {

    private val adapter = Moshi.Builder().build().adapter(MetaResponseDto::class.java)

    @Test
    fun `localized addon certification takes priority`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "certificationLocal": " 16 ",
                  "certification": "TV-MA"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("16", result.ageRating)
    }

    @Test
    fun `default addon certification is used when localized value is blank`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "movie",
                "type": "movie",
                "name": "Movie",
                "app_extras": {
                  "certificationLocal": " ",
                  "certification": " PG-13 "
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("PG-13", result.ageRating)
    }

    @Test
    fun `keyed season posters map by season number`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "seasonPosters": { "0": "specials.jpg", "1": " s1.jpg ", "2": "" }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(mapOf(0 to "specials.jpg", 1 to "s1.jpg"), result.seasonPosters)
    }

    @Test
    fun `season poster array with leading null maps to specials and regular seasons`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "videos": [
                  { "id": "s1e1", "season": 1, "episode": 1 },
                  { "id": "s2e1", "season": 2, "episode": 1 }
                ],
                "app_extras": {
                  "seasonPosters": [null, "s1.jpg", "s2.jpg"]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(mapOf(1 to "s1.jpg", 2 to "s2.jpg"), result.seasonPosters)
    }

    @Test
    fun `season poster array matching regular seasons skips specials`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "videos": [
                  { "id": "s0e1", "season": 0, "episode": 1 },
                  { "id": "s1e1", "season": 1, "episode": 1 },
                  { "id": "s3e1", "season": 3, "episode": 1 }
                ],
                "app_extras": {
                  "seasonPosters": ["s1.jpg", "s3.jpg"]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(mapOf(1 to "s1.jpg", 3 to "s3.jpg"), result.seasonPosters)
    }

    @Test
    fun `episode season poster falls back to tmdb path field`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "videos": [
                  { "id": "a", "season": 1, "episode": 1, "seasonPoster": "direct.jpg", "season_poster_path": "path.jpg" },
                  { "id": "b", "season": 2, "episode": 1, "season_poster_path": " path2.jpg " },
                  { "id": "c", "season": 3, "episode": 1, "seasonPoster": " " }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("direct.jpg", "path2.jpg", null), result.videos.map { it.seasonPoster })
    }

    @Test
    fun `an episode's IMDb id and numbering are read in any form without failing the meta`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "kitsu:1",
                "type": "series",
                "name": "Show",
                "videos": [
                  { "id": "kitsu:1:1", "season": 1, "episode": 1, "imdb_id": "tt123", "imdbSeason": 2, "imdbEpisode": 1 },
                  { "id": "kitsu:1:2", "season": 1, "episode": 2, "imdb_id": " tt123 ", "imdbSeason": "2", "imdbEpisode": "2" },
                  { "id": "kitsu:1:3", "season": 1, "episode": 3, "imdb_id": 123, "imdbSeason": {"n": 2}, "imdbEpisode": 2.5 },
                  { "id": "kitsu:1:4", "season": 1, "episode": 4, "imdb_id": "nm123", "imdbSeason": "S2", "imdbEpisode": -1 },
                  { "id": "kitsu:1:5", "season": 1, "episode": 5 }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("tt123", "tt123", null, null, null), result.videos.map { it.imdbId })
        assertEquals(listOf(2, 2, null, null, null), result.videos.map { it.imdbSeason })
        assertEquals(listOf(1, 2, null, null, null), result.videos.map { it.imdbEpisode })
    }

    @Test
    fun `trailer streams keep direct urls next to youtube ids`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "tt1",
                "type": "movie",
                "name": "Movie",
                "trailers": [{ "source": "aaaaaaaaaaa", "type": "Trailer" }],
                "trailerStreams": [
                  { "ytId": "aaaaaaaaaaa", "title": "Duplicate" },
                  { "url": "https://cdn.example/trailer.mp4", "title": "Plex 1080p", "thumbnail": "https://cdn.example/thumb.jpg" },
                  { "url": "https://cdn.example/trailer.mp4", "name": "Duplicate" },
                  { "url": "magnet:?xt=urn:btih:abc" },
                  { "ytId": "bbbbbbbbbbb", "name": "Teaser" }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), result.trailerYtIds)
        assertEquals(listOf("https://cdn.example/trailer.mp4"), result.trailerUrls)
        assertEquals(
            listOf("aaaaaaaaaaa", "https://cdn.example/trailer.mp4", "bbbbbbbbbbb"),
            result.trailers.map { it.playbackKey }
        )
        assertEquals("Plex 1080p", result.trailers[1].name)
        assertEquals("https://cdn.example/thumb.jpg", result.trailers[1].thumbnail)
        assertEquals("Teaser", result.trailers[2].name)
    }

    private fun parse(json: String) = requireNotNull(adapter.fromJson(json)?.meta).toDomain()
}

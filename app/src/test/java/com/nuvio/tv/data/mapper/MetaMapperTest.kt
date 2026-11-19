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

    private fun parse(json: String) = requireNotNull(adapter.fromJson(json)?.meta).toDomain()
}

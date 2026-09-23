package com.nuvio.tv.data.remote.dto.mdblist

import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.RottenTomatoesStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MDBListMediaResponseDtoTest {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(MDBListMediaResponseDto::class.java)

    @Test
    fun `standard icons switch at sixty percent without granting certification`() {
        for (score in listOf(0.0, 59.0, 59.9)) {
            val ratings = MDBListRatings(tomatoes = score, audience = score)
            assertEquals(RottenTomatoesStatus.ROTTEN, ratings.tomatoesStatus)
            assertEquals(RottenTomatoesStatus.STALE, ratings.audienceStatus)
        }
        for (score in listOf(60.0, 75.0, 90.0, 100.0)) {
            val ratings = MDBListRatings(tomatoes = score, audience = score)
            assertEquals(RottenTomatoesStatus.FRESH, ratings.tomatoesStatus)
            assertEquals(RottenTomatoesStatus.HOT, ratings.audienceStatus)
        }
    }

    @Test
    fun `existing certifications use retention thresholds`() {
        val retained = MDBListRatings(tomatoes = 70.0, audience = 80.0, tomatoesCertified = true, audienceCertified = true)
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, retained.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, retained.audienceStatus)
        assertEquals(RottenTomatoesStatus.FRESH, retained.copy(tomatoes = 69.0).tomatoesStatus)
        assertEquals(RottenTomatoesStatus.HOT, retained.copy(audience = 79.0).audienceStatus)
        assertEquals(RottenTomatoesStatus.ROTTEN, retained.copy(tomatoes = 59.0).tomatoesStatus)
        assertEquals(RottenTomatoesStatus.STALE, retained.copy(audience = 59.0).audienceStatus)
    }

    @Test
    fun `title response maps certification keywords and both scores`() {
        val ratings = parse(
            """
            {
                "title": "Example",
                "ratings": [
                    {"source": "imdb", "value": 8.1},
                    {"source": "tomatoes", "value": 72, "score": 72, "votes": 100},
                    {"source": "popcorn", "value": 85, "score": 85, "votes": 1000}
                ],
                "keywords": [
                    {"id": 19631, "name": "certified-fresh"},
                    {"name": "mdblist.certified-hot"}
                ]
            }
            """.trimIndent()
        )
        assertEquals(72.0, ratings.tomatoes)
        assertEquals(85.0, ratings.audience)
        assertEquals(8.1, ratings.imdb)
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, ratings.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, ratings.audienceStatus)
    }

    @Test
    fun `high scores and vote counts do not grant certification`() {
        val ratings = parse(
            """{"ratings":[{"source":"tomatoes","value":100,"votes":500},{"source":"popcorn","value":100,"votes":10000}]}"""
        )
        assertFalse(ratings.tomatoesCertified)
        assertFalse(ratings.audienceCertified)
        assertEquals(RottenTomatoesStatus.FRESH, ratings.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.HOT, ratings.audienceStatus)
    }

    @Test
    fun `missing and invalid scores are skipped but zero is preserved`() {
        val ratings = parse(
            """
            {
                "ratings": [
                    {"source": "tomatoes", "value": null},
                    {"source": "tomatoes"},
                    {"source": "tomatoes", "value": -1},
                    {"source": "popcorn", "value": 101},
                    {"source": "popcorn", "value": 0}
                ],
                "keywords": null
            }
            """.trimIndent()
        )
        assertNull(ratings.tomatoes)
        assertNull(ratings.tomatoesStatus)
        assertEquals(0.0, ratings.audience)
        assertEquals(RottenTomatoesStatus.STALE, ratings.audienceStatus)
        assertTrue(parse("{}").isEmpty())
        assertTrue(parse("""{"ratings":null}""").isEmpty())
    }

    @Test
    fun `audience aliases and string keywords are accepted`() {
        for (source in listOf("audience", "tomatoesaudience")) {
            val ratings = parse(
                """{"ratings":[{"source":"$source","value":91}],"keywords":["certified-hot"]}"""
            )
            assertEquals(RottenTomatoesStatus.VERIFIED_HOT, ratings.audienceStatus)
        }
    }

    @Test
    fun `missing scores stay hidden even with certification keywords`() {
        val ratings = parse("""{"keywords":["certified-fresh","certified-hot"]}""")
        assertTrue(ratings.isEmpty())
        assertNull(ratings.tomatoesStatus)
        assertNull(ratings.audienceStatus)
    }

    @Test
    fun `all supported sources use their native rating scales`() {
        val ratings = parse("""{"ratings":[
            {"source":"imdb","value":0,"score":99},
            {"source":"trakt","value":81},
            {"source":"tmdb","value":78},
            {"source":"letterboxd","value":8.4,"score":84},
            {"source":"myanimelist","value":8.5},
            {"source":"metacritic","value":75},
            {"source":"metacriticuser","value":9.0}
        ]}""")

        assertEquals(MDBListRatings(imdb = 0.0, trakt = 81.0, tmdb = 78.0, letterboxd = 4.2, mal = 8.5, metacritic = 75.0), ratings)
    }

    @Test
    fun `invalid values and missing ratings are skipped`() {
        val ratings = parse("""{"ratings":[
            {"source":"imdb","value":11},
            {"source":"trakt","value":-1},
            {"source":"tmdb","value":101},
            {"source":"letterboxd","value":-1,"score":80},
            {"source":"letterboxd","value":5.1},
            {"source":"myanimelist","value":10.1},
            {"source":"metacritic","value":null},
            {"source":"imdb","score":75}
        ]}""")

        assertTrue(ratings.isEmpty())
    }

    @Test
    fun `live single and batch Letterboxd formats keep the same five point rating`() {
        for (value in listOf(3.9, 7.8)) {
            assertEquals(3.9, parse("""{"ratings":[{"source":"letterboxd","value":$value,"score":78}]}""").letterboxd)
        }
        assertEquals(1.2, parse("""{"ratings":[{"source":"letterboxd","value":2.4,"score":24}]}""").letterboxd)
        assertEquals(4.2, parse("""{"ratings":[{"source":"letterboxd","value":4.2}]}""").letterboxd)
    }

    @Test
    fun `current and legacy imdb ids can identify batch results`() {
        for (payload in listOf(
            """{"ids":{"imdb":"tt1","tmdb":1,"mal":null}}""",
            """{"imdb_id":"tt1"}""",
            """{"imdbid":"tt1"}"""
        )) {
            assertEquals("tt1", requireNotNull(adapter.fromJson(payload)).resolvedImdbId())
        }
        assertNull(requireNotNull(adapter.fromJson("""{"id":1}""")).resolvedImdbId())
    }

    private fun parse(payload: String): MDBListRatings =
        requireNotNull(adapter.fromJson(payload)).toRatings()
}

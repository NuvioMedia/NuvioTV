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
        assertNull(ratings.imdb)
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

    private fun parse(payload: String): MDBListRatings =
        requireNotNull(adapter.fromJson(payload)).toRottenTomatoesRatings()
}

package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListRatingsClient
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRatingDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.RottenTomatoesStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class MDBListRepositoryTest {
    private val api = mockk<MDBListApi>()
    private val settings = MDBListSettings(
        enabled = true,
        apiKey = "test-key",
        showTrakt = false,
        showImdb = false,
        showTmdb = false,
        showLetterboxd = false,
        showTomatoes = true,
        showAudience = true,
        showMetacritic = false,
        showMal = false
    )
    private val media = MDBListMediaResponseDto(
        ratings = listOf(
            MDBListMediaRatingDto("tomatoes", 72.0),
            MDBListMediaRatingDto("popcorn", 85.0)
        ),
        keywords = listOf("certified-fresh", "certified-hot")
    )
    private val account = MdbListTestHarness()
    private val preferences = MutableStateFlow(settings)
    private val imdbSettings = settings.copy(showTomatoes = false, showAudience = false, showImdb = true)

    @Test
    fun `ratings availability includes connected accounts and explicit keys`() {
        val repository = repository()
        assertFalse(repository.isAvailable(settings.copy(apiKey = "")))
        assertTrue(repository.isAvailable(settings))
        account.connected()
        assertTrue(repository.isAvailable(settings.copy(apiKey = "")))
        assertFalse(repository.isAvailable(settings.copy(enabled = false)))
    }

    @Test
    fun `connected account supplies home and detail ratings without an api key`() = runTest {
        account.connected()
        account.reply(body = """{"ratings":[{"rating":8.1}]}""")
        val repository = repository(imdbSettings.copy(apiKey = ""))

        assertEquals(8.1, repository.getImdbRatingForItem("tt1234567", "movie"))
        assertEquals(8.1, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)

        assertEquals(1, account.engine.requests.size)
        assertEquals("/rating/movie/imdb", account.engine.requests.single().path)
        assertEquals("access-one", account.engine.requests.single().accessToken)
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `api key overrides connected account and clearing it restores account ratings`() = runTest {
        account.connected()
        account.reply(body = """{"ratings":[{"rating":8.1}]}""")
        coEvery { api.getRating("movie", "imdb", "separate-key", any()) } returns numericRating(7.2)
        val repository = repository(imdbSettings.copy(apiKey = ""))

        assertEquals(8.1, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)
        preferences.value = imdbSettings.copy(apiKey = " separate-key ")
        assertEquals(7.2, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)
        preferences.value = imdbSettings.copy(apiKey = "")
        assertEquals(8.1, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)

        assertEquals(1, account.engine.requests.size)
        coVerify(exactly = 1) { api.getRating("movie", "imdb", "separate-key", any()) }
    }

    @Test
    fun `disconnect and profile changes do not reuse another account cache`() = runTest {
        account.connected()
        account.reply(body = """{"ratings":[{"rating":8.1}]}""")
        val repository = repository(imdbSettings.copy(apiKey = ""))
        assertEquals(8.1, repository.getImdbRatingForItem("tt1234567", "movie"))

        account.store.clearAuth()
        assertNull(repository.getImdbRatingForItem("tt1234567", "movie"))
        account.connected("reconnected")
        account.reply(body = """{"ratings":[{"rating":7.2}]}""")
        assertEquals(7.2, repository.getImdbRatingForItem("tt1234567", "movie"))

        account.store.selectProfile(2)
        assertNull(repository.getImdbRatingForItem("tt1234567", "movie"))
        account.connected("profile-two")
        account.reply(body = """{"ratings":[{"rating":6.3}]}""")
        assertEquals(6.3, repository.getImdbRatingForItem("tt1234567", "movie"))
        assertEquals(listOf("access-one", "reconnected", "profile-two"), account.engine.requests.map { it.accessToken })
    }

    @Test
    fun `missing credentials and disabled ratings do not make requests`() = runTest {
        val repository = repository(imdbSettings.copy(apiKey = ""))
        assertNull(repository.getImdbRatingForItem("tt1234567", "movie"))
        assertNull(repository.getRatingsForMeta(meta(), "tt1234567", "movie"))

        account.connected()
        preferences.value = imdbSettings.copy(enabled = false)
        assertNull(repository.getImdbRatingForItem("tt1234567", "movie"))
        assertNull(repository.getRatingsForMeta(meta(), "tt1234567", "movie"))
        assertTrue(account.engine.requests.isEmpty())
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `both rotten tomatoes scores share one lookup and are cached`() = runTest {
        coEvery { api.getMedia("movie", "tt1234567", "test-key", "keyword") } returns Response.success(media)
        coEvery { api.getRating("movie", "imdb", "test-key", any()) } returns numericRating(8.1)
        val repository = repository(settings.copy(showImdb = true))

        val first = repository.getRatingsForMeta(meta(), "tt1234567", "movie")
        val cached = repository.getRatingsForMeta(meta(), "tt1234567", "movie")

        assertNotNull(first)
        assertEquals(first, cached)
        assertTrue(first!!.hasImdbRating)
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, first.ratings.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, first.ratings.audienceStatus)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 1) { api.getRating(any(), any(), any(), any()) }
        coVerify(exactly = 1) { api.getRating("movie", "imdb", "test-key", any()) }
    }

    @Test
    fun `audience only uses one show lookup and respects disabled critics`() = runTest {
        coEvery { api.getMedia("show", "tt1234567", "test-key", "keyword") } returns Response.success(media)

        val result = repository(settings.copy(showTomatoes = false))
            .getRatingsForMeta(meta("series"), "tt1234567", "series")

        assertNotNull(result)
        assertNull(result!!.ratings.tomatoes)
        assertNull(result.ratings.tomatoesStatus)
        assertEquals(85.0, result.ratings.audience)
        assertFalse(result.hasImdbRating)
        coVerify(exactly = 1) { api.getMedia("show", "tt1234567", "test-key", "keyword") }
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `other providers do not request rotten tomatoes metadata`() = runTest {
        coEvery { api.getRating("movie", "imdb", "test-key", any()) } returns numericRating(8.1)

        val result = repository(settings.copy(showTomatoes = false, showAudience = false, showImdb = true))
            .getRatingsForMeta(meta(), "tt1234567", "movie")

        assertEquals(8.1, result?.ratings?.imdb)
        coVerify(exactly = 0) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 1) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `failed metadata lookup falls back to numeric ratings without certification`() = runTest {
        coEvery { api.getMedia(any(), any(), any(), any()) } throws IOException("Unavailable")
        coEvery { api.getRating("movie", "tomatoes", "test-key", any()) } returns numericRating(100.0)
        coEvery { api.getRating("movie", "audience", "test-key", any()) } returns numericRating(59.0)

        val result = repository().getRatingsForMeta(meta(), "tt1234567", "movie")

        assertNotNull(result)
        assertEquals(RottenTomatoesStatus.FRESH, result!!.ratings.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.STALE, result.ratings.audienceStatus)
        assertFalse(result.ratings.tomatoesCertified)
        assertFalse(result.ratings.audienceCertified)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 2) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `partial metadata only falls back for the missing rating`() = runTest {
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(
            media.copy(ratings = listOf(MDBListMediaRatingDto("popcorn", 85.0)))
        )
        coEvery { api.getRating("movie", "tomatoes", "test-key", any()) } returns numericRating(68.0)

        val result = repository().getRatingsForMeta(meta(), "tt1234567", "movie")

        assertNotNull(result)
        assertEquals(68.0, result!!.ratings.tomatoes)
        assertFalse(result.ratings.tomatoesCertified)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, result.ratings.audienceStatus)
        coVerify(exactly = 1) { api.getRating(any(), any(), any(), any()) }
        coVerify(exactly = 1) { api.getRating("movie", "tomatoes", "test-key", any()) }
    }

    private fun repository(initialSettings: MDBListSettings = settings): MDBListRepository {
        val store = mockk<MDBListSettingsDataStore>()
        preferences.value = initialSettings
        every { store.settings } returns preferences
        val client = MdbListRatingsClient(
            api, account.api, account.store, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        )
        return MDBListRepository(client, store, mockk<TmdbService>())
    }

    private fun meta(mediaType: String = "movie"): Meta = mockk {
        every { id } returns "tt1234567"
        every { apiType } returns mediaType
    }

    private fun numericRating(value: Double) = Response.success(
        MDBListRatingResponseDto(listOf(MDBListRatingItemDto(value)))
    )
}

package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListRatingsClient
import com.nuvio.tv.data.mdblist.MdbListRatingsLoader
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRatingDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.async
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
            MDBListMediaRatingDto("imdb", 8.1),
            MDBListMediaRatingDto("trakt", 81.0),
            MDBListMediaRatingDto("tmdb", 78.0),
            MDBListMediaRatingDto("letterboxd", 4.2, 84.0),
            MDBListMediaRatingDto("metacritic", 75.0),
            MDBListMediaRatingDto("myanimelist", 8.5),
            MDBListMediaRatingDto("tomatoes", 72.0),
            MDBListMediaRatingDto("popcorn", 85.0)
        ),
        keywords = listOf("certified-fresh", "certified-hot")
    )
    private val account = MdbListTestHarness()
    private val preferences = MutableStateFlow(settings)
    private val imdbSettings = settings.copy(showTomatoes = false, showAudience = false, showImdb = true)

    @Test
    fun `ratings availability includes connected accounts and explicit keys`() = runTest {
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
        account.reply(body = """{"ratings":[{"source":"imdb","value":8.1}]}""")
        val repository = repository(imdbSettings.copy(apiKey = ""))

        assertEquals(8.1, repository.getImdbRatingForItem("tt1234567", "movie"))
        assertEquals(8.1, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)

        assertEquals(1, account.engine.requests.size)
        assertEquals("/imdb/movie/tt1234567/", account.engine.requests.single().path)
        assertEquals("access-one", account.engine.requests.single().accessToken)
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `api key overrides connected account and clearing it restores account ratings`() = runTest {
        account.connected()
        account.reply(body = """{"ratings":[{"source":"imdb","value":8.1}]}""")
        coEvery { api.getMedia("movie", "tt1234567", "separate-key", "keyword") } returns imdbResponse(7.2)
        val repository = repository(imdbSettings.copy(apiKey = ""))

        assertEquals(8.1, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)
        preferences.value = imdbSettings.copy(apiKey = " separate-key ")
        assertEquals(7.2, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)
        preferences.value = imdbSettings.copy(apiKey = "")
        assertEquals(8.1, repository.getRatingsForMeta(meta(), "tt1234567", "movie")?.ratings?.imdb)

        assertEquals(1, account.engine.requests.size)
        coVerify(exactly = 1) { api.getMedia("movie", "tt1234567", "separate-key", "keyword") }
    }

    @Test
    fun `disconnect and profile changes do not reuse another account cache`() = runTest {
        account.connected()
        account.reply(body = """{"ratings":[{"source":"imdb","value":8.1}]}""")
        val repository = repository(imdbSettings.copy(apiKey = ""))
        assertEquals(8.1, repository.getImdbRatingForItem("tt1234567", "movie"))

        account.store.clearAuth()
        assertNull(repository.getImdbRatingForItem("tt1234567", "movie"))
        account.connected("reconnected")
        account.reply(body = """{"ratings":[{"source":"imdb","value":7.2}]}""")
        assertEquals(7.2, repository.getImdbRatingForItem("tt1234567", "movie"))

        account.store.selectProfile(2)
        assertNull(repository.getImdbRatingForItem("tt1234567", "movie"))
        account.connected("profile-two")
        account.reply(body = """{"ratings":[{"source":"imdb","value":6.3}]}""")
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
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `both rotten tomatoes scores share one lookup and are cached`() = runTest {
        coEvery { api.getMedia("movie", "tt1234567", "test-key", "keyword") } returns Response.success(media)
        val repository = repository(settings.copy(showImdb = true))

        val first = repository.getRatingsForMeta(meta(), "tt1234567", "movie")
        val cached = repository.getRatingsForMeta(meta(), "tt1234567", "movie")

        assertNotNull(first)
        assertEquals(first, cached)
        assertTrue(first!!.hasImdbRating)
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, first.ratings.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, first.ratings.audienceStatus)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
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
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `imdb only selects its rating from one full media response`() = runTest {
        coEvery { api.getMedia("movie", "tt1234567", "test-key", "keyword") } returns Response.success(media)

        val result = repository(imdbSettings).getRatingsForMeta(meta(), "tt1234567", "movie")

        assertEquals(8.1, result?.ratings?.imdb)
        assertNull(result?.ratings?.tomatoes)
        assertNull(result?.ratings?.tmdb)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `failed metadata can be retried without individual provider fallback requests`() = runTest {
        coEvery { api.getMedia(any(), any(), any(), any()) } throws IOException("Unavailable")
        val repository = repository()

        assertNull(repository.getRatingsForMeta(meta(), "tt1234567", "movie"))
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(media)
        val result = repository.getRatingsForMeta(meta(), "tt1234567", "movie")

        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, result?.ratings?.tomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, result?.ratings?.audienceStatus)
        coVerify(exactly = 2) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `missing providers stay absent without fallback requests`() = runTest {
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(
            media.copy(ratings = listOf(MDBListMediaRatingDto("popcorn", 85.0)))
        )

        val result = repository().getRatingsForMeta(meta(), "tt1234567", "movie")

        assertNotNull(result)
        assertNull(result!!.ratings.tomatoes)
        assertFalse(result.ratings.tomatoesCertified)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, result.ratings.audienceStatus)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `home ratings detail ratings and source toggles reuse one complete response`() = runTest {
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(media)
        val repository = repository(imdbSettings)

        assertEquals(8.1, repository.getImdbRatingForItem("tt1234567", "movie"))
        preferences.value = MDBListSettings(enabled = true, apiKey = "test-key")
        val ratings = requireNotNull(repository.getRatingsForMeta(meta(), "tt1234567", "movie")).ratings
        assertEquals(listOf(8.1, 81.0, 78.0, 4.2, 72.0, 85.0, 75.0, 8.5), listOf(
            ratings.imdb, ratings.trakt, ratings.tmdb, ratings.letterboxd,
            ratings.tomatoes, ratings.audience, ratings.metacritic, ratings.mal
        ))
        preferences.value = settings.copy(showTomatoes = false, showAudience = false, showMal = true)
        val filtered = requireNotNull(repository.getRatingsForMeta(meta(), "tt1234567", "movie"))
        assertEquals(8.5, filtered.ratings.mal)
        assertNull(filtered.ratings.imdb)
        assertFalse(filtered.hasImdbRating)
        assertNull(filtered.ratings.audienceStatus)
        assertFalse(filtered.ratings.audienceCertified)
        assertEquals(8.1, repository.getImdbRatingForItem("tt1234567", "movie"))
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `concurrent home and detail lookups share one request`() = runTest {
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(media)
        val repository = repository(MDBListSettings(enabled = true, apiKey = "test-key"))
        val home = async { repository.getImdbRatingForItem("tt1234567", "movie") }
        val detail = async { repository.getRatingsForMeta(meta(), "tt1234567", "movie") }

        assertEquals(8.1, home.await())
        assertEquals(4.2, detail.await()?.ratings?.letterboxd)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `disabling every detail source avoids a ratings request`() = runTest {
        val repository = repository(settings.copy(showTomatoes = false, showAudience = false))

        assertNull(repository.getRatingsForMeta(meta(), "tt1234567", "movie"))
        coVerify(exactly = 0) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    private fun TestScope.repository(initialSettings: MDBListSettings = settings): MDBListRepository {
        val store = mockk<MDBListSettingsDataStore>()
        preferences.value = initialSettings
        every { store.settings } returns preferences
        val client = MdbListRatingsClient(
            api, account.api, account.store, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        )
        return MDBListRepository(client, store, mockk<TmdbService>(), MdbListRatingsLoader(client, backgroundScope))
    }

    private fun meta(mediaType: String = "movie"): Meta = mockk {
        every { id } returns "tt1234567"
        every { apiType } returns mediaType
    }

    private fun imdbResponse(value: Double) = Response.success(
        MDBListMediaResponseDto(ratings = listOf(MDBListMediaRatingDto("imdb", value)))
    )
}

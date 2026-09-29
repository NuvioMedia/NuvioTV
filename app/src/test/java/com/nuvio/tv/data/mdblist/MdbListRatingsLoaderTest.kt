package com.nuvio.tv.data.mdblist

import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRatingDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MdbListRatingsLoaderTest {
    private val harness = MdbListTestHarness()
    private val api = mockk<MDBListApi>()
    private val client = MdbListRatingsClient(
        api, harness.api, harness.store, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    )
    private val credential = MdbListRatingsCredential.ApiKey("test-key")
    private val batches = mutableListOf<MDBListMediaRequestDto>()
    private var now = 0L
    private val media = MDBListMediaResponseDto(
        ratings = listOf(MDBListMediaRatingDto("imdb", 8.1), MDBListMediaRatingDto("letterboxd", 7.8, 78.0))
    )

    init {
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(media)
        coEvery { api.getMediaBatch(any(), any(), any()) } coAnswers {
            val body = thirdArg<MDBListMediaRequestDto>()
            batches += body
            Response.success(body.ids.reversed().map { id -> media.copy(ids = mapOf("imdb" to id)) })
        }
    }

    @Test
    fun `nearby requests batch once and match results by id`() = runTest {
        coEvery { api.getMediaBatch(any(), any(), any()) } returns Response.success(listOf(
            media.copy(ids = mapOf("imdb" to "tt2"), ratings = listOf(MDBListMediaRatingDto("imdb", 6.5))),
            media.copy(imdbId = "tt1")
        ))
        val loader = loader()
        val first = async { loader.getRatings("movie", "tt1", credential) }
        runCurrent()
        advanceTimeBy(25)
        val second = async { loader.getRatings("movie", "tt2", credential) }

        assertEquals(8.1, first.await()?.imdb)
        assertEquals(6.5, second.await()?.imdb)
        assertEquals(3.9, loader.getRatings("movie", "tt1", credential)?.letterboxd)
        coVerify(exactly = 1) { api.getMediaBatch("movie", "test-key", MDBListMediaRequestDto(listOf("tt1", "tt2"))) }
        coVerify(exactly = 0) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `requests use at most two hundred unique ids per batch`() = runTest {
        val loader = loader()
        val ratings = (1..405).map { id -> async { loader.getRatings("movie", "tt$id", credential) } }.awaitAll()

        assertTrue(ratings.all { it?.imdb == 8.1 && it.letterboxd == 3.9 })
        assertEquals(listOf(200, 200, 5), batches.map { it.ids.size })
        assertEquals((1..405).map { "tt$it" }, batches.flatMap { it.ids })
        assertTrue(batches.all { it.appendToResponse == listOf("keyword") })
        coVerify(exactly = 0) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate items share one in flight request`() = runTest {
        val loader = loader()
        val ratings = (1..10).map { async { loader.getRatings("movie", "tt1", credential) } }.awaitAll()

        assertTrue(ratings.all { it?.imdb == 8.1 })
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getMediaBatch(any(), any(), any()) }
    }

    @Test
    fun `different media types and credentials use separate batches`() = runTest {
        val loader = loader()
        val otherKey = MdbListRatingsCredential.ApiKey("other-key")
        listOf("movie" to credential, "show" to credential, "movie" to otherKey).flatMap { (type, key) ->
            listOf("tt1", "tt2").map { id -> async { loader.getRatings(type, id, key) } }
        }.awaitAll()

        coVerify(exactly = 1) { api.getMediaBatch("movie", "test-key", any()) }
        coVerify(exactly = 1) { api.getMediaBatch("show", "test-key", any()) }
        coVerify(exactly = 1) { api.getMediaBatch("movie", "other-key", any()) }
        assertEquals(listOf(2, 2, 2), batches.map { it.ids.size })
    }

    @Test
    fun `omitted items stay empty without individual fallback requests`() = runTest {
        coEvery { api.getMediaBatch(any(), any(), any()) } returns Response.success(listOf(media.copy(imdbId = "tt1")))
        val loader = loader()
        val found = async { loader.getRatings("movie", "tt1", credential) }
        val missing = async { loader.getRatings("movie", "tt2", credential) }

        assertEquals(8.1, found.await()?.imdb)
        assertTrue(requireNotNull(missing.await()).isEmpty())
        assertTrue(requireNotNull(loader.getRatings("movie", "tt2", credential)).isEmpty())
        coVerify(exactly = 1) { api.getMediaBatch(any(), any(), any()) }
        coVerify(exactly = 0) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `expired ratings are refreshed once and reused before expiry`() = runTest {
        val loader = loader()
        assertEquals(8.1, loader.getRatings("movie", "tt1", credential)?.imdb)
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(
            media.copy(ratings = listOf(MDBListMediaRatingDto("imdb", 7.2)))
        )
        now = 30L * 60L * 1000L - 1
        assertEquals(8.1, loader.getRatings("movie", "tt1", credential)?.imdb)
        now++
        assertEquals(7.2, loader.getRatings("movie", "tt1", credential)?.imdb)
        coVerify(exactly = 2) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `failed and empty HTTP responses can be retried`() = runTest {
        val loader = loader()
        coEvery { api.getMedia(any(), any(), any(), any()) } throws IOException("Unavailable")
        assertNull(loader.getRatings("movie", "tt1", credential))
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(null)
        assertNull(loader.getRatings("movie", "tt1", credential))
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(media)

        assertEquals(8.1, loader.getRatings("movie", "tt1", credential)?.imdb)
        coVerify(exactly = 3) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `cancelling one caller leaves the shared request available`() = runTest {
        val loader = loader()
        val cancelled = async { loader.getRatings("movie", "tt1", credential) }
        runCurrent()
        val waiting = async { loader.getRatings("movie", "tt1", credential) }
        runCurrent()
        cancelled.cancel()

        assertEquals(8.1, waiting.await()?.imdb)
        assertTrue(cancelled.isCancelled)
        coVerify(exactly = 1) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `client cancellation propagates and allows retry`() = runTest {
        val loader = loader()
        coEvery { api.getMedia(any(), any(), any(), any()) } throws CancellationException("Cancelled")
        expectMdbListFailure<CancellationException> { loader.getRatings("movie", "tt1", credential) }
        coEvery { api.getMedia(any(), any(), any(), any()) } returns Response.success(media)

        assertEquals(8.1, loader.getRatings("movie", "tt1", credential)?.imdb)
        coVerify(exactly = 2) { api.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `account changes cancel queued lookups before dispatch`() = runTest {
        harness.connected()
        val original = requireNotNull(client.credential(""))
        val loader = loader()
        val pending = async { loader.getRatings("movie", "tt1", original) }
        runCurrent()
        harness.store.selectProfile(2)

        expectMdbListFailure<CancellationException> { pending.await() }
        assertTrue(harness.engine.requests.isEmpty())
    }

    @Test
    fun `account cache rejects stale scopes after profile changes`() = runTest {
        harness.connected()
        harness.reply(body = """{"ratings":[{"source":"imdb","value":8.1}]}""")
        val original = requireNotNull(client.credential(""))
        val loader = loader()
        assertEquals(8.1, loader.getRatings("movie", "tt1", original)?.imdb)
        harness.store.selectProfile(2)
        expectMdbListFailure<CancellationException> { loader.getRatings("movie", "tt1", original) }
        harness.connected("profile-two")
        harness.reply(body = """{"ratings":[{"source":"imdb","value":7.2}]}""")

        assertEquals(7.2, loader.getRatings("movie", "tt1", requireNotNull(client.credential("")))?.imdb)
        assertEquals(2, harness.engine.requests.size)
    }

    private fun TestScope.loader() = MdbListRatingsLoader(client, backgroundScope) { now }
}

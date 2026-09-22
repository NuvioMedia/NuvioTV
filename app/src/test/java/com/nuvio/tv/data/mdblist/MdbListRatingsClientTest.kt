package com.nuvio.tv.data.mdblist

import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MdbListRatingsClientTest {
    private val harness = MdbListTestHarness()
    private val keyApi = mockk<MDBListApi>()
    private val client = MdbListRatingsClient(
        keyApi, harness.api, harness.store, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    )
    private val body = MDBListRatingRequestDto(listOf("tt1234567"), "imdb")

    @Test
    fun `account metadata includes certification keywords without an api key`() = runTest {
        harness.connected()
        harness.reply(body = """{"ratings":[{"source":"tomatoes","value":92}],"keywords":[{"name":"certified-fresh"}]}""")

        val media = client.getMedia("movie", "tt1234567", requireNotNull(client.credential("")))

        assertEquals(92.0, media?.toRottenTomatoesRatings()?.tomatoes)
        assertTrue(media!!.toRottenTomatoesRatings().tomatoesCertified)
        val request = harness.engine.requests.single()
        assertEquals("/imdb/movie/tt1234567/", request.path)
        assertEquals(mapOf("append_to_response" to "keyword"), request.query)
        assertEquals("access-one", request.accessToken)
        coVerify(exactly = 0) { keyApi.getMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `account ratings refresh rejected tokens and preserve request body`() = runTest {
        harness.connected()
        harness.reply(401)
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)
        harness.reply(body = """{"ratings":[{"rating":8.1}]}""")

        val result = client.getRating("movie", "imdb", requireNotNull(client.credential("")), body)

        assertEquals(8.1, result?.ratings?.single()?.rating)
        assertEquals(listOf("/rating/movie/imdb", "/oauth/token/", "/rating/movie/imdb"), harness.engine.requests.map { it.path })
        assertEquals("access-two", harness.engine.requests.last().accessToken)
        assertEquals("""{"ids":["tt1234567"],"provider":"imdb"}""", harness.engine.requests.last().body)
        assertTrue(harness.engine.requests.last().query.isEmpty())
    }

    @Test
    fun `rejected override does not fall back to or disconnect the connected account`() = runTest {
        harness.connected()
        coEvery { keyApi.getRating("movie", "imdb", "override", body) } returns
            Response.error(401, "{}".toResponseBody())

        val error = expectMdbListFailure<MdbListApiException> {
            client.getRating("movie", "imdb", requireNotNull(client.credential(" override ")), body)
        }

        assertEquals(401, error.status)
        assertTrue(harness.engine.requests.isEmpty())
        assertTrue(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun `account change during a ratings request discards the stale response`() = runTest {
        harness.connected()
        harness.reply(body = """{"ratings":[{"rating":8.1}]}""")
        harness.engine.intercept = { harness.store.selectProfile(2) }

        expectMdbListFailure<CancellationException> {
            client.getRating("movie", "imdb", requireNotNull(client.credential("")), body)
        }
    }
}

package com.nuvio.tv.data.mediaserver.silo

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiloArtworkTest {
    private val stale = "https://silo.example.com/api/v2/artwork/a.jpg?exp=1000&sig=old"
    private val fresh = "https://silo.example.com/api/v2/artwork/a.jpg?exp=2000000000&sig=new"
    private var fetches = 0
    private val refresher = SiloArtworkRefresher(
        fetch = { fetches++; mapOf(SiloArtworkKind.POSTER to fresh) },
        now = { 1_500_000L }
    )

    @Test
    fun aLinkNamesItsTitle() {
        val link = SiloArtworkLink.mark(stale, "c1", "movie-tmdb-6.03", SiloArtworkKind.POSTER)

        val mark = SiloArtworkLink.parse(link.toHttpUrl())

        assertEquals(SiloArtworkMark("c1", "movie-tmdb-6.03", SiloArtworkKind.POSTER), mark)
        assertEquals(1000L, SiloArtworkLink.expiresAt(link.toHttpUrl()))
        assertNull(SiloArtworkLink.parse(stale.toHttpUrl()))
    }

    @Test
    fun anExpiredLinkIsSwappedBeforeItIsFetched() {
        val sent = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(SiloArtworkInterceptor({ refresher }, nowSeconds = { 1_500L }))
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ByteArray(0).toResponseBody()).build()
            }
            .build()
        val link = SiloArtworkLink.mark(stale, "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)

        client.newCall(Request.Builder().url(link).build()).execute().close()

        assertEquals("new", sent.single().url.queryParameter("sig"))
    }

    @Test
    fun aRefusedLinkIsTriedOnceMoreWithAFreshOne() {
        val sent = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(SiloArtworkInterceptor({ refresher }, nowSeconds = { 0L }))
            .addInterceptor { chain ->
                sent += chain.request()
                val code = if (chain.request().url.queryParameter("sig") == "old") 403 else 200
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .body(ByteArray(0).toResponseBody()).build()
            }
            .build()
        val link = SiloArtworkLink.mark(stale, "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)

        client.newCall(Request.Builder().url(link).build()).execute().close()

        assertEquals(listOf("old", "new"), sent.map { it.url.queryParameter("sig") })
    }

    @Test
    fun freshLinksAreKeptPerTitle() = runTest {
        val mark = SiloArtworkMark("c1", "movie-tmdb-603", SiloArtworkKind.POSTER)

        refresher.freshLink(mark, stale.toHttpUrl())
        refresher.freshLink(mark.copy(kind = SiloArtworkKind.BACKGROUND), stale.toHttpUrl())

        assertEquals(1, fetches)
    }
}

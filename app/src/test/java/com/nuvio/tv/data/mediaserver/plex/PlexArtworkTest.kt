package com.nuvio.tv.data.mediaserver.plex

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexArtworkTest {
    private val sent = mutableListOf<Request>()

    private fun client(access: PlexArtworkAccess?): OkHttpClient {
        val signer = PlexArtworkSigner { connectionId -> access.takeIf { connectionId == "c1" } }
        return OkHttpClient.Builder()
            .addInterceptor(PlexArtworkInterceptor { signer })
            .addInterceptor(PlexArtworkInterceptor.Token { signer })
            .addInterceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody())
                    .build()
            }
            .build()
    }

    private fun fetch(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute().close()

    @Test
    fun linksNameTheConnectionButCarryNoToken() {
        val link = PlexArtworkLink.picture("https://media.example:32400", "c1", "/library/metadata/1/thumb/2", 400, 600)!!

        assertTrue(link.startsWith("https://media.example:32400/photo/:/transcode?width=400&height=600"))
        assertTrue(link.endsWith("#nuvio-plex=c1"))
        assertEquals("https://image.tmdb.org/a.jpg", PlexArtworkLink.picture("https://media.example", "c1", "https://image.tmdb.org/a.jpg", 1, 1))
        assertNull(PlexArtworkLink.picture("https://media.example", "c1", "relative/path", 1, 1))
    }

    @Test
    fun signsMarkedLinksForTheConnectionsServer() {
        val link = PlexArtworkLink.picture("https://media.example:32400", "c1", "/library/metadata/1/thumb/2", 400, 600)!!

        fetch(client(PlexArtworkAccess("https://media.example:32400", "server-token")), link)

        assertEquals("server-token", sent.single().header("X-Plex-Token"))
    }

    @Test
    fun followsTheServerToItsCurrentAddress() {
        val link = PlexArtworkLink.picture("http://192.168.1.20:32400", "c1", "/library/metadata/1/thumb/2", 400, 600)!!

        fetch(client(PlexArtworkAccess("https://203-0-113-5.abc.plex.direct:32400", "server-token")), link)

        val request = sent.single()
        assertEquals("203-0-113-5.abc.plex.direct", request.url.host)
        assertEquals("/library/metadata/1/thumb/2", request.url.queryParameter("url"))
        assertEquals("server-token", request.header("X-Plex-Token"))
    }

    @Test
    fun leavesOtherLinksAlone() {
        fetch(client(PlexArtworkAccess("https://media.example", "server-token")), "https://image.tmdb.org/a.jpg")
        fetch(client(null), PlexArtworkLink.picture("https://media.example", "c1", "/thumb", 1, 1)!!)

        assertTrue(sent.none { it.header("X-Plex-Token") != null })
    }
}

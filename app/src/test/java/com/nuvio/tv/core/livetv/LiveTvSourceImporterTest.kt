package com.nuvio.tv.core.livetv

import com.nuvio.tv.domain.model.LiveTvSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveTvSourceImporterTest {
    @Test
    fun importsConfiguredPlaylistAndRejectsRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val importer = LiveTvSourceImporter(
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
            )
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1 tvg-id=\"br\",Canal Brasil\nhttps://example.org/live.m3u8\n"))
            val imported = importer.import(LiveTvSource("source-1", "Brasil", server.url("/list.m3u").toString()))
            assertEquals("Canal Brasil", imported.channels.single().displayName)

            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org/next"))
            val error = assertThrows(LiveTvSourceException::class.java) {
                runBlocking { importer.import(LiveTvSource("source-2", "Brasil", server.url("/redirect").toString())) }
            }
            assertEquals("A fonte redirecionou; atualize a URL configurada", error.message)
        }
    }

    @Test
    fun rejectsBodiesAbovePlaylistByteLimit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val importer = LiveTvSourceImporter(OkHttpClient())
            server.enqueue(MockResponse().setBody("x".repeat(M3uPlaylistParser.MAX_PLAYLIST_CHARS + 1)))
            val error = assertThrows(LiveTvSourceException::class.java) {
                runBlocking { importer.import(LiveTvSource("source-1", "Brasil", server.url("/large").toString())) }
            }
            assertEquals("A fonte excede o tamanho permitido", error.message)
        }
    }
}

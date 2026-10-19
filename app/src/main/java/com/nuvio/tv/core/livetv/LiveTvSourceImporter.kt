package com.nuvio.tv.core.livetv

import com.nuvio.tv.domain.model.LiveTvImportResult
import com.nuvio.tv.domain.model.LiveTvSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Fetches explicitly configured user sources with strict TLS, bounded time and bounded bodies. */
@Singleton
class LiveTvSourceImporter @Inject constructor(
    @Named("liveTv") private val client: OkHttpClient,
) {
    suspend fun import(source: LiveTvSource): LiveTvImportResult {
        val playlist = fetch(source.playlistUrl, M3uPlaylistParser.MAX_PLAYLIST_CHARS)
        val channels = M3uPlaylistParser.parse(playlist, source.id)
        val epg = source.epgUrl?.let { url ->
            XmlTvParser.parse(fetch(url, XmlTvParser.MAX_DOCUMENT_CHARS), source.id)
        }
        return if (epg == null) channels else channels.copy(
            channels = mergeEpgOnlyChannels(channels.channels, epg.channels),
            programs = epg.programs,
            skippedEntries = channels.skippedEntries + epg.skippedEntries,
        )
    }

    private suspend fun fetch(url: String, maxChars: Int): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw LiveTvSourceException("A fonte redirecionou; atualize a URL configurada")
            if (!response.isSuccessful) throw LiveTvSourceException("A fonte respondeu com erro HTTP ${response.code}")
            val body = response.body ?: throw LiveTvSourceException("A fonte retornou uma resposta vazia")
            if (body.contentLength() > maxChars.toLong()) throw LiveTvSourceException("A fonte excede o tamanho permitido")
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream(minOf(maxChars, 64 * 1024))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxChars) throw LiveTvSourceException("A fonte excede o tamanho permitido")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8)
            if (text.length > maxChars) throw LiveTvSourceException("A fonte excede o tamanho permitido")
            text
        }
    }

    private fun mergeEpgOnlyChannels(
        playlistChannels: List<com.nuvio.tv.domain.model.LiveTvChannel>,
        epgChannels: List<com.nuvio.tv.domain.model.LiveTvChannel>,
    ): List<com.nuvio.tv.domain.model.LiveTvChannel> {
        val ids = playlistChannels.mapNotNullTo(HashSet()) { it.tvgId?.takeIf(String::isNotBlank) }
        return playlistChannels + epgChannels.filter { channel ->
            channel.streamUrl == null && channel.tvgId?.let(ids::contains) != true
        }
    }
}

class LiveTvSourceException(message: String) : IOException(message)

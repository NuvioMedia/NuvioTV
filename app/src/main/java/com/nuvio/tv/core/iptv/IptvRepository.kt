package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.IptvPreferencesDataStore
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class IptvRepository @Inject constructor(
    @Named("addonPermissive") private val httpClient: OkHttpClient,
    private val preferences: IptvPreferencesDataStore
) {
    private var cachedChannels: List<IptvChannel>? = null
    private var cachedPlaylistUrl: String? = null

    private var cachedProgrammes: Map<String, List<EpgProgramme>>? = null
    private var cachedEpgUrl: String? = null

    suspend fun getChannels(forceRefresh: Boolean = false): Result<List<IptvChannel>> = withContext(Dispatchers.IO) {
        val url = preferences.playlistUrl.first()
        if (url.isBlank()) return@withContext Result.failure(IllegalStateException("no_playlist_url"))
        val cached = cachedChannels
        if (!forceRefresh && cached != null && cachedPlaylistUrl == url) {
            return@withContext Result.success(cached)
        }
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("empty_response"))
                val channels = M3uParser.parse(body)
                cachedChannels = channels
                cachedPlaylistUrl = url
                Result.success(channels)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getProgrammesByChannel(forceRefresh: Boolean = false): Map<String, List<EpgProgramme>> =
        withContext(Dispatchers.IO) {
            val url = preferences.epgUrl.first()
            if (url.isBlank()) return@withContext emptyMap()
            val cached = cachedProgrammes
            if (!forceRefresh && cached != null && cachedEpgUrl == url) {
                return@withContext cached
            }
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyMap()
                    val stream = response.body?.byteStream() ?: return@withContext emptyMap()
                    val grouped = XmltvParser.parse(stream).groupBy { it.channelId }
                    cachedProgrammes = grouped
                    cachedEpgUrl = url
                    grouped
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
}

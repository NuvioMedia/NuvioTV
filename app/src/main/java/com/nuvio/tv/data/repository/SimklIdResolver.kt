package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.simkl.SimklApiConfiguration
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private data class RedirectResult(val type: String, val simklId: Long)

private fun parseRedirectLocation(location: String): RedirectResult? {
    val segments = location.substringBefore('?').split('/')
    val typeIndex = segments.indexOfLast { it == "anime" || it == "tv" || it == "movies" }
    val type = segments.getOrNull(typeIndex) ?: return null
    val id = segments.getOrNull(typeIndex + 1)?.toLongOrNull() ?: return null
    return RedirectResult(type, id)
}

@Singleton
class SimklIdResolver @Inject constructor(
    @Named("simkl") private val okHttpClient: OkHttpClient,
    private val simklConfig: SimklApiConfiguration
) {
    private val clientId = simklConfig.clientId
    private val appName = simklConfig.appName
    private val appVersion = simklConfig.appVersion

    data class ResolvedIds(
        val simklId: Long,
        val type: String,
        val mal: String? = null,
        val anilist: String? = null,
        val kitsu: String? = null,
        val imdb: String? = null,
        val tvdbSeason: Int? = null
    )

    data class EpisodeMapping(
        val animeEpisode: Int,
        val tvdbSeason: Int,
        val tvdbEpisode: Int
    )

    private val idsCache = ConcurrentHashMap<String, ResolvedIds?>()
    private val episodeCache = ConcurrentHashMap<Long, List<EpisodeMapping>>()

    suspend fun resolveIds(source: String, id: String): ResolvedIds? {
        val cacheKey = "$source:$id"
        idsCache[cacheKey]?.let { return it }
        if (clientId.isBlank()) return null

        return try {
            val redirect = resolveViaRedirect(source, id) ?: return null

            val detailsBody = httpGet("$baseUrl/${redirect.type}/${redirect.simklId}?extended=full&${commonParams()}") ?: return null
            val details = JSONObject(detailsBody)
            val ids = details.optJSONObject("ids")

            ResolvedIds(
                simklId = redirect.simklId,
                type = redirect.type,
                mal = ids?.optString("mal")?.takeIf { it.isNotBlank() },
                anilist = ids?.optString("anilist")?.takeIf { it.isNotBlank() },
                kitsu = ids?.optString("kitsu")?.takeIf { it.isNotBlank() },
                imdb = ids?.optString("imdb")?.takeIf { it.isNotBlank() },
                tvdbSeason = details.optInt("season", -1).takeIf { it > 0 }
            ).also { idsCache[cacheKey] = it }
        } catch (e: Exception) {
            Log.d(TAG, "resolveIds $source:$id failed: ${e.message}")
            null
        }
    }

    suspend fun getEpisodeMapping(simklId: Long, type: String = "anime"): List<EpisodeMapping> {
        episodeCache[simklId]?.let { return it }
        if (clientId.isBlank()) return emptyList()

        return try {
            val body = httpGet("$baseUrl/$type/episodes/$simklId?${commonParams()}") ?: return emptyList()
            val episodes = JSONArray(body)
            val mapping = mutableListOf<EpisodeMapping>()
            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                val epNum = ep.optInt("episode", -1)
                val tvdb = ep.optJSONObject("tvdb") ?: continue
                val tvdbSeason = tvdb.optInt("season", -1)
                val tvdbEpisode = tvdb.optInt("episode", -1)
                if (epNum > 0 && tvdbSeason > 0 && tvdbEpisode > 0) {
                    mapping.add(EpisodeMapping(epNum, tvdbSeason, tvdbEpisode))
                }
            }
            mapping.also { episodeCache[simklId] = it }
        } catch (e: Exception) {
            Log.d(TAG, "getEpisodeMapping $type:$simklId failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun resolveEpisodeTvdb(source: String, id: String, episode: Int): Pair<Int, Int>? {
        val ids = resolveIds(source, id) ?: return null
        val entry = getEpisodeMapping(ids.simklId, ids.type).firstOrNull { it.animeEpisode == episode }
        return entry?.let { it.tvdbSeason to it.tvdbEpisode }
    }

    private suspend fun resolveViaRedirect(source: String, id: String): RedirectResult? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "$baseUrl/redirect?to=simkl&$source=$id&${commonParams()}"
            val noRedirectClient = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder().url(url).get().build()
            val response = noRedirectClient.newCall(request).execute()
            val location = response.header("Location")
            response.close()
            location?.let { parseRedirectLocation(it) }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun httpGet(url: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun commonParams() = "client_id=$clientId&app-name=$appName&app-version=$appVersion"
    private val baseUrl get() = simklConfig.baseUrl

    companion object {
        private const val TAG = "SimklIdResolver"
    }
}

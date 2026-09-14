package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.simkl.SimklApiConfiguration
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private val noRedirectClient = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    data class ResolvedIds(
        val simklId: Long,
        val type: String,
        val mal: String? = null,
        val anilist: String? = null,
        val kitsu: String? = null,
        val imdb: String? = null,
        val tvdbSeason: Int? = null,
        val tvdb: String? = null
    )

    data class EpisodeMapping(
        val animeEpisode: Int,
        val tvdbSeason: Int,
        val tvdbEpisode: Int
    )

    private val idsCache = ConcurrentHashMap<String, ResolvedIds?>()
    data class AnimeEpisode(val ids: ResolvedIds, val episode: Int)

    private data class AnimeDetails(val ids: ResolvedIds, val related: List<Long>)
    private val detailsCache = ConcurrentHashMap<String, AnimeDetails>()
    private val episodeCache = ConcurrentHashMap<String, List<EpisodeMapping>>()
    private val animeEpisodeCache = ConcurrentHashMap<String, AnimeEpisode>()
    private val mappingRequests = Semaphore(4)

    suspend fun resolveIds(source: String, id: String): ResolvedIds? {
        val cacheKey = "$source:$id"
        idsCache[cacheKey]?.let { return it }
        if (clientId.isBlank()) return null

        return try {
            val redirect = resolveViaRedirect(source, id) ?: return null

            loadDetails(redirect.type, redirect.simklId)?.ids?.also { idsCache[cacheKey] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "resolveIds $source:$id failed: ${e.message}")
            null
        }
    }

    suspend fun getEpisodeMapping(simklId: Long, type: String = "anime"): List<EpisodeMapping> {
        return loadEpisodeMapping(simklId, type).orEmpty()
    }

    private suspend fun loadEpisodeMapping(simklId: Long, type: String): List<EpisodeMapping>? {
        val cacheKey = "$type:$simklId"
        episodeCache[cacheKey]?.let { return it }
        if (clientId.isBlank()) return null

        return try {
            val body = httpGet("$baseUrl/$type/episodes/$simklId?${commonParams()}") ?: return null
            val episodes = JSONArray(body)
            val mapping = mutableListOf<EpisodeMapping>()
            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                // Simkl specials have a separate numbering namespace, not MAL episode numbers.
                if (ep.optString("type") != "episode") continue
                val epNum = ep.optInt("episode", -1)
                val tvdb = ep.optJSONObject("tvdb") ?: continue
                val tvdbSeason = tvdb.optInt("season", -1)
                val tvdbEpisode = tvdb.optInt("episode", -1)
                if (epNum > 0 && tvdbSeason >= 0 && tvdbEpisode > 0) {
                    mapping.add(EpisodeMapping(epNum, tvdbSeason, tvdbEpisode))
                }
            }
            mapping.distinct().also { episodeCache[cacheKey] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "getEpisodeMapping $type:$simklId failed: ${e.message}")
            null
        }
    }

    suspend fun resolveEpisodeTvdb(source: String, id: String, episode: Int): Pair<Int, Int>? {
        val ids = resolveIds(source, id) ?: return null
        val entry = getEpisodeMapping(ids.simklId, ids.type).filter { it.animeEpisode == episode }.singleOrNull()
        return entry?.let { it.tvdbSeason to it.tvdbEpisode }
    }

    suspend fun resolveAnimeEpisode(imdbId: String, season: Int, episode: Int): AnimeEpisode? = coroutineScope {
        if (season < 0 || episode <= 0) return@coroutineScope null
        val cacheKey = "$imdbId:$season:$episode"
        animeEpisodeCache[cacheKey]?.let { return@coroutineScope it }
        val root = resolveIds("imdb", imdbId)?.takeIf { it.type == "anime" } ?: return@coroutineScope null
        val entries = linkedMapOf<Long, AnimeDetails>()
        val visited = mutableSetOf<Long>()
        var pending = listOf(root.simklId)
        while (pending.isNotEmpty()) {
            if (visited.size + pending.size > 40) return@coroutineScope null
            visited.addAll(pending)
            val details = pending.map { id -> async { mappingRequests.withPermit { loadDetails("anime", id) } } }.awaitAll()
            // Incomplete data cannot establish that a mapping is unique.
            if (details.any { it == null }) return@coroutineScope null
            val matching = details.filterNotNull().filter { entry ->
                val ids = entry.ids
                // Later anime entries can have their own IMDb ID but share the TVDB episode namespace.
                if (root.tvdb != null) ids.tvdb == root.tvdb else ids.imdb == imdbId
            }
            matching.forEach { entries[it.ids.simklId] = it }
            pending = matching.flatMap { it.related }.distinct().filterNot { it in visited }
        }
        val matches = entries.values.map { entry -> async {
            val mapping = mappingRequests.withPermit { loadEpisodeMapping(entry.ids.simklId, entry.ids.type) }
                ?: return@async null
            mapping.filter { it.tvdbSeason == season && it.tvdbEpisode == episode }
                .map { AnimeEpisode(entry.ids, it.animeEpisode) }
        } }.awaitAll()
        if (matches.any { it == null }) return@coroutineScope null
        matches.filterNotNull().flatten().distinct().singleOrNull()?.also {
            animeEpisodeCache[cacheKey] = it
        }
    }

    private suspend fun loadDetails(type: String, simklId: Long): AnimeDetails? {
        val key = "$type:$simklId"
        detailsCache[key]?.let { return it }
        return try {
            val body = httpGet("$baseUrl/$type/$simklId?extended=full_anime_seasons&${commonParams()}") ?: return null
            val details = JSONObject(body)
            val ids = details.optJSONObject("ids") ?: return null
            fun id(name: String) = ids.optString(name).takeIf { it.isNotBlank() && it != "null" }
            val resolved = ResolvedIds(
                simklId, type, id("mal"), id("anilist"), id("kitsu"), id("imdb"),
                details.optInt("season", -1).takeIf { it >= 0 }, id("tvdb")
            )
            val relations = details.optJSONArray("relations")
            val related = buildList {
                if (relations != null) for (i in 0 until relations.length()) {
                    val relationId = relations.optJSONObject(i)?.optJSONObject("ids")?.optLong("simkl", -1)
                    if (relationId != null && relationId > 0) add(relationId)
                }
            }
            AnimeDetails(resolved, related.distinct()).also { detailsCache[key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Anime mapping details unavailable for $type:$simklId")
            null
        }
    }

    private suspend fun resolveViaRedirect(source: String, id: String): RedirectResult? {
        val url = "$baseUrl/redirect?to=simkl&$source=$id&${commonParams()}"
        return requestText(url, redirect = true)?.let(::parseRedirectLocation)
    }

    private suspend fun httpGet(url: String): String? = requestText(url)

    private suspend fun requestText(url: String, redirect: Boolean = false): String? = suspendCancellableCoroutine { continuation ->
        val client = if (redirect) noRedirectClient else okHttpClient
        val call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (redirect) it.header("Location") else if (it.isSuccessful) it.body.string() else null
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun commonParams() = "client_id=$clientId&app-name=$appName&app-version=$appVersion"
    private val baseUrl get() = simklConfig.baseUrl

    companion object {
        private const val TAG = "SimklIdResolver"
    }
}

package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipRequest
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "op", "ed", "recap", "outro", "mixed-op", "mixed-ed"
    val provider: String   // "introdb", "aniskip", "animeskip"
)

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val aniSkipApi: AniSkipApi,
    private val animeSkipApi: AnimeSkipApi,
    private val armApi: ArmApi,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val malIdCache = ConcurrentHashMap<String, String>()
    private val animeSkipShowIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> {
        if (imdbId == null) return emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return it }

        if (introDbConfigured) {
            val result = fetchFromIntroDb(imdbId, season, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        val malId = resolveMalId(imdbId)
        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        // AnimeSkip: try season-specific AniList ID first, then season-1 as fallback
        val anilistIds = resolveAllAnilistIdsFromImdb(imdbId)
        val toTry = listOfNotNull(
            anilistIds.getOrNull(season - 1),
            anilistIds.firstOrNull()
        ).distinct()
        for (anilistId in toTry) {
            val result = fetchFromAnimeSkip(anilistId, episode, season = null)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> {
        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return it }

        val aniSkipResult = fetchFromAniSkip(malId, episode)
        if (aniSkipResult.isNotEmpty()) return aniSkipResult.also { cache[cacheKey] = it }

        val directAnilistId = try {
            armApi.resolveMalToAnilist(malId = malId)
                .takeIf { it.isSuccessful }?.body()?.anilist?.toString()
        } catch (e: Exception) { null }

        if (directAnilistId != null) {
            val result = fetchFromAnimeSkip(directAnilistId, episode, season = null)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        val imdbId = try {
            armApi.resolveMalToImdb(malId = malId)
                .takeIf { it.isSuccessful }?.body()?.imdb
        } catch (e: Exception) { null }

        if (imdbId != null) {
            val firstAnilistId = resolveAllAnilistIdsFromImdb(imdbId).firstOrNull()
            if (firstAnilistId != null) {
                val result = fetchFromAnimeSkip(firstAnilistId, episode, season = null)
                if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
            }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> {
        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return it }

        val malId = try {
            armApi.resolveKitsuToMal(kitsuId = kitsuId)
                .takeIf { it.isSuccessful }?.body()?.myanimelist?.toString()
        } catch (e: Exception) { null }

        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        // AnimeSkip: try direct AniList ID first (season-specific, no season filter needed)
        val directAnilistId = try {
            armApi.resolveKitsuToAnilist(kitsuId = kitsuId)
                .takeIf { it.isSuccessful }?.body()?.anilist?.toString()
        } catch (e: Exception) { null }

        if (directAnilistId != null) {
            val result = fetchFromAnimeSkip(directAnilistId, episode, season = null)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        // Fallback: Kitsu -> IMDB -> first AniList ID (season 1 show)
        val imdbId = try {
            armApi.resolveKitsuToImdb(kitsuId = kitsuId)
                .takeIf { it.isSuccessful }?.body()?.imdb
        } catch (e: Exception) { null }

        if (imdbId != null) {
            val firstAnilistId = resolveAllAnilistIdsFromImdb(imdbId).firstOrNull()
            if (firstAnilistId != null) {
                val result = fetchFromAnimeSkip(firstAnilistId, episode, season = null)
                if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
            }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode)
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                listOfNotNull(
                    data.intro.toSkipIntervalOrNull("intro"),
                    data.recap.toSkipIntervalOrNull("recap"),
                    data.outro.toSkipIntervalOrNull("outro")
                )
            } else emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: no data for $imdbId S${season}E${episode}")
            emptyList()
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
            val response = aniSkipApi.getSkipTimes(malId, episode, types)
            if (response.isSuccessful && response.body()?.found == true) {
                response.body()!!.results?.map { result ->
                    SkipInterval(
                        startTime = result.interval.startTime,
                        endTime = result.interval.endTime,
                        type = result.skipType,
                        provider = "aniskip"
                    )
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AniSkip: no data for MAL $malId ep $episode")
            emptyList()
        }
    }

    // season: null when anilistId is season-specific; pass season when using season-1 show ID
    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val clientId = animeSkipSettingsDataStore.clientId.firstOrNull()?.trim()
        if (clientId.isNullOrBlank()) return emptyList()
        val enabled = animeSkipSettingsDataStore.enabled.firstOrNull() ?: false
        if (!enabled) return emptyList()
        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val episodesResponse = animeSkipApi.query(
                    clientId = clientId,
                    body = AnimeSkipRequest(
                        query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                    )
                )
                if (!episodesResponse.isSuccessful) continue

                val episodes = episodesResponse.body()?.data?.findEpisodesByShowId ?: continue
                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits" -> "ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AnimeSkip: error for anilist $anilistId ep $episode: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val showIds = try {
            animeSkipApi.query(
                clientId = clientId,
                body = AnimeSkipRequest(
                    query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
                )
            ).body()?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        // cache only if single result; multi-show case skip cache to avoid complexity
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    private suspend fun resolveMalId(imdbId: String): String? {
        val cached = malIdCache[imdbId]
        if (cached != null) return cached.takeIf { it != NO_ID }
        val malId = try {
            armApi.resolveImdbToMal(imdbId)
                .takeIf { it.isSuccessful }?.body()?.firstOrNull()?.myanimelist?.toString()
        } catch (e: Exception) { null }
        malIdCache[imdbId] = malId ?: NO_ID
        return malId
    }

    private suspend fun resolveAllAnilistIdsFromImdb(imdbId: String): List<String> {
        return try {
            armApi.resolveImdbToAnilist(imdbId)
                .takeIf { it.isSuccessful }
                ?.body()?.mapNotNull { it.anilist?.toString() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    companion object {
        private const val NO_ID = "__none__"
    }
}

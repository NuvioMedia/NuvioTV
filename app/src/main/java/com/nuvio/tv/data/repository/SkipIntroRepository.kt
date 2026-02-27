package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "op", "ed", "recap", "outro", "mixed-op", "mixed-ed"
    val provider: String   // "introdb" or "aniskip"
)

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val aniSkipApi: AniSkipApi,
    private val armApi: ArmApi
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val malIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<SkipInterval> {
        if (imdbId == null) return emptyList()

        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return it }

        // 1. Try IntroDB first (TV shows)
        if (introDbConfigured) {
            val introDbResult = fetchFromIntroDb(imdbId, season, episode)
            if (introDbResult.isNotEmpty()) {
                cache[cacheKey] = introDbResult
                return introDbResult
            }
        }

        // 2. Try AniSkip (anime) - resolve MAL ID from IMDB via ARM
        val malId = resolveMalId(imdbId, season)
        if (malId != null) {
            val aniSkipResult = fetchFromAniSkip(malId, episode)
            if (aniSkipResult.isNotEmpty()) {
                cache[cacheKey] = aniSkipResult
                return aniSkipResult
            }
        }

        // Cache empty result to avoid repeated lookups
        cache[cacheKey] = emptyList()
        return emptyList()
    }

    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> {
        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return it }
        val result = fetchFromAniSkip(malId, episode)
        cache[cacheKey] = result
        return result
    }

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> {
        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return it }
        val malId = resolveKitsuId(kitsuId)
        val result = if (malId != null) fetchFromAniSkip(malId, episode) else emptyList()
        cache[cacheKey] = result
        return result
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode)
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                listOfNotNull(
                    data.intro.toSkipIntervalOrNull(type = "intro"),
                    data.recap.toSkipIntervalOrNull(type = "recap"),
                    data.outro.toSkipIntervalOrNull(type = "outro")
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

        return SkipInterval(
            startTime = start,
            endTime = end,
            type = type,
            provider = "introdb"
        )
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

    private suspend fun resolveMalId(imdbId: String, season: Int): String? {
        val cacheKey = "$imdbId:$season"
        val cached = malIdCache[cacheKey]
        if (cached != null) return cached.takeIf { it != NO_MAL_ID }

        val malId = try {
            val response = armApi.resolveByImdb(imdbId)
            if (response.isSuccessful) {
                val entries = response.body() ?: emptyList()
                (entries.getOrNull(season - 1) ?: entries.firstOrNull())?.myanimelist?.toString()
            } else null
        } catch (e: Exception) { null }

        malIdCache[cacheKey] = malId ?: NO_MAL_ID
        return malId
    }

    private suspend fun resolveKitsuId(kitsuId: String): String? {
        val cacheKey = "kitsu:$kitsuId"
        val cached = malIdCache[cacheKey]
        if (cached != null) return cached.takeIf { it != NO_MAL_ID }

        val malId = try {
            val response = armApi.resolveByKitsu(kitsuId = kitsuId)
            if (response.isSuccessful) {
                response.body()?.myanimelist?.toString()
            } else null
        } catch (e: Exception) { null }

        malIdCache[cacheKey] = malId ?: NO_MAL_ID
        return malId
    }

    companion object {
        private const val NO_MAL_ID = "__none__"
    }
}

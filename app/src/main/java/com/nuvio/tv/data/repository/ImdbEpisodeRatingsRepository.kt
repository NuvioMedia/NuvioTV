package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val imdbTapframeApi: ImdbTapframeApi,
    private val seriesGraphApi: SeriesGraphApi
) {
    private data class CacheEntry(
        val episodes: List<ExternalEpisodeRating>,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<List<ExternalEpisodeRating>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal suspend fun getEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?,
        addonEpisodes: List<AddonEpisodeAirDate>
    ): Map<Pair<Int, Int>, Double> {
        return alignEpisodeRatings(addonEpisodes, getExternalEpisodes(imdbId, tmdbId))
    }

    private suspend fun getExternalEpisodes(
        imdbId: String?,
        tmdbId: Int?
    ): List<ExternalEpisodeRating> {
        val normalizedImdbId = imdbId
            ?.trim()
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?.substringBefore(':')
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 }
        if (normalizedImdbId == null && normalizedTmdbId == null) return emptyList()

        val cacheKey = when {
            !normalizedImdbId.isNullOrBlank() -> "imdb:$normalizedImdbId"
            else -> "tmdb:$normalizedTmdbId"
        }

        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.episodes
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchEpisodeRatings(
                        imdbId = normalizedImdbId,
                        tmdbId = normalizedTmdbId
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            episodes = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private suspend fun fetchEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): List<ExternalEpisodeRating> {
        if (!imdbId.isNullOrBlank()) {
            val primary = fetchFromImdbTapframe(imdbId)
            if (primary.any { it.rating != null }) return primary
            Log.w(tag, "Primary episode ratings empty for imdbId=$imdbId, trying fallback.")
        }

        if (tmdbId != null && tmdbId > 0) {
            return fetchFromSeriesGraph(tmdbId)
        }

        return emptyList()
    }

    private suspend fun fetchFromImdbTapframe(imdbId: String): List<ExternalEpisodeRating> {
        return try {
            val response = imdbTapframeApi.getSeasonRatings(imdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed primary season ratings for imdbId=$imdbId (${response.code()})")
                return emptyList()
            }
            toEpisodeRatings(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching primary season ratings for imdbId=$imdbId", e)
            emptyList()
        }
    }

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): List<ExternalEpisodeRating> {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed fallback season ratings for tmdbId=$tmdbId (${response.code()})")
                return emptyList()
            }
            toEpisodeRatings(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching fallback season ratings for tmdbId=$tmdbId", e)
            emptyList()
        }
    }

    private fun toEpisodeRatings(payload: List<com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto>): List<ExternalEpisodeRating> {
        return payload.flatMap { season ->
            season.episodes.orEmpty().mapNotNull { episode ->
                ExternalEpisodeRating(
                    season = episode.seasonNumber ?: return@mapNotNull null,
                    episode = episode.episodeNumber ?: return@mapNotNull null,
                    airDate = episode.airDate,
                    rating = episode.voteAverage
                )
            }
        }
    }
}

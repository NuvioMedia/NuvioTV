package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListRepository @Inject constructor(
    private val api: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val result: MDBListRatingsResult?,
        val expiresAtMs: Long
    )

    private enum class ProviderType(val apiValue: String) {
        TRAKT("trakt"),
        IMDB("imdb"),
        TMDB("tmdb"),
        LETTERBOXD("letterboxd"),
        TOMATOES("tomatoes"),
        AUDIENCE("audience"),
        METACRITIC("metacritic")
    }

    private val tag = "MDBListRepository"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<MDBListRatingsResult?>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lightweight helper for home screen enrichment - fetches only the IMDb rating. */
    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return null

        val mediaType = normalizeMediaType(itemType)
        val imdbId = resolveImdbId(
            meta = Meta(
                id = itemId,
                type = when (normalizeMediaType(itemType)) {
                    "show" -> com.nuvio.tv.domain.model.ContentType.SERIES
                    else -> com.nuvio.tv.domain.model.ContentType.MOVIE
                },
                name = itemId,
                poster = null,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            ),
            fallbackItemId = itemId,
            fallbackItemType = itemType,
            mediaType = mediaType
        ) ?: return null

        val cacheKey = "$mediaType:$imdbId:imdb:${apiKey.hashCode()}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.result?.ratings?.imdb
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchRatings(
                        imdbId = imdbId,
                        mediaType = mediaType,
                        apiKey = apiKey,
                        providers = listOf(ProviderType.IMDB)
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            result = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { inFlight[cacheKey] = it }
        }
        return deferred.await()?.ratings?.imdb
    }

    suspend fun getRatingsForMeta(
        meta: Meta,        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null

        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return null

        val enabledProviders = enabledProviders(settings)
        if (enabledProviders.isEmpty()) return null

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val imdbId = resolveImdbId(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val providerHash = enabledProviders.map { it.apiValue }.sorted().joinToString(",")
        val cacheKey = "$mediaType:$imdbId:$providerHash:${apiKey.hashCode()}"
        val now = System.currentTimeMillis()

        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.result
            }
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchRatings(
                        imdbId = imdbId,
                        mediaType = mediaType,
                        apiKey = apiKey,
                        providers = enabledProviders
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            result = result,
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

    private suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String,
        providers: List<ProviderType>
    ): MDBListRatingsResult? {
        val semaphore = Semaphore(4)
        val requestBody = MDBListRatingRequestDto(
            ids = listOf(imdbId),
            provider = "imdb"
        )

        val results = providers.map { provider ->
            scope.async {
                semaphore.withPermit {
                    fetchProviderRating(
                        mediaType = mediaType,
                        provider = provider,
                        apiKey = apiKey,
                        requestBody = requestBody
                    )
                }
            }
        }.awaitAll().toMap()

        val ratings = MDBListRatings(
            trakt = results[ProviderType.TRAKT],
            imdb = results[ProviderType.IMDB],
            tmdb = results[ProviderType.TMDB],
            letterboxd = results[ProviderType.LETTERBOXD],
            tomatoes = results[ProviderType.TOMATOES],
            audience = results[ProviderType.AUDIENCE],
            metacritic = results[ProviderType.METACRITIC]
        )

        if (ratings.isEmpty()) return null

        return MDBListRatingsResult(
            ratings = ratings,
            hasImdbRating = ratings.imdb != null
        )
    }

    private suspend fun fetchProviderRating(
        mediaType: String,
        provider: ProviderType,
        apiKey: String,
        requestBody: MDBListRatingRequestDto
    ): Pair<ProviderType, Double?> {
        return try {
            val response = api.getRating(
                mediaType = mediaType,
                ratingType = provider.apiValue,
                apiKey = apiKey,
                body = requestBody
            )

            if (!response.isSuccessful) {
                Log.w(tag, "Failed ${provider.apiValue} (${response.code()})")
                return provider to null
            }

            val rating = response.body()?.ratings?.firstOrNull()?.rating
            provider to rating
        } catch (e: Exception) {
            Log.w(tag, "Error fetching ${provider.apiValue}", e)
            provider to null
        }
    }

    private fun enabledProviders(settings: MDBListSettings): List<ProviderType> = buildList {
        if (settings.showTrakt) add(ProviderType.TRAKT)
        if (settings.showImdb) add(ProviderType.IMDB)
        if (settings.showTmdb) add(ProviderType.TMDB)
        if (settings.showLetterboxd) add(ProviderType.LETTERBOXD)
        if (settings.showTomatoes) add(ProviderType.TOMATOES)
        if (settings.showAudience) add(ProviderType.AUDIENCE)
        if (settings.showMetacritic) add(ProviderType.METACRITIC)
    }

    private suspend fun resolveImdbId(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): String? {
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbId = extractTmdbId(meta.id)
            ?: extractTmdbId(fallbackItemId)
            ?: meta.id.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: fallbackItemId.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()

        if (tmdbId != null) {
            val mapped = tmdbService.tmdbToImdb(tmdbId, fallbackItemType)
            if (!mapped.isNullOrBlank()) return mapped
        }

        val lookupType = if (fallbackItemType.isNotBlank()) fallbackItemType else mediaType
        val converted = tmdbService.ensureTmdbId(meta.id, lookupType)?.toIntOrNull()?.let { tmdbNumericId ->
            tmdbService.tmdbToImdb(tmdbNumericId, lookupType)
        }
        return converted?.takeIf { it.startsWith("tt") }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun extractTmdbId(rawId: String?): Int? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').toIntOrNull()
        }
        return null
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }
}

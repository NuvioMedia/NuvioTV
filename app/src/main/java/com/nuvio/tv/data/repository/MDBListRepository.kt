package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListRatingsClient
import com.nuvio.tv.data.mdblist.MdbListRatingsCredential
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val api: MdbListRatingsClient,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private data class CacheKey(
        val mediaType: String,
        val imdbId: String,
        val providers: String,
        val credential: MdbListRatingsCredential
    )

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
        METACRITIC("metacritic"),
        MAL("mal")
    }

    private val tag = "MDBListRepository"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()
    private val inFlight = mutableMapOf<CacheKey, kotlinx.coroutines.Deferred<MDBListRatingsResult?>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isAvailable(settings: MDBListSettings): Boolean = settings.enabled && api.credential(settings.apiKey) != null

    /** Lightweight helper for home screen enrichment - fetches only the IMDb rating. */
    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        val credential = api.credential(settings.apiKey) ?: return null

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

        val cacheKey = CacheKey(mediaType, imdbId, "imdb", credential)
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
                        credential = credential,
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

        val credential = api.credential(settings.apiKey) ?: return null

        val enabledProviders = enabledProviders(settings)
        if (enabledProviders.isEmpty()) return null

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val imdbId = resolveImdbId(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val providerHash = enabledProviders.map { it.apiValue }.sorted().joinToString(",")
        val cacheKey = CacheKey(mediaType, imdbId, providerHash, credential)
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
                        credential = credential,
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
        credential: MdbListRatingsCredential,
        providers: List<ProviderType>
    ): MDBListRatingsResult? = coroutineScope {
        val semaphore = Semaphore(4)
        val requestBody = MDBListRatingRequestDto(
            ids = listOf(imdbId),
            provider = "imdb"
        )

        val rottenTomatoesRatings = if (providers.any { it == ProviderType.TOMATOES || it == ProviderType.AUDIENCE }) {
            async {
                semaphore.withPermit { fetchRottenTomatoesRatings(imdbId, mediaType, credential) }
            }
        } else {
            null
        }
        val results = providers.map { provider ->
            async {
                val rating = when (provider) {
                    ProviderType.TOMATOES -> rottenTomatoesRatings?.await()?.tomatoes
                    ProviderType.AUDIENCE -> rottenTomatoesRatings?.await()?.audience
                    else -> null
                }
                if (rating != null) {
                    provider to rating
                } else semaphore.withPermit {
                    fetchProviderRating(
                        mediaType = mediaType,
                        provider = provider,
                        credential = credential,
                        requestBody = requestBody
                    )
                }
            }
        }.awaitAll().toMap()

        val certifications = rottenTomatoesRatings?.await()
        val ratings = MDBListRatings(
            trakt = results[ProviderType.TRAKT],
            imdb = results[ProviderType.IMDB],
            tmdb = results[ProviderType.TMDB],
            letterboxd = results[ProviderType.LETTERBOXD],
            tomatoes = results[ProviderType.TOMATOES],
            audience = results[ProviderType.AUDIENCE],
            metacritic = results[ProviderType.METACRITIC],
            mal = results[ProviderType.MAL],
            tomatoesCertified = certifications?.let { it.tomatoes != null && it.tomatoesCertified } == true,
            audienceCertified = certifications?.let { it.audience != null && it.audienceCertified } == true
        )

        if (ratings.isEmpty()) return@coroutineScope null

        MDBListRatingsResult(
            ratings = ratings,
            hasImdbRating = ratings.imdb != null
        )
    }

    private suspend fun fetchRottenTomatoesRatings(
        imdbId: String,
        mediaType: String,
        credential: MdbListRatingsCredential
    ): MDBListRatings? {
        return try {
            api.getMedia(mediaType, imdbId, credential)?.toRottenTomatoesRatings()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(tag, "Error fetching Rotten Tomatoes metadata", e)
            null
        }
    }

    private suspend fun fetchProviderRating(
        mediaType: String,
        provider: ProviderType,
        credential: MdbListRatingsCredential,
        requestBody: MDBListRatingRequestDto
    ): Pair<ProviderType, Double?> {
        return try {
            val response = api.getRating(
                mediaType = mediaType,
                ratingType = provider.apiValue,
                credential = credential,
                body = requestBody
            )

            val rating = response?.ratings?.firstOrNull()?.rating
            provider to rating
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
        if (settings.showMal) add(ProviderType.MAL)
    }

    private suspend fun resolveImdbId(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): String? {
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }
        extractImdbId(meta.imdbId)?.let { return it }

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

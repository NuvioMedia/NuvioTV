package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.AnimeIdCacheStore
import com.nuvio.tv.data.remote.api.ArmApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class AnimeIdResolver @Inject constructor(
    private val armApi: ArmApi,
    private val cacheStore: AnimeIdCacheStore
) {
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<Int?>>()
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val cacheGeneration = AtomicLong(0L)

    fun supports(rawId: String): Boolean = parseArmRequest(rawId) != null

    suspend fun resolveTmdbId(rawId: String): Int? {
        val request = parseArmRequest(rawId) ?: return null
        return resolveRequest(request)
    }

    private suspend fun resolveRequest(request: AnimeIdRequest): Int? {
        cacheStore.get(request.source, request.id)?.let { return it.tmdbId }

        val requestDeferred = inFlight.computeIfAbsent(request.cacheKey) {
            val requestGeneration = cacheGeneration.get()
            resolverScope.async(start = CoroutineStart.LAZY) {
                fetchAndCache(request, requestGeneration)
            }.also { deferred ->
                deferred.invokeOnCompletion {
                    inFlight.remove(request.cacheKey, deferred)
                }
            }
        }
        requestDeferred.start()
        return requestDeferred.await()
    }

    suspend fun prefetchTmdbIds(rawIds: Collection<String>) {
        val requests = rawIds
            .asSequence()
            .mapNotNull(::parseArmRequest)
            .distinctBy { it.cacheKey }
            .toList()
        if (requests.isEmpty()) return

        withContext(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                coroutineScope {
                    requests.map { request ->
                        async { resolveRequest(request) }
                    }.awaitAll()
                }
            }
        }
    }

    fun clearCache() {
        cacheGeneration.incrementAndGet()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        cacheStore.clear()
    }

    private suspend fun fetchAndCache(request: AnimeIdRequest, requestGeneration: Long): Int? =
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                fetchOne(request, requestGeneration)
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "ARM mapping timed out for ${request.cacheKey}")
            if (cacheGeneration.get() == requestGeneration) {
                cacheStore.putFailure(request.source, request.id)
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "ARM mapping failed for ${request.cacheKey}: ${error.message}")
            if (cacheGeneration.get() == requestGeneration) {
                cacheStore.putFailure(request.source, request.id)
            }
            null
        }

    internal fun parseArmRequest(rawId: String): AnimeIdRequest? {
        val normalized = rawId.trim().substringBefore('/').lowercase()
        val rawParts = normalized.split(':').filter { it.isNotBlank() }
        val parts = if (rawParts.firstOrNull() in setOf("movie", "series", "tv")) {
            rawParts.drop(1)
        } else {
            rawParts
        }
        if (parts.size < 2) return null

        val source = when (parts.first()) {
            "mal", "myanimelist", "my-anime-list" -> "myanimelist"
            "anilist", "ani-list" -> "anilist"
            "kitsu" -> "kitsu"
            "anidb", "ani-db" -> "anidb"
            "animeplanet", "anime-planet" -> "anime-planet"
            "animecountdown", "anime-countdown" -> "animecountdown"
            "animenewsnetwork", "anime-news-network", "ann" -> "animenewsnetwork"
            "anisearch", "ani-search" -> "anisearch"
            "livechart", "live-chart" -> "livechart"
            else -> return null
        }
        val id = parts.drop(1).firstOrNull { part ->
            if (source == "anime-planet") part.isNotBlank() else part.all(Char::isDigit)
        } ?: return null
        return AnimeIdRequest(source = source, id = id)
    }

    private suspend fun fetchOne(request: AnimeIdRequest, requestGeneration: Long): Int? =
        requestSemaphore.withPermit {
            val response = armApi.resolveId(
                source = request.source,
                id = request.id,
                include = "themoviedb"
            )
            if (!response.isSuccessful) {
                throw IllegalStateException("ARM returned HTTP ${response.code()}")
            }
            val tmdbId = response.body()?.themoviedb
            if (cacheGeneration.get() == requestGeneration) {
                if (tmdbId != null) {
                    cacheStore.putSuccess(request.source, request.id, tmdbId)
                } else {
                    cacheStore.putMiss(request.source, request.id)
                }
            }
            tmdbId
        }

    internal data class AnimeIdRequest(val source: String, val id: String) {
        val cacheKey: String = "$source:$id"
    }

    private companion object {
        const val TAG = "AnimeIdResolver"
        const val MAX_CONCURRENT_REQUESTS = 4
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}

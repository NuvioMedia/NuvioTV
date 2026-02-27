package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.image.TmdbImageUrlOptimizer
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.URLEncoder
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val tmdbImageUrlOptimizer: TmdbImageUrlOptimizer
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        private const val MAX_CACHE_ENTRIES = 256

        // Fallbacks when addon responses don't provide Cache-Control directives.
        private const val DEFAULT_MAX_AGE_SECONDS = 120L
        private const val DEFAULT_STALE_REVALIDATE_SECONDS = 300L
        private const val DEFAULT_STALE_IF_ERROR_SECONDS = 900L

        private const val MAX_MAX_AGE_SECONDS = 24 * 60 * 60L
        private const val MAX_STALE_WINDOW_SECONDS = 24 * 60 * 60L
    }

    private data class CachePolicy(
        val maxAgeMs: Long,
        val staleRevalidateMs: Long,
        val staleIfErrorMs: Long,
        val cacheable: Boolean
    ) {
        val immediateServeWindowMs: Long
            get() = maxAgeMs + staleRevalidateMs

        val errorServeWindowMs: Long
            get() = maxAgeMs + staleIfErrorMs

        val hardExpiryWindowMs: Long
            get() = max(immediateServeWindowMs, errorServeWindowMs)
    }

    private data class CachedCatalogEntry(
        val row: CatalogRow,
        val fetchedAtMs: Long,
        val policy: CachePolicy
    )

    private sealed interface CatalogFetchResult {
        data class Success(val row: CatalogRow) : CatalogFetchResult

        data class Error(
            val message: String,
            val code: Int? = null
        ) : CatalogFetchResult
    }

    private val cacheLock = Any()
    private val catalogCache =
        object : LinkedHashMap<String, CachedCatalogEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCatalogEntry>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        }
    private val inFlightMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, Deferred<CatalogFetchResult>>()

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        val cacheKey = buildCacheKey(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            type = type,
            catalogId = catalogId,
            skip = skip,
            extraArgs = extraArgs
        )
        val now = System.currentTimeMillis()
        val cachedEntry = getCachedEntry(cacheKey, now)

        // Serve cache immediately when fresh or within stale-while-revalidate.
        val serveCachedImmediately = cachedEntry != null &&
            now - cachedEntry.fetchedAtMs <= cachedEntry.policy.immediateServeWindowMs

        if (serveCachedImmediately) {
            emit(NetworkResult.Success(cachedEntry.row))
        } else {
            emit(NetworkResult.Loading)
        }

        // Fresh cache: no network call needed.
        if (cachedEntry != null && now - cachedEntry.fetchedAtMs <= cachedEntry.policy.maxAgeMs) {
            return@flow
        }

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip supportsSkip=$supportsSkip url=$url"
        )
        val result = fetchCatalogShared(
            cacheKey = cacheKey,
            url = url,
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            skip = skip,
            supportsSkip = supportsSkip
        )

        when (result) {
            is CatalogFetchResult.Success -> {
                // Only emit fresh data if it differs from what is currently displayed.
                if (cachedEntry == null || cachedEntry.row.items != result.row.items) {
                    emit(NetworkResult.Success(result.row))
                }
            }
            is CatalogFetchResult.Error -> {
                val canServeStaleOnError = cachedEntry != null &&
                    now - cachedEntry.fetchedAtMs <= cachedEntry.policy.errorServeWindowMs

                if (canServeStaleOnError) {
                    // Keep stale content on-screen when network fails.
                    if (!serveCachedImmediately) {
                        emit(NetworkResult.Success(cachedEntry.row))
                    }
                } else {
                    Log.w(
                        TAG,
                        "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                    )
                    emit(NetworkResult.Error(result.message, result.code))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchCatalogShared(
        cacheKey: String,
        url: String,
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        supportsSkip: Boolean
    ): CatalogFetchResult {
        val existing = inFlightMutex.withLock { inFlightRequests[cacheKey] }
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<CatalogFetchResult>()
        val winner = inFlightMutex.withLock {
            val current = inFlightRequests[cacheKey]
            if (current != null) {
                current
            } else {
                inFlightRequests[cacheKey] = deferred
                deferred
            }
        }

        if (winner !== deferred) {
            return winner.await()
        }

        try {
            val result = fetchCatalogFromNetwork(
                cacheKey = cacheKey,
                url = url,
                addonId = addonId,
                addonName = addonName,
                addonBaseUrl = addonBaseUrl,
                catalogId = catalogId,
                catalogName = catalogName,
                type = type,
                skip = skip,
                supportsSkip = supportsSkip
            )
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            val error = CatalogFetchResult.Error(e.message ?: "Unknown error occurred")
            deferred.complete(error)
            return error
        } finally {
            inFlightMutex.withLock {
                if (inFlightRequests[cacheKey] === deferred) {
                    inFlightRequests.remove(cacheKey)
                }
            }
        }
    }

    private suspend fun fetchCatalogFromNetwork(
        cacheKey: String,
        url: String,
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        supportsSkip: Boolean
    ): CatalogFetchResult {
        val response = try {
            api.getCatalog(url)
        } catch (e: Exception) {
            return CatalogFetchResult.Error(e.message ?: "Unknown error occurred")
        }

        if (!response.isSuccessful) {
            return CatalogFetchResult.Error(
                message = response.message(),
                code = response.code()
            )
        }

        val body = response.body()
            ?: return CatalogFetchResult.Error("Empty response body")

        val items = body.metas.map { dto ->
            val domain = dto.toDomain()
            domain.copy(
                poster = tmdbImageUrlOptimizer.optimizePosterUrl(domain.poster) ?: domain.poster,
                background = tmdbImageUrlOptimizer.optimizeBackdropUrl(domain.background) ?: domain.background
            )
        }
        Log.d(
            TAG,
            "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
        )

        val catalogRow = CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.fromString(type),
            rawType = type,
            items = items,
            isLoading = false,
            hasMore = supportsSkip && items.isNotEmpty(),
            currentPage = skip / 100,
            supportsSkip = supportsSkip
        )
        val now = System.currentTimeMillis()
        val policy = parseCachePolicy(response.headers()["Cache-Control"])
        if (policy.cacheable) {
            putCachedEntry(
                cacheKey = cacheKey,
                entry = CachedCatalogEntry(
                    row = catalogRow,
                    fetchedAtMs = now,
                    policy = policy
                )
            )
        } else {
            removeCachedEntry(cacheKey)
        }

        return CatalogFetchResult.Success(row = catalogRow)
    }

    private fun parseCachePolicy(cacheControlHeader: String?): CachePolicy {
        var noStore = false
        var maxAgeSeconds: Long? = null
        var staleRevalidateSeconds: Long? = null
        var staleIfErrorSeconds: Long? = null

        cacheControlHeader
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.forEach { directive ->
                when {
                    directive == "no-store" -> noStore = true
                    directive == "no-cache" -> maxAgeSeconds = 0L
                    directive.startsWith("max-age=") -> {
                        maxAgeSeconds = directive.substringAfter('=').toLongOrNull()
                    }
                    directive.startsWith("stale-while-revalidate=") -> {
                        staleRevalidateSeconds = directive.substringAfter('=').toLongOrNull()
                    }
                    directive.startsWith("stale-if-error=") -> {
                        staleIfErrorSeconds = directive.substringAfter('=').toLongOrNull()
                    }
                }
            }

        if (noStore) {
            return CachePolicy(
                maxAgeMs = 0L,
                staleRevalidateMs = 0L,
                staleIfErrorMs = 0L,
                cacheable = false
            )
        }

        val maxAgeMs = (maxAgeSeconds ?: DEFAULT_MAX_AGE_SECONDS)
            .coerceIn(0L, MAX_MAX_AGE_SECONDS) * 1000L
        val staleRevalidateMs = (staleRevalidateSeconds ?: DEFAULT_STALE_REVALIDATE_SECONDS)
            .coerceIn(0L, MAX_STALE_WINDOW_SECONDS) * 1000L
        val staleIfErrorMs = (staleIfErrorSeconds ?: DEFAULT_STALE_IF_ERROR_SECONDS)
            .coerceIn(0L, MAX_STALE_WINDOW_SECONDS) * 1000L

        return CachePolicy(
            maxAgeMs = maxAgeMs,
            staleRevalidateMs = staleRevalidateMs,
            staleIfErrorMs = staleIfErrorMs,
            cacheable = true
        )
    }

    private fun getCachedEntry(cacheKey: String, nowMs: Long): CachedCatalogEntry? {
        synchronized(cacheLock) {
            val entry = catalogCache[cacheKey] ?: return null
            val ageMs = nowMs - entry.fetchedAtMs
            if (ageMs > entry.policy.hardExpiryWindowMs) {
                catalogCache.remove(cacheKey)
                return null
            }
            return entry
        }
    }

    private fun putCachedEntry(cacheKey: String, entry: CachedCatalogEntry) {
        synchronized(cacheLock) {
            catalogCache[cacheKey] = entry
        }
    }

    private fun removeCachedEntry(cacheKey: String) {
        synchronized(cacheLock) {
            catalogCache.remove(cacheKey)
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')

        if (extraArgs.isEmpty()) {
            return if (skip > 0) {
                "$cleanBaseUrl/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$cleanBaseUrl/catalog/$type/$catalogId.json"
            }
        }

        val allArgs = LinkedHashMap<String, String>()
        allArgs.putAll(extraArgs)

        // For Stremio catalogs, pagination is controlled by `skip` inside extraArgs.
        if (!allArgs.containsKey("skip") && skip > 0) {
            allArgs["skip"] = skip.toString()
        }

        val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
            "${encodeArg(key)}=${encodeArg(value)}"
        }

        return "$cleanBaseUrl/catalog/$type/$catalogId/$encodedArgs.json"
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildCacheKey(
        addonBaseUrl: String,
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val normalizedArgs = extraArgs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
        return "${normalizedBaseUrl}_${addonId}_${type}_${catalogId}_${skip}_${normalizedArgs}"
    }
}

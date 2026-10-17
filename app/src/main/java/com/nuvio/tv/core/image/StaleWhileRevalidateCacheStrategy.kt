package com.nuvio.tv.core.image

import android.util.Log
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Stale-while-revalidate [CacheStrategy]:
 * - Fresh cache -> use it.
 * - Stale cache -> serve it immediately.
 * - No cache -> normal network fetch.
 *
 * Background revalidation runs only when the cached response has an ETag or
 * Last-Modified. Without a validator a GET always returns 200 and the body
 * would be discarded, then the UI would download the same image again.
 * Revalidation uses its own dispatcher so it cannot take slots from visible loads.
 * A 200 invalidates the on-screen image only when the validator actually changed.
 */
@OptIn(ExperimentalCoilApi::class)
class StaleWhileRevalidateCacheStrategy(
    private val revalidationClient: () -> OkHttpClient,
    private val imageLoaderProvider: () -> ImageLoader,
) : CacheStrategy {

    companion object {
        private const val TAG = "NuvioSWR"
        private const val REVALIDATION_COOLDOWN_MS = 10L * 60 * 1000 // 10 min
        private const val REVALIDATION_MAX_REQUESTS = 8
        private val revalidatingUrls = ConcurrentHashMap.newKeySet<String>()
        private val revalidatedAt = ConcurrentHashMap<String, Long>()
    }

    private val revalidationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val delegate = CacheControlCacheStrategy()
    private val revalidationDispatcher = Dispatcher().apply {
        maxRequests = REVALIDATION_MAX_REQUESTS
        maxRequestsPerHost = REVALIDATION_MAX_REQUESTS
    }

    @Volatile
    private var quietClient: OkHttpClient? = null

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult {
        val delegateResult = delegate.read(cacheResponse, networkRequest, options)
        if (delegateResult.response != null) return delegateResult

        if (cachedResponseCanRevalidate(
                cacheResponse.headers["etag"],
                cacheResponse.headers["last-modified"],
            )
        ) {
            val url = networkRequest.url
            val lastRevalidated = revalidatedAt[url]
            val now = System.currentTimeMillis()
            if (lastRevalidated == null || (now - lastRevalidated) > REVALIDATION_COOLDOWN_MS) {
                scheduleBackgroundRevalidation(url, cacheResponse)
            }
        }
        return CacheStrategy.ReadResult(cacheResponse)
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        return delegate.write(cacheResponse, networkRequest, networkResponse, options)
    }

    private fun quietRevalidationClient(): OkHttpClient {
        quietClient?.let { return it }
        return synchronized(this) {
            quietClient ?: revalidationClient().newBuilder()
                .dispatcher(revalidationDispatcher)
                .build()
                .also { quietClient = it }
        }
    }

    private fun scheduleBackgroundRevalidation(url: String, cachedResponse: NetworkResponse) {
        if (!revalidatingUrls.add(url)) return

        revalidationScope.launch {
            try {
                val requestBuilder = Request.Builder().url(url)
                cachedResponse.headers["etag"]?.let {
                    requestBuilder.addHeader("If-None-Match", it)
                }
                cachedResponse.headers["last-modified"]?.let {
                    requestBuilder.addHeader("If-Modified-Since", it)
                }

                val response = quietRevalidationClient().newCall(requestBuilder.build()).execute()
                try {
                    when (
                        classifyRevalidationResponse(
                            cachedEtag = cachedResponse.headers["etag"],
                            cachedLastModified = cachedResponse.headers["last-modified"],
                            responseCode = response.code,
                            responseEtag = response.header("ETag"),
                            responseLastModified = response.header("Last-Modified"),
                        )
                    ) {
                        StaleRevalidationResult.Changed -> {
                            evictFromDiskCache(url)
                            evictFromMemoryCache(url)
                            ImageInvalidationBus.notifyInvalidated(url)
                        }
                        StaleRevalidationResult.Unchanged -> {
                            if (response.code != 304 && response.code !in 200..299) {
                                Log.w(TAG, "Revalidation ${response.code}: ${url.take(80)}")
                            }
                        }
                    }
                } finally {
                    response.close()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Revalidation error: ${url.take(80)} - ${e.message}")
                }
            } finally {
                revalidatingUrls.remove(url)
                revalidatedAt[url] = System.currentTimeMillis()
            }
        }
    }

    private fun evictFromMemoryCache(url: String) {
        try {
            val memoryCache = imageLoaderProvider().memoryCache ?: return
            memoryCache.keys
                .filter { it.key.contains(url) }
                .forEach { memoryCache.remove(it) }
        } catch (_: Exception) { }
    }

    private fun evictFromDiskCache(url: String) {
        try {
            val diskCache = imageLoaderProvider().diskCache ?: return
            diskCache.openSnapshot(url)?.use { diskCache.remove(url) }
        } catch (_: Exception) { }
    }

}

internal fun cachedResponseCanRevalidate(etag: String?, lastModified: String?): Boolean =
    !etag.isNullOrBlank() || !lastModified.isNullOrBlank()

internal enum class StaleRevalidationResult {
    Unchanged,
    Changed,
}

/**
 * A 200 is not proof the image changed. Poster hosts often ignore conditional
 * headers and send the same bytes again. Reload only when ETag or Last-Modified
 * is present on both sides and differs.
 */
internal fun classifyRevalidationResponse(
    cachedEtag: String?,
    cachedLastModified: String?,
    responseCode: Int,
    responseEtag: String?,
    responseLastModified: String?,
): StaleRevalidationResult {
    if (responseCode == 304 || responseCode !in 200..299) {
        return StaleRevalidationResult.Unchanged
    }
    val cachedTag = cachedEtag?.takeIf { it.isNotBlank() }
    val newTag = responseEtag?.takeIf { it.isNotBlank() }
    if (cachedTag != null && newTag != null) {
        return if (cachedTag == newTag) {
            StaleRevalidationResult.Unchanged
        } else {
            StaleRevalidationResult.Changed
        }
    }
    val cachedModified = cachedLastModified?.takeIf { it.isNotBlank() }
    val newModified = responseLastModified?.takeIf { it.isNotBlank() }
    if (cachedTag == null && cachedModified != null && newModified != null) {
        return if (cachedModified == newModified) {
            StaleRevalidationResult.Unchanged
        } else {
            StaleRevalidationResult.Changed
        }
    }
    return StaleRevalidationResult.Unchanged
}

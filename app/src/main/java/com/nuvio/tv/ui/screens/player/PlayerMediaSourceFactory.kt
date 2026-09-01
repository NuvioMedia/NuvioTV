package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.NuvioApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class PlayerMediaSourceFactory(private val context: Context) {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()

    @Volatile private var currentVodCacheUrl: String? = null
    @Volatile private var currentVodCacheResolvedUrl: String? = null
    @Volatile private var currentVodCacheActive: Boolean = false

    val vodCacheStateLabel: String
        get() = when {
            currentVodCacheActive -> "On - ${formatCacheBytes(configuredVodCacheMaxBytes)}"
            isVodCacheDisabled -> "Unavailable"
            vodCacheEnabled -> "Off - not used for this stream"
            else -> "Off"
        }

    // Distinguishes an empty cache from one holding data the seek did not read.
    fun vodCacheBytesForKey(cacheKey: String?, url: String?): Long {
        val key = cacheKey?.takeIf { it.isNotBlank() }
            ?: url?.takeIf { it.isNotBlank() }
            ?: return -1L
        val cache = synchronized(vodCacheLock) { sharedSimpleCache } ?: return -1L
        return try {
            cache.getCachedSpans(key).sumOf { it.length }
        } catch (e: Exception) {
            -1L
        }
    }

    val vodCacheStatsLabel: String
        get() = if (configuredVodCacheMaxBytes <= 0L) {
            "-"
        } else {
            "${formatCacheBytes(vodCacheBytesReadFromCache.get())} read, " +
                "${formatCacheBytes(vodCacheBytesRemoved.get())} evicted"
        }
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)

    fun unlockStartupPrefetch() {
        parallelStartupPrefetchUnlocked.set(true)
    }

    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeKb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB
    // Gated by the parallel network toggle because its only use is the native memory argument to
    // the chunk data source, which nothing else builds.
    var nuvioPerformanceModeEnabled: Boolean = PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED

    // The engine runs off the setting whether or not the parallel gate above passed, so the log
    // reports this rather than the gated flag.
    var nativeEngineEnabled: Boolean = PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED

    // Reported only: both decide which target the helper hands the load control, so a log without
    // them cannot say why a run used the size it did.
    var bufferEngineEnabled: Boolean = false
    var bufferBudgetManaged: Boolean = PlayerSettings.DEFAULT_BUFFER_BUDGET_MANAGED
    var vodCacheEnabled: Boolean = PlayerSettings.DEFAULT_VOD_CACHE_ENABLED
    var vodCacheSizeMode: VodCacheSizeMode = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
    var vodCacheSizeMb: Int = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB

    // OkHttp client used only by the opt-in parallel-connections path.
    private val playbackHttpClient by lazy {
        PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
            .cookieJar(NuvioApplication.extensionCookieJar)
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    fun configureSubtitleParsing(
        extractorsFactory: ExtractorsFactory?,
        subtitleParserFactory: SubtitleParser.Factory?
    ) {
        customExtractorsFactory = extractorsFactory
        customSubtitleParserFactory = subtitleParserFactory
    }

    fun createMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        filename: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        mimeTypeOverride: String? = null,
        audioDelayUsProvider: (() -> Long)? = null,
        mediaMetadata: androidx.media3.common.MediaMetadata? = null,
        cacheKey: String? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val httpDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, sanitizedHeaders)

        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(
            url = url,
            filename = filename,
            responseHeaders = responseHeaders
        )
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        filename?.takeIf { it.isNotBlank() }?.let(mediaItemBuilder::setMediaId)
        // Adaptive sources index their own segments, so a custom key only applies to progressive.
        if (!isHls && !isDash) {
            cacheKey?.takeIf { it.isNotBlank() }?.let(mediaItemBuilder::setCustomCacheKey)
        }
        mediaMetadata?.let(mediaItemBuilder::setMediaMetadata)

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()

        Log.i(
            "PlayerMediaSource",
            "PLAYBACK_CONFIG: native=$nativeEngineEnabled " +
                "customBuffers=$bufferEngineEnabled budgetManaged=$bufferBudgetManaged " +
                "minBufferMs=${NuvioExoPlayerPerformanceHelper.minBufferMs} " +
                "maxBufferMs=${NuvioExoPlayerPerformanceHelper.maxBufferMs} " +
                "backBufferMs=${NuvioExoPlayerPerformanceHelper.backBufferMs} " +
                "targetMb=${NuvioExoPlayerPerformanceHelper.targetBufferSizeMb} " +
                "safeNativeMb=${NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)} " +
                "parallel=${if (useParallelConnections) parallelConnectionCount else 0} chunkKb=$parallelChunkSizeKb " +
                PlayerMemoryReporter.snapshot(context)
        )
        PlayerMemoryReporter.startSampling(context)
        val mp4SessionMode = !useParallelConnections && !isHls && !isDash &&
            resolvedMimeType == MimeTypes.VIDEO_MP4
        val useChunkSessionSource = (useParallelConnections || mp4SessionMode) && !isHls && !isDash
        parallelStartupPrefetchUnlocked.set(!useChunkSessionSource)
        val progressiveUpstreamFactory: DataSource.Factory = if (useChunkSessionSource) {
            if (mp4SessionMode) {
                Log.i(
                    "PlayerMediaSourceFactory",
                    "MP4_SESSION engaged: single-connection chunk session " +
                        "(${MP4_SESSION_CHUNK_BYTES / (1024L * 1024L)} MB chunks) " +
                        "for progressive MP4 with parallel connections off"
                )
            }
            val okHttpFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
                setDefaultRequestProperties(sanitizedHeaders)
                setUserAgent(DEFAULT_USER_AGENT)
            }
            val sessionConnections = if (mp4SessionMode) 1 else parallelConnectionCount
            val sessionChunkBytes = if (mp4SessionMode) {
                MP4_SESSION_CHUNK_BYTES
            } else {
                // Runtime enforcement of the tier chunk cap: a value
                // persisted before the cap existed (or on another device)
                // must not bypass it.
                parallelChunkSizeKb
                    .coerceAtMost(com.nuvio.tv.ui.screens.settings.MemoryBudget.tierMaxChunkMb * 1024)
                    .toLong() * 1024L
            }
            ParallelRangeDataSource.Factory(
                okHttpFactory,
                sessionConnections,
                sessionChunkBytes,
                useNativeMemory = nuvioPerformanceModeEnabled,
                shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
                onResolvedUri = { resolved -> currentVodCacheResolvedUrl = resolved?.toString() }
            )
        } else {
            httpDataSourceFactory
        }

        // 2. VOD disk cache (opt-in).
        val useVodCache = ENABLE_VOD_CACHE && vodCacheEnabled && !isHls && !isDash && shouldUseVodCache(url)
        // A playback started inside the delay window would have its own data swept out from under it.
        pendingEvictionJob?.cancel()
        pendingEvictionJob = null
        currentVodCacheUrl = url
        currentVodCacheResolvedUrl = null
        vodCacheEvictor?.resetTrimAnchor()
        // Size the cache only when used; 0 means off or not enough free space (skip, stream direct).
        val vodCacheMaxBytes = if (useVodCache && !isVodCacheDisabled) resolveVodCacheMaxBytes() else 0L
        val vodCacheActive = vodCacheMaxBytes > 0L

        val cachedProgressiveFactory: DataSource.Factory = if (vodCacheActive) {
            val cache = obtainVodCache(context, vodCacheMaxBytes)
            if (cache != null) {
                currentVodCacheActive = true
                buildVodCacheDataSourceFactory(progressiveUpstreamFactory, cache)
            } else {
                currentVodCacheActive = false
                progressiveUpstreamFactory
            }
        } else {
            currentVodCacheActive = false
            progressiveUpstreamFactory
        }
        val progressiveFactory = DataSource.Factory {
            BufferedReadDataSource(cachedProgressiveFactory.createDataSource())
        }
        // BUFFER_NETWORK reports the setting before this runs, so report what the stream actually got.
        Log.i(
            LOG_TAG,
            "VOD_CACHE: enabled=$vodCacheEnabled active=$currentVodCacheActive " +
                "requestedCap=${vodCacheMaxBytes / (1024L * 1024L)}MB stats=${vodCacheStatsSnapshot()}"
        )

        val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(progressiveFactory, extractorsFactory).apply {
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            customSubtitleParserFactory?.let { parserFactory ->
                setSubtitleParserFactory(parserFactory)
            }
        }
        val forceDefaultFactory = customExtractorsFactory != null || customSubtitleParserFactory != null

        // Sidecar subtitles are more reliable through DefaultMediaSourceFactory.
        if (subtitleConfigurations.isNotEmpty()) {
            return wrapAudioDelay(
                mediaSource = defaultFactory.createMediaSource(mediaItem),
                audioDelayUsProvider = audioDelayUsProvider
            )
        }

        val mediaSource = when {
            isHls && !forceDefaultFactory -> HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            isDash && !forceDefaultFactory -> DashMediaSource.Factory(httpDataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
        return wrapAudioDelay(mediaSource = mediaSource, audioDelayUsProvider = audioDelayUsProvider)
    }

    fun shutdown() {
        ParallelRangeDataSource.releaseRetainedSession()
    }

    // The counters are all zero at playback start, so the start line never shows what the cache
    // actually served.
    fun logVodCacheStats() {
        Log.i(
            LOG_TAG,
            "VOD_CACHE_END: enabled=$vodCacheEnabled active=$currentVodCacheActive " +
                "stats=${vodCacheStatsSnapshot()}"
        )
    }

    // Data from a finished title is only reachable by resuming it, so drop it rather than let it
    // evict the next title's window. Runs off the caller thread because the user is navigating.
    fun evictCachedSession() {
        pendingEvictionJob?.cancel()
        pendingEvictionJob = vodCacheMaintenanceScope.launch {
            // Deleting gigabytes the instant playback ends lands on the exit animation and the
            // first scroll of the screen behind it.
            delay(EVICTION_DELAY_MS)
            val cache = synchronized(vodCacheLock) { sharedSimpleCache } ?: return@launch
            clearAllCachedResources(cache)
        }
    }

    private fun buildVodCacheDataSourceFactory(upstreamFactory: DataSource.Factory, cache: SimpleCache): DataSource.Factory {
        val dataSinkFactory = DataSink.Factory {
            VodCacheWriteSink(
                CacheDataSink.Factory().setCache(cache)
                    .setFragmentSize(VOD_CACHE_FRAGMENT_BYTES)
                    .createDataSink(),
                vodCacheWriteCounters
            )
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    vodCacheBytesReadFromCache.addAndGet(cachedBytesRead)
                }

                override fun onCacheIgnored(reason: Int) {
                    Log.w(LOG_TAG, "VOD_CACHE: read bypassed the cache, reason=$reason")
                }
            })
    }

    private fun shouldUseVodCache(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    private fun resolveVodCacheMaxBytes(): Long {
        val minBytes = PlayerSettings.MIN_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val maxBytes = PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val runtimeMaxBytes = resolveRuntimeVodCacheUpperBoundBytes(maxBytes)
        // Not enough free space to host a useful cache: skip it (0 = caller streams direct).
        if (runtimeMaxBytes < minBytes) return 0L
        val manualBytes = vodCacheSizeMb
            .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB)
            .toLong() * 1024L * 1024L
        val resolvedManualBytes = manualBytes.coerceAtMost(runtimeMaxBytes)

        if (vodCacheSizeMode == VodCacheSizeMode.MANUAL) return resolvedManualBytes

        val freeSpaceBytes = reclaimableSpaceBytes()
        if (freeSpaceBytes <= 0L) return resolvedManualBytes
        return resolveAutoVodCacheBytes(freeSpaceBytes, minBytes, runtimeMaxBytes)
    }

    // What the cache already holds is reclaimable, so leaving it out would shrink the cap on every stream.
    private fun reclaimableSpaceBytes(): Long = context.cacheDir.usableSpace + currentVodCacheSpaceBytes()

    private fun resolveRuntimeVodCacheUpperBoundBytes(hardMaxBytes: Long): Long {
        val freeSpaceBytes = reclaimableSpaceBytes()
        val headroomAdjusted = if (freeSpaceBytes > VOD_CACHE_FREE_SPACE_RESERVE_BYTES) {
            freeSpaceBytes - VOD_CACHE_FREE_SPACE_RESERVE_BYTES
        } else {
            (freeSpaceBytes * 8L) / 10L
        }
        return headroomAdjusted.coerceAtLeast(1L * 1024L * 1024L).coerceAtMost(hardMaxBytes)
    }

    companion object {
        private const val MIME_VIDEO_QUICK_TIME = "video/quicktime"
        private const val MP4_SESSION_CHUNK_BYTES = 8L * 1024L * 1024L
        private const val ENABLE_VOD_CACHE = true
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        private const val VOD_CACHE_DIR_NAME = "nuvio_vod_cache"
        // Larger fragments mean fewer files to create, index and delete, which is the part of cache
        // upkeep that competes with playback reads on slow storage.
        private const val VOD_CACHE_FRAGMENT_BYTES = 8L * 1024L * 1024L

        // Bytes kept behind the playhead, roughly 120s at 66 Mbps; anything older is only reachable
        // by a seek longer than the back buffer already covers, so keeping it just evicts other titles.
        private const val VOD_CACHE_RETAIN_BEHIND_BYTES = 1024L * 1024L * 1024L
        private const val VOD_CACHE_TRIM_STEP_BYTES = 64L * 1024L * 1024L
        private const val LOG_TAG = "PlayerMediaSource"
        private const val AUTO_VOD_CACHE_FLOOR_BYTES = 2L * 1024L * 1024L * 1024L
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val MIME_PROBE_CACHE_SIZE = 64

        data class StreamProbeInfo(
            val contentLength: Long,
            val acceptsRanges: Boolean
        )

        private val probeInfoCache = object : LinkedHashMap<String, StreamProbeInfo>(MIME_PROBE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamProbeInfo>?): Boolean {
                return size > MIME_PROBE_CACHE_SIZE
            }
        }

        @JvmStatic
        fun getProbeInfo(url: String, headers: Map<String, String>): StreamProbeInfo? {
            val sanitizedHeaders = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitizedHeaders)
            return synchronized(probeInfoCache) {
                probeInfoCache[cacheKey]
            }
        }

        private fun cacheProbeInfo(url: String, headers: Map<String, String>, contentLength: Long, acceptsRanges: Boolean) {
            val sanitizedHeaders = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitizedHeaders)
            synchronized(probeInfoCache) {
                probeInfoCache[cacheKey] = StreamProbeInfo(contentLength, acceptsRanges)
            }
        }

        private fun buildMimeProbeCacheKey(url: String, headers: Map<String, String>): String {
            if (headers.isEmpty()) return url
            return buildString {
                append(url)
                headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
                    append('|')
                    append(key)
                    append('=')
                    append(value)
                }
            }
        }

        data class NormalizedPlaybackRequest(
            val url: String,
            val headers: Map<String, String>
        )

        @Volatile private var sharedSimpleCache: SimpleCache? = null
        @Volatile private var configuredVodCacheMaxBytes: Long = -1L
        @Volatile private var isVodCacheDisabled: Boolean = false
        private val vodCacheLock = Any()
        private val vodCacheBytesReadFromCache = AtomicLong(0L)
        private val vodCacheSpansRemoved = AtomicLong(0L)
        private val vodCacheBytesRemoved = AtomicLong(0L)
        private val vodCacheBytesTrimmed = AtomicLong(0L)
        private val vodCacheWriteCounters = VodCacheWriteSink.Counters()
        @Volatile private var vodCacheEvictor: CountingCacheEvictor? = null

        // A header read at 0, the jump to a resume point and an mp4 index at the tail all look alike
        // as they arrive, so the window follows the playhead the controller reports instead.
        @Volatile var vodCachePlayheadBytesProvider: (() -> Long)? = null

        @Volatile
        private var pendingEvictionJob: Job? = null

        private const val EVICTION_DELAY_MS = 5_000L

        // Process scoped so cleanup survives the player screen being torn down.
        private val vodCacheMaintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private suspend fun removeResourceQuietly(cache: SimpleCache, key: String) {
            val spans = try {
                cache.getCachedSpans(key)
            } catch (e: Exception) {
                return
            }
            for (span in spans) {
                try {
                    cache.removeSpan(span)
                } catch (e: Exception) {
                    // A locked span is dropped by the evictor later.
                }
                // A long cleanup should not monopolize an IO worker other loads may need.
                yield()
            }
        }

        // A crash leaves the previous session's titles behind, so start each app run clean.
        private fun clearStaleVodCache(cache: SimpleCache) {
            vodCacheMaintenanceScope.launch { clearAllCachedResources(cache) }
        }

        // Removing only the key the player reports orphans anything written under a resolved url,
        // which then survives until the cap forces an eviction mid playback.
        private suspend fun clearAllCachedResources(cache: SimpleCache) {
            val keys = try {
                cache.keys.toList()
            } catch (e: Exception) {
                return
            }
            for (key in keys) {
                removeResourceQuietly(cache, key)
            }
        }

        fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
            val raw: Map<*, *> = headers ?: return emptyMap()
            if (raw.isEmpty()) return emptyMap()

            val sanitized = LinkedHashMap<String, String>(raw.size)
            raw.forEach { (rawKey, rawValue) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                val value = (rawValue as? String)?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                if (key.equals("Range", ignoreCase = true)) return@forEach
                sanitized[key] = value
            }
            return sanitized
        }

        fun normalizePlaybackRequest(
            url: String,
            headers: Map<String, String>?
        ): NormalizedPlaybackRequest {
            val sanitizedHeaders = sanitizeHeaders(headers)
            val (cleanUrl, mergedHeaders) = extractUserInfoAuth(url, sanitizedHeaders)
            return NormalizedPlaybackRequest(
                url = cleanUrl,
                headers = sanitizeHeaders(mergedHeaders)
            )
        }

        fun parseHeaders(headers: String?): Map<String, String> {
            if (headers.isNullOrEmpty()) return emptyMap()

            return try {
                // Try JSON format first (new)
                if (headers.trimStart().startsWith("{")) {
                    val json = org.json.JSONObject(headers)
                    val result = LinkedHashMap<String, String>()
                    json.keys().forEach { key ->
                        val value = json.optString(key, "")
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            result[key] = value
                        }
                    }
                    return sanitizeHeaders(result)
                }

                // Legacy key=value&key=value format (backward compat)
                val parsed = headers.split("&").associate { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }.filterKeys { it.isNotEmpty() }
                sanitizeHeaders(parsed)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun obtainVodCache(context: Context, maxBytes: Long): SimpleCache? {
            synchronized(vodCacheLock) {
                if (isVodCacheDisabled) return null
                val existing = sharedSimpleCache
                // A running player may still be reading this cache, and the evictor cap cannot change
                // on a live instance, so a resize waits for the next app start.
                if (existing != null) {
                    if (configuredVodCacheMaxBytes != maxBytes) {
                        Log.i(LOG_TAG, "VOD_CACHE: keeping cap ${configuredVodCacheMaxBytes / (1024L * 1024L)}MB, ${maxBytes / (1024L * 1024L)}MB applies next launch")
                    }
                    return existing
                }

                val dir = File(context.applicationContext.cacheDir, VOD_CACHE_DIR_NAME)
                var created = createVodCache(context, dir, maxBytes)
                if (created == null) {
                    // A crash mid-write can leave an index the cache cannot read, so drop it and rebuild once.
                    Log.w(LOG_TAG, "VOD_CACHE: index unusable, rebuilding at ${dir.absolutePath}")
                    dir.deleteRecursively()
                    created = createVodCache(context, dir, maxBytes)
                }

                if (created == null) {
                    isVodCacheDisabled = true
                    Log.w(LOG_TAG, "VOD_CACHE: unavailable for this session, streaming direct")
                } else {
                    configuredVodCacheMaxBytes = maxBytes
                    vodCacheBytesReadFromCache.set(0L)
                    vodCacheSpansRemoved.set(0L)
                    vodCacheBytesTrimmed.set(0L)
                    vodCacheBytesRemoved.set(0L)
                    vodCacheWriteCounters.reset()
                    clearStaleVodCache(created)
                }
                sharedSimpleCache = created
                return created
            }
        }

        // A fifth of free space is too small on low-storage devices to hold the window a large remux keeps seeking back into.
        internal fun resolveAutoVodCacheBytes(freeSpaceBytes: Long, minBytes: Long, runtimeMaxBytes: Long): Long =
            maxOf(AUTO_VOD_CACHE_FLOOR_BYTES, freeSpaceBytes / 5L).coerceIn(minBytes, runtimeMaxBytes)

        private fun createVodCache(context: Context, dir: File, maxBytes: Long): SimpleCache? {
            var cache: SimpleCache? = null
            return try {
                dir.mkdirs()
                val provider = StandaloneDatabaseProvider(context.applicationContext)
                val evictor = CountingCacheEvictor(maxBytes)
                val built = SimpleCache(dir, evictor, provider)
                vodCacheEvictor = evictor
                cache = built
                // SimpleCache stores an index failure instead of throwing, so touch it here to surface one now.
                built.cacheSpace
                built
            } catch (_: Throwable) {
                cache?.let(::releaseVodCacheQuietly)
                null
            }
        }

        private fun releaseVodCacheQuietly(cache: SimpleCache) {
            try {
                cache.release()
            } catch (_: Throwable) {
            }
        }

        private fun formatCacheBytes(bytes: Long): String {
            val safe = bytes.coerceAtLeast(0L)
            val gb = 1024L * 1024L * 1024L
            return if (safe >= gb) {
                String.format(Locale.US, "%.1f GB", safe.toDouble() / gb)
            } else {
                "${safe / (1024L * 1024L)} MB"
            }
        }

        private fun currentVodCacheSpaceBytes(): Long {
            val cache = sharedSimpleCache ?: return 0L
            return try {
                cache.cacheSpace
            } catch (_: Throwable) {
                0L
            }
        }

        private fun vodCacheStatsSnapshot(): String {
            val cache = sharedSimpleCache ?: return "none"
            val space = try {
                cache.cacheSpace
            } catch (_: Throwable) {
                -1L
            }
            return "space=${space / (1024L * 1024L)}MB " +
                "cap=${configuredVodCacheMaxBytes / (1024L * 1024L)}MB " +
                "hitKb=${vodCacheBytesReadFromCache.get() / 1024L} " +
                "removedSpans=${vodCacheSpansRemoved.get()} " +
                "removedBytes=${vodCacheBytesRemoved.get() / (1024L * 1024L)}MB " +
                "trimmedBytes=${vodCacheBytesTrimmed.get() / (1024L * 1024L)}MB " +
                "writtenBytes=${vodCacheWriteCounters.bytesWritten.get() / (1024L * 1024L)}MB " +
                "writeMs=${vodCacheWriteCounters.writeTimeMs.get()} " +
                "enqueueMs=${vodCacheWriteCounters.enqueueTimeMs.get()} " +
                "writeBlockedMs=${vodCacheWriteCounters.blockedMs.get()} " +
                "bufferMs=${vodCacheWriteCounters.bufferTimeNs.get() / 1_000_000L} " +
                "copyMs=${vodCacheWriteCounters.copyTimeNs.get() / 1_000_000L} " +
                "allocs=${vodCacheWriteCounters.allocations.get()} " +
                "closeWaitMs=${vodCacheWriteCounters.closeWaitMs.get()} " +
                "spans=${vodCacheWriteCounters.spans.get()} " +
                "writeErrors=${vodCacheWriteCounters.errors.get()}"
        }

        private class CountingCacheEvictor(maxBytes: Long) : CacheEvictor {
            private val delegate = LeastRecentlyUsedCacheEvictor(maxBytes)

            override fun requiresCacheSpanTouches(): Boolean = delegate.requiresCacheSpanTouches()

            override fun onCacheInitialized() = delegate.onCacheInitialized()

            @Volatile
            private var lastTrimPosition = 0L

            // The evictor lives as long as the shared cache, so an anchor left by one title would sit
            // ahead of the next title's playhead and hold its trimming still.
            fun resetTrimAnchor() {
                lastTrimPosition = 0L
            }

            override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
                trimBehindPlayhead(cache, key)
                delegate.onStartFile(cache, key, position, length)
            }

            // Spans are ordered by position, so stop at the first one inside the window. Trimming
            // every fragment copies the span set 8x more often than the window can move, so batch it.
            private fun trimBehindPlayhead(cache: Cache, key: String) {
                val playhead = runCatching { vodCachePlayheadBytesProvider?.invoke() }
                    .getOrNull() ?: return
                if (playhead <= 0L) return
                val cutoff = playhead - VOD_CACHE_RETAIN_BEHIND_BYTES
                if (cutoff <= 0L) return
                // A back seek leaves the playhead behind the last trim, which reads as no progress
                // and holds the window still rather than dropping what the seek is about to play.
                if (playhead - lastTrimPosition < VOD_CACHE_TRIM_STEP_BYTES) return
                lastTrimPosition = playhead
                val spans = try {
                    cache.getCachedSpans(key)
                } catch (e: Exception) {
                    return
                }
                for (span in spans) {
                    if (span.position + span.length > cutoff) break
                    try {
                        val trimmed = span.length
                        cache.removeSpan(span)
                        vodCacheBytesTrimmed.addAndGet(trimmed)
                    } catch (e: Exception) {
                        // A span still locked by a reader stays until the next write.
                        break
                    }
                }
            }

            override fun onSpanAdded(cache: Cache, span: CacheSpan) = delegate.onSpanAdded(cache, span)

            // Counts stale-span cleanup as well as eviction, so treat a rising count as cache churn rather than eviction alone.
            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                vodCacheSpansRemoved.incrementAndGet()
                vodCacheBytesRemoved.addAndGet(span.length)
                delegate.onSpanRemoved(cache, span)
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
                delegate.onSpanTouched(cache, oldSpan, newSpan)
        }

        private fun inferAdaptiveMimeTypeFromPath(path: String?): String? {
            val normalized = path?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val pathWithoutFragment = normalized.substringBefore('#')
            val pathPart = pathWithoutFragment.substringBefore('?')
            val fileName = pathPart.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            return when (extension) {
                "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                "ism", "isml" -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }

        internal fun inferMimeType(
            url: String,
            filename: String?,
            responseHeaders: Map<String, String>? = null
        ): String? {
            val adaptiveMime = inferAdaptiveMimeTypeFromPath(filename)
                ?: inferAdaptiveMimeTypeFromPath(url)
            if (adaptiveMime != null) {
                return adaptiveMime
            }

            return inferMimeTypeFromResponseHeaders(responseHeaders)
                ?: inferMimeTypeFromPath(filename)
                ?: inferMimeTypeFromPath(url)
        }

        internal fun normalizeMimeType(contentType: String?): String? {
            val normalized = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                ?: return null

            return when (normalized) {
                "application/vnd.apple.mpegurl",
                "application/mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl",
                "application/m3u8" -> MimeTypes.APPLICATION_M3U8

                "application/dash+xml",
                "video/vnd.mpeg.dash.mpd" -> MimeTypes.APPLICATION_MPD

                "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS

                "video/mp4",
                "application/mp4",
                "video/x-m4v" -> MimeTypes.VIDEO_MP4

                "video/webm",
                "audio/webm" -> MimeTypes.VIDEO_WEBM

                "video/x-matroska",
                "audio/x-matroska",
                "video/mkv",
                "audio/mkv" -> MimeTypes.VIDEO_MATROSKA
                else -> null
            }
        }

        internal fun sniffManifestMimeType(snippet: String?): String? {
            val normalized = snippet
                ?.trimStart()
                ?.lowercase(Locale.US)
                ?: return null

            return when {
                normalized.startsWith("#extm3u") -> MimeTypes.APPLICATION_M3U8
                normalized.startsWith("<?xml") && normalized.contains("<mpd") -> MimeTypes.APPLICATION_MPD
                normalized.startsWith("<mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        suspend fun probeMimeType(
            url: String,
            headers: Map<String, String>,
            filename: String? = null,
            responseHeaders: Map<String, String>? = null
        ): String? {
            return inferMimeType(
                url = url,
                filename = filename,
                responseHeaders = responseHeaders
            )
        }

        suspend fun probeNetworkMimeType(
            url: String,
            headers: Map<String, String> = emptyMap()
        ): String? = withContext(Dispatchers.IO) {
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return@withContext null
            }
            val sanitizedHeaders = sanitizeHeaders(headers)
            val methods = listOf("HEAD", "GET")
            for (method in methods) {
                runCatching {
                    val requestBuilder = Request.Builder().url(url)
                    if (method == "GET") {
                        requestBuilder.header("Range", "bytes=0-2048")
                    }
                    sanitizedHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", ignoreCase = true)) {
                            requestBuilder.header(key, value)
                        }
                    }
                    if (sanitizedHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                        requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                    }

                    PlayerPlaybackNetworking.playbackHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful && response.code !in 200..308) {
                            return@use null
                        }

                        val finalUrl = response.request.url.toString()
                        inferAdaptiveMimeTypeFromPath(finalUrl)?.let { return@withContext it }

                        val contentType = response.header("Content-Type")
                        normalizeMimeType(contentType)?.let { return@withContext it }

                        val responseHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }
                        inferMimeTypeFromResponseHeaders(responseHeadersMap)?.let { return@withContext it }

                        if (method == "GET") {
                            val snippet = response.body?.byteStream()?.use { stream ->
                                val bytes = ByteArray(512)
                                val read = stream.read(bytes)
                                if (read > 0) String(bytes, 0, read, Charsets.UTF_8) else null
                            }
                            sniffManifestMimeType(snippet)?.let { return@withContext it }
                        }

                        inferMimeTypeFromPath(finalUrl)?.let { return@withContext it }
                    }
                }.getOrNull()?.let { return@withContext it }
            }
            null
        }

        private fun inferMimeTypeFromResponseHeaders(headers: Map<String, String>?): String? {
            if (headers.isNullOrEmpty()) return null

            val contentType = headers.entries
                .firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
                ?.value
            normalizeMimeType(contentType)?.let { return it }

            val contentDisposition = headers.entries
                .firstOrNull { (key, _) -> key.equals("Content-Disposition", ignoreCase = true) }
                ?.value
                ?: return null

            val filename = contentDisposition
                .substringAfter("filename*=", missingDelimiterValue = "")
                .substringAfterLast("''", missingDelimiterValue = "")
                .ifBlank {
                    contentDisposition.substringAfter("filename=", missingDelimiterValue = "")
                }
                .trim()
                .trim('"', '\'')
                .takeIf { it.isNotBlank() }

            return inferMimeTypeFromPath(filename)
        }

        private fun inferMimeTypeFromPath(path: String?): String? {
            val normalized = path?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val pathWithoutFragment = normalized.substringBefore('#')
            val pathPart = pathWithoutFragment.substringBefore('?')
            val queryPart = pathWithoutFragment.substringAfter('?', missingDelimiterValue = "")
            val fileName = pathPart.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

            return when {
                extension == "m3u8" || extension == "m3u" -> MimeTypes.APPLICATION_M3U8
                extension == "mpd" -> MimeTypes.APPLICATION_MPD
                extension == "ism" || extension == "isml" -> MimeTypes.APPLICATION_SS
                extension == "mkv" -> MimeTypes.VIDEO_MATROSKA
                extension == "webm" -> MimeTypes.VIDEO_WEBM
                extension == "mp4" || extension == "m4v" -> MimeTypes.VIDEO_MP4
                extension == "ts" || extension == "mts" || extension == "m2ts" -> MimeTypes.VIDEO_MP2T
                extension == "mov" -> MIME_VIDEO_QUICK_TIME
                extension == "avi" -> MimeTypes.VIDEO_AVI
                extension == "mpeg" || extension == "mpg" -> MimeTypes.VIDEO_MPEG
                else -> inferMimeTypeFromQuery(queryPart)
                    ?: inferMimeTypeFromDelimitedToken(pathPart)
                    ?: inferMimeTypeFromDelimitedToken(queryPart)
            }
        }

        private fun inferMimeTypeFromQuery(query: String): String? {
            if (query.isBlank()) return null

            query.split('&').forEach { parameter ->
                val key = parameter.substringBefore('=', missingDelimiterValue = "").trim()
                val value = parameter.substringAfter('=', missingDelimiterValue = "").trim()
                if (key.isBlank() || value.isBlank()) return@forEach

                when (key) {
                    "format",
                    "mime",
                    "mime_type",
                    "contenttype",
                    "content_type",
                    "type",
                    "ext",
                    "extension",
                    "output",
                    "protocol",
                    "mode",
                    "stream",
                    "service" -> {
                        when (value.substringAfterLast('/').substringAfterLast('.')) {
                            "m3u8", "m3u" -> return MimeTypes.APPLICATION_M3U8
                            "mpd" -> return MimeTypes.APPLICATION_MPD
                            "ism", "isml" -> return MimeTypes.APPLICATION_SS
                            "mkv" -> return MimeTypes.VIDEO_MATROSKA
                            "webm" -> return MimeTypes.VIDEO_WEBM
                            "mp4", "m4v" -> return MimeTypes.VIDEO_MP4
                            "ts", "mts", "m2ts" -> return MimeTypes.VIDEO_MP2T
                            "mov" -> return MIME_VIDEO_QUICK_TIME
                            "avi" -> return MimeTypes.VIDEO_AVI
                            "mpeg", "mpg" -> return MimeTypes.VIDEO_MPEG
                        }
                    }
                }

                when (value) {
                    "application/vnd.apple.mpegurl",
                    "application/mpegurl",
                    "application/x-mpegurl",
                    "audio/mpegurl",
                    "audio/x-mpegurl",
                    "application/m3u8",
                    "m3u8",
                    "m3u",
                    "hls" -> return MimeTypes.APPLICATION_M3U8
                    "application/dash+xml",
                    "video/vnd.mpeg.dash.mpd",
                    "dash" -> return MimeTypes.APPLICATION_MPD
                    "application/vnd.ms-sstr+xml",
                    "smoothstreaming",
                    "ss" -> return MimeTypes.APPLICATION_SS
                }
            }

            return null
        }

        private fun inferMimeTypeFromDelimitedToken(value: String): String? {
            if (value.isBlank()) return null

            return when {
                DELIMITED_M3U8_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
                PLAYLIST_HLS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
                DELIMITED_MPD_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_MPD
                DELIMITED_SS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }


        private fun wrapAudioDelay(
            mediaSource: MediaSource,
            audioDelayUsProvider: (() -> Long)?
        ): MediaSource {
            return if (audioDelayUsProvider == null) {
                mediaSource
            } else {
                AudioDelayMediaSource(
                    mediaSource = mediaSource,
                    audioDelayUsProvider = audioDelayUsProvider
                )
            }
        }

        private val DELIMITED_M3U8_PATTERN = Regex("(^|[=/_.?&-])(m3u8|m3u)($|[=/_.?&-])")
        private val PLAYLIST_HLS_PATTERN = Regex("/(playlist|hls|manifest|master|vs)/(?!stream$|list$|info$|details$)[a-zA-Z0-9_/-]+$")
        private val DELIMITED_MPD_PATTERN = Regex("(^|[=/_.?&-])mpd($|[=/_.?&-])")
        private val DELIMITED_SS_PATTERN = Regex("(^|[=/_.?&-])(ism|isml)($|[=/_.?&-])")

        /**
         * Extracts `user:pass` from a URL's userinfo component and converts it
         * to a Basic Auth header. Returns the cleaned URL (without userinfo) and
         * merged headers. If the URL has no userinfo, returns the original URL and headers unchanged.
         *
         * The returned URL has no userinfo, and the returned headers carry Basic auth.
         */
        fun extractUserInfoAuth(
            url: String,
            headers: Map<String, String>
        ): Pair<String, Map<String, String>> {
            if (url.isBlank()) return url to headers
            val uri = try { java.net.URI(url) } catch (_: Exception) { return url to headers }
            val rawUserInfo = uri.rawAuthority
                ?.substringBeforeLast('@', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
            val userInfo = uri.userInfo ?: rawUserInfo?.let(::decodeRawUserInfo) ?: return url to headers
            if (userInfo.isBlank()) return url to headers
            val cleanUrl = stripRawUserInfo(uri) ?: return url to headers
            val mergedHeaders = LinkedHashMap(headers)
            if (headers.none { it.key.equals("Authorization", ignoreCase = true) }) {
                val encoded = Base64.getEncoder().encodeToString(userInfo.toByteArray(Charsets.UTF_8))
                mergedHeaders["Authorization"] = "Basic $encoded"
            }
            return cleanUrl to mergedHeaders
        }

        private fun stripRawUserInfo(uri: java.net.URI): String? {
            val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
            val rawAuthority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
            val cleanAuthority = rawAuthority.substringAfterLast('@', missingDelimiterValue = rawAuthority)
                .takeIf { it != rawAuthority && it.isNotBlank() }
                ?: return null
            return buildString {
                append(scheme)
                append("://")
                append(cleanAuthority)
                append(uri.rawPath.orEmpty())
                uri.rawQuery?.let {
                    append('?')
                    append(it)
                }
                uri.rawFragment?.let {
                    append('#')
                    append(it)
                }
            }
        }

        private fun decodeRawUserInfo(value: String): String? =
            runCatching {
                URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrNull()
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpException = loadErrorInfo.exception.findCause<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        if (httpException != null) {
            val code = httpException.responseCode
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 410) {
                return androidx.media3.common.C.TIME_UNSET
            }
        }
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        return if (timeout) {
            when (loadErrorInfo.errorCount) {
                1 -> 750L
                2 -> 1500L
                else -> 3000L
            }
        } else super.getRetryDelayMsFor(loadErrorInfo)
    }
}

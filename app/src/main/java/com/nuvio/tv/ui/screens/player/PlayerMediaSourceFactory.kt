package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
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
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)

    fun unlockStartupPrefetch() {
        parallelStartupPrefetchUnlocked.set(true)
    }

    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeKb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB
    var nuvioPerformanceModeEnabled: Boolean = PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED
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
        mediaMetadata: androidx.media3.common.MediaMetadata? = null
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
        mediaMetadata?.let(mediaItemBuilder::setMediaMetadata)

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()

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
            ParallelRangeDataSource.Factory(
                okHttpFactory,
                if (mp4SessionMode) 1 else parallelConnectionCount,
                if (mp4SessionMode) {
                    MP4_SESSION_CHUNK_BYTES
                } else {
                    // Runtime enforcement of the tier chunk cap: a value
                    // persisted before the cap existed (or on another device)
                    // must not bypass it.
                    parallelChunkSizeKb
                        .coerceAtMost(com.nuvio.tv.ui.screens.settings.MemoryBudget.tierMaxChunkMb * 1024)
                        .toLong() * 1024L
                },
                useNativeMemory = nuvioPerformanceModeEnabled,
                shouldAllowBackgroundPrefetch = { parallelStartupPrefetchUnlocked.get() },
                onResolvedUri = { resolved -> currentVodCacheResolvedUrl = resolved?.toString() }
            )
        } else {
            httpDataSourceFactory
        }

        // 2. VOD disk cache (opt-in).
        val useVodCache = ENABLE_VOD_CACHE && vodCacheEnabled && !isHls && !isDash && shouldUseVodCache(url)
        val previousVodCacheActive = currentVodCacheActive
        currentVodCacheUrl = url
        currentVodCacheResolvedUrl = null
        // Size the cache only when used; 0 means off or not enough free space (skip, stream direct).
        val vodCacheMaxBytes = if (useVodCache && !isVodCacheDisabled) resolveVodCacheMaxBytes() else 0L
        val vodCacheActive = vodCacheMaxBytes > 0L

        if (vodCacheActive) {
            maybeApplyLiveVodCacheCapIncrease(context, vodCacheMaxBytes, !previousVodCacheActive)
        }

        val progressiveFactory: DataSource.Factory = if (vodCacheActive) {
            val cache = getReadySimpleCache(vodCacheMaxBytes) ?: getAnySimpleCache()
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

        val baseExtractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        val extractorsFactory = baseExtractorsFactory.withNuvioMp4Extractor()
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

    private fun buildVodCacheDataSourceFactory(upstreamFactory: DataSource.Factory, cache: SimpleCache): DataSource.Factory {
        val dataSinkFactory = CacheDataSink.Factory().setCache(cache).setFragmentSize(2L * 1024L * 1024L)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
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

        val freeSpaceBytes = context.cacheDir.usableSpace
        if (freeSpaceBytes <= 0L) return resolvedManualBytes
        val autoBytes = freeSpaceBytes / 5L // 20% for a healthy buffer
        return autoBytes.coerceIn(minBytes, runtimeMaxBytes)
    }

    private fun resolveRuntimeVodCacheUpperBoundBytes(hardMaxBytes: Long): Long {
        val freeSpaceBytes = context.cacheDir.usableSpace
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

        private fun getReadySimpleCache(expectedMaxBytes: Long): SimpleCache? {
            val cache = sharedSimpleCache ?: return null
            return if (configuredVodCacheMaxBytes == expectedMaxBytes) cache else null
        }

        private fun getAnySimpleCache(): SimpleCache? = sharedSimpleCache

        private fun maybeApplyLiveVodCacheCapIncrease(
            context: Context,
            requestedMaxBytes: Long,
            allowLiveReconfigure: Boolean
        ) {
            // Live cache reconfiguration is not yet implemented; the shared cache is
            // created lazily elsewhere. Kept as the integration point for the VOD cache.
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
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val responseCode = loadErrorInfo.exception
            .findCause<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            ?.responseCode
        if (
            shouldPreferAlternativeHlsTrack(
                responseCode = responseCode,
                dataType = loadErrorInfo.mediaLoadData.dataType,
                alternativeTrackAvailable = fallbackOptions.isFallbackAvailable(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK
                )
            )
        ) {
            // A media-segment 404 belongs to the selected rendition. Exclude that
            // rendition first so HLS can continue with another compatible track.
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS
            )
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

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

internal fun shouldPreferAlternativeHlsTrack(
    responseCode: Int?,
    dataType: Int,
    alternativeTrackAvailable: Boolean
): Boolean =
    responseCode == 404 &&
        dataType == C.DATA_TYPE_MEDIA &&
        alternativeTrackAvailable

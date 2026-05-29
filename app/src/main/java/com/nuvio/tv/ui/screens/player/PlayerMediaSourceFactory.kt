package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
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

    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeMb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
    var vodCacheEnabled: Boolean = PlayerSettings.DEFAULT_VOD_CACHE_ENABLED
    var vodCacheSizeMode: VodCacheSizeMode = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
    var vodCacheSizeMb: Int = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB

    // OkHttp client used only by the opt-in parallel-connections path.
    private val playbackHttpClient by lazy {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
        OkHttpClient.Builder()
            .cookieJar(NuvioApplication.extensionCookieJar)
            .dns(IPv4FirstDns())
            .dispatcher(dispatcher)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
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

        // 1. Parallel connections (opt-in). ParallelRangeDataSource needs a concrete
        // OkHttpDataSource.Factory, so build one only on this path.
        parallelStartupPrefetchUnlocked.set(!(useParallelConnections && !isHls && !isDash))
        val progressiveUpstreamFactory: DataSource.Factory = if (useParallelConnections && !isHls && !isDash) {
            val okHttpFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
                setDefaultRequestProperties(sanitizedHeaders)
                setUserAgent(DEFAULT_USER_AGENT)
            }
            ParallelRangeDataSource.Factory(
                okHttpFactory,
                parallelConnectionCount,
                parallelChunkSizeMb.toLong() * 1024L * 1024L,
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

    fun shutdown() = Unit

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
        private const val PROBE_TIMEOUT_MS = 4000
        private const val PROBE_BYTES = 1024
        private const val MIME_PROBE_CACHE_SIZE = 64
        private const val MIME_VIDEO_QUICK_TIME = "video/quicktime"
        private const val ENABLE_VOD_CACHE = true
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val mimeProbeCache = object : LinkedHashMap<String, String>(MIME_PROBE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MIME_PROBE_CACHE_SIZE
            }
        }

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

        internal fun inferMimeType(
            url: String,
            filename: String?,
            responseHeaders: Map<String, String>? = null
        ): String? {
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
            inferMimeType(
                url = url,
                filename = filename,
                responseHeaders = responseHeaders
            )?.let { return it }

            val sanitizedHeaders = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitizedHeaders)

            synchronized(mimeProbeCache) {
                mimeProbeCache[cacheKey]
            }?.let { return it }

            val probedMimeType = withContext(Dispatchers.IO) {
                probeMimeTypeWithRangeGet(url, sanitizedHeaders)
                    ?: probeMimeTypeWithHead(url, sanitizedHeaders)
            }

            if (probedMimeType != null) {
                synchronized(mimeProbeCache) {
                    mimeProbeCache[cacheKey] = probedMimeType
                }
            }

            return probedMimeType
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
                extension == "m3u8" -> MimeTypes.APPLICATION_M3U8
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
                    "output" -> {
                        when (value.substringAfterLast('/').substringAfterLast('.')) {
                            "m3u8" -> return MimeTypes.APPLICATION_M3U8
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
                DELIMITED_MPD_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_MPD
                DELIMITED_SS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }

        private fun probeMimeTypeWithHead(url: String, headers: Map<String, String>): String? {
            val connection = openConnection(url = url, headers = headers, method = "HEAD")
            return try {
                connection.responseCode
                val responseHeaders = readResponseHeaders(connection)
                normalizeMimeType(connection.contentType)
                    ?: inferMimeType(
                        url = connection.url?.toString().orEmpty(),
                        filename = null,
                        responseHeaders = responseHeaders
                    )
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

        private fun probeMimeTypeWithRangeGet(url: String, headers: Map<String, String>): String? {
            val connection = openConnection(
                url = url,
                headers = headers,
                method = "GET",
                range = "bytes=0-${PROBE_BYTES - 1}"
            )
            return try {
                connection.responseCode
                val responseHeaders = readResponseHeaders(connection)
                normalizeMimeType(connection.contentType)
                    ?: inferMimeType(
                        url = connection.url?.toString().orEmpty(),
                        filename = null,
                        responseHeaders = responseHeaders
                    )
                    ?: sniffManifestMimeType(readProbeSnippet(connection.inputStream))
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

        private fun openConnection(
            url: String,
            headers: Map<String, String>,
            method: String,
            range: String? = null
        ): HttpURLConnection {
            return PlayerPlaybackNetworking.openConnection(
                url = url,
                headers = headers,
                method = method,
                connectTimeoutMs = PROBE_TIMEOUT_MS,
                readTimeoutMs = PROBE_TIMEOUT_MS,
                range = range
            )
        }

        private fun readProbeSnippet(inputStream: InputStream?): String? {
            if (inputStream == null) return null
            val buffer = ByteArray(PROBE_BYTES)
            val read = inputStream.read(buffer)
            if (read <= 0) return null
            return String(buffer, 0, read, Charsets.UTF_8)
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

        private fun readResponseHeaders(connection: HttpURLConnection): Map<String, String> {
            return buildMap {
                connection.headerFields.forEach { (key, values) ->
                    if (key.isNullOrBlank()) return@forEach
                    val value = values
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?: return@forEach
                    put(key, value)
                }
            }
        }

        private val DELIMITED_M3U8_PATTERN = Regex("(^|[=/_.?&-])m3u8($|[=/_.?&-])")
        private val DELIMITED_MPD_PATTERN = Regex("(^|[=/_.?&-])mpd($|[=/_.?&-])")
        private val DELIMITED_SS_PATTERN = Regex("(^|[=/_.?&-])(ism|isml)($|[=/_.?&-])")

        /**
         * Extracts `user:password` from a URL's userinfo component and converts it
         * to a Basic Auth header. Returns the cleaned URL (without userinfo) and
         * merged headers. If the URL has no userinfo, returns the original URL and headers unchanged.
         *
         * Example: `https://user:pass@host/path` → URL `https://host/path` + header `Authorization: Basic dXNlcjpwYXNz`
         */
        fun extractUserInfoAuth(
            url: String,
            headers: Map<String, String>
        ): Pair<String, Map<String, String>> {
            if (url.isBlank()) return url to headers
            val uri = try { java.net.URI(url) } catch (_: Exception) { return url to headers }
            val userInfo = uri.userInfo ?: return url to headers
            if (userInfo.isBlank()) return url to headers
            // Already has an Authorization header — don't override
            if (headers.any { it.key.equals("Authorization", ignoreCase = true) }) {
                return url to headers
            }
            val encoded = android.util.Base64.encodeToString(
                userInfo.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val cleanUri = java.net.URI(
                uri.scheme,
                null, // no userinfo
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            )
            val mergedHeaders = LinkedHashMap(headers)
            mergedHeaders["Authorization"] = "Basic $encoded"
            return cleanUri.toString() to mergedHeaders
        }
    }
}

private class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
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

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

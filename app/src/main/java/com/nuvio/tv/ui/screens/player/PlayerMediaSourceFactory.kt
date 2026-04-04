package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class PlayerMediaSourceFactory {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val playbackHttpClient by lazy {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
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
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        filename: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        mimeTypeOverride: String? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
            setDefaultRequestProperties(sanitizedHeaders)
            setUserAgent(DEFAULT_USER_AGENT)
        }

        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(
            url = url,
            filename = filename,
            responseHeaders = responseHeaders
        )
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()
        val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory).apply {
            customSubtitleParserFactory?.let { parserFactory ->
                setSubtitleParserFactory(parserFactory)
            }
        }
        val forceDefaultFactory = customExtractorsFactory != null || customSubtitleParserFactory != null

        // Sidecar subtitles are more reliable through DefaultMediaSourceFactory.
        if (subtitleConfigurations.isNotEmpty()) {
            return defaultFactory.createMediaSource(mediaItem)
        }

        return when {
            isHls && !forceDefaultFactory -> HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            isDash && !forceDefaultFactory -> DashMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
    }

    fun shutdown() = Unit

    companion object {
        private const val PROBE_TIMEOUT_MS = 4000
        private const val PROBE_BYTES = 1024
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

            return withContext(Dispatchers.IO) {
                probeMimeTypeWithHead(url, sanitizedHeaders)
                    ?: probeMimeTypeWithRangeGet(url, sanitizedHeaders)
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
                extension == "mov" -> MimeTypes.VIDEO_QUICK_TIME
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
                            "mov" -> return MimeTypes.VIDEO_QUICK_TIME
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
            return (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = method
                setRequestProperty("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                headers.forEach { (key, value) ->
                    if (key.equals("Range", ignoreCase = true)) return@forEach
                    if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                    setRequestProperty(key, value)
                }
                range?.let { setRequestProperty("Range", it) }
            }
        }

        private fun readProbeSnippet(inputStream: InputStream?): String? {
            if (inputStream == null) return null
            val buffer = ByteArray(PROBE_BYTES)
            val read = inputStream.read(buffer)
            if (read <= 0) return null
            return String(buffer, 0, read, Charsets.UTF_8)
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
    }
}

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
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class PlayerMediaSourceFactory {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val playbackHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
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
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        mimeTypeOverride: String? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
            setDefaultRequestProperties(sanitizedHeaders)
            setUserAgent(DEFAULT_USER_AGENT)
        }

        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(url = url, filename = null)
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

        internal fun inferMimeType(url: String, filename: String?): String? {
            return inferMimeTypeFromPath(filename)
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
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8

                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
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
            filename: String? = null
        ): String? {
            inferMimeType(url = url, filename = filename)?.let { return it }

            val sanitizedHeaders = sanitizeHeaders(headers)

            return withContext(Dispatchers.IO) {
                probeMimeTypeWithHead(url, sanitizedHeaders)
                    ?: probeMimeTypeWithRangeGet(url, sanitizedHeaders)
            }
        }

        private fun inferMimeTypeFromPath(path: String?): String? {
            val normalized = path
                ?.substringBefore('#')
                ?.substringBefore('?')
                ?.lowercase(Locale.US)
                ?.trim()
                ?: return null

            return when {
                normalized.endsWith(".m3u8") ||
                    normalized.contains("/playlist") ||
                    normalized.contains("/hls") ||
                    normalized.contains("m3u8") -> MimeTypes.APPLICATION_M3U8

                normalized.endsWith(".mpd") ||
                    normalized.contains("/dash") -> MimeTypes.APPLICATION_MPD

                else -> null
            }
        }

        private fun probeMimeTypeWithHead(url: String, headers: Map<String, String>): String? {
            val connection = openConnection(url = url, headers = headers, method = "HEAD")
            return try {
                connection.responseCode
                normalizeMimeType(connection.contentType)
                    ?: inferMimeType(url = connection.url?.toString().orEmpty(), filename = null)
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
                normalizeMimeType(connection.contentType)
                    ?: inferMimeType(url = connection.url?.toString().orEmpty(), filename = null)
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
    }
}

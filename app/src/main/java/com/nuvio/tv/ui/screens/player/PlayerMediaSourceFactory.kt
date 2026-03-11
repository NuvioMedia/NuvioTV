package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.data.local.PlayerSettings
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.URLDecoder

@UnstableApi
internal class PlayerMediaSourceFactory {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null

    fun configureSubtitleParsing(
        extractorsFactory: ExtractorsFactory?,
        subtitleParserFactory: SubtitleParser.Factory?
    ) {
        customExtractorsFactory = extractorsFactory
        customSubtitleParserFactory = subtitleParserFactory
    }

    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeMb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB

    fun createMediaSource(
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setConnectTimeoutMs(8000)
            setReadTimeoutMs(8000)
            setAllowCrossProtocolRedirects(true)
            setDefaultRequestProperties(sanitizedHeaders)
            setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }

        val isHls = url.contains(".m3u8", ignoreCase = true) ||
            url.contains("/playlist", ignoreCase = true) ||
            url.contains("/hls", ignoreCase = true) ||
            url.contains("m3u8", ignoreCase = true)

        val isDash = url.contains(".mpd", ignoreCase = true) ||
            url.contains("/dash", ignoreCase = true)

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        when {
            isHls -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            isDash -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()

        val dataSourceFactory = if (useParallelConnections) {
            ParallelRangeDataSource.Factory(
                httpDataSourceFactory,
                parallelConnectionCount,
                parallelChunkSizeMb.toLong() * 1024 * 1024
            )
        } else {
            httpDataSourceFactory
        }

        val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory).apply {
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
            else -> {
                defaultFactory.createMediaSource(mediaItem)
            }
        }
    }

    fun shutdown() = Unit

    companion object {
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
    }
}

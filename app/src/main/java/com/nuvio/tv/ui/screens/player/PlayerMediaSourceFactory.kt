package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

internal class PlayerMediaSourceFactory(private val context: Context) {
    private var okHttpClient: OkHttpClient? = null
    private val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()

    fun createMediaSource(
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()
    ): MediaSource {
        val sanitizedHeaders = headers.filterKeys { !it.equals("Range", ignoreCase = true) }
        val okHttpFactory = OkHttpDataSource.Factory(getOrCreateOkHttpClient()).apply {
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
        val useVodCache = ENABLE_VOD_CACHE && !isHls && !isDash && shouldUseVodCache(url)
        val progressiveFactory = if (useVodCache && !isVodCacheDisabled) {
            runCatching {
                Log.d(TAG, "Using VOD cache for host=${Uri.parse(url).host ?: "unknown"}")
                buildVodCacheDataSourceFactory(okHttpFactory)
            }.getOrElse { error ->
                isVodCacheDisabled = true
                Log.e(TAG, "Disabling VOD cache after initialization failure", error)
                okHttpFactory
            }
        } else {
            okHttpFactory
        }
        return when {
            isHls -> HlsMediaSource.Factory(okHttpFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            isDash -> DashMediaSource.Factory(okHttpFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(progressiveFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(mediaItem)
        }
    }

    fun shutdown() {
        okHttpClient?.let { client ->
            Thread {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }.start()
            okHttpClient = null
        }
    }

    private fun getOrCreateOkHttpClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
        return okHttpClient ?: OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
            .also { okHttpClient = it }
    }

    private fun buildVodCacheDataSourceFactory(upstreamFactory: DataSource.Factory): DataSource.Factory {
        val cache = getOrCreateSimpleCache(context)
        val dataSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(2L * 1024L * 1024L)
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

    companion object {
        private const val TAG = "PlayerMediaSource"
        private const val ENABLE_VOD_CACHE = false
        private const val VOD_CACHE_DIR = "player_vod_cache"
        private const val VOD_CACHE_MAX_BYTES = 2L * 1024L * 1024L * 1024L
        @Volatile private var sharedSimpleCache: SimpleCache? = null
        @Volatile private var isVodCacheDisabled: Boolean = false

        fun parseHeaders(headers: String?): Map<String, String> {
            if (headers.isNullOrEmpty()) return emptyMap()

            return try {
                headers.split("&").associate { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }.filterKeys { it.isNotEmpty() }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun getOrCreateSimpleCache(context: Context): SimpleCache {
            sharedSimpleCache?.let { return it }
            synchronized(this) {
                sharedSimpleCache?.let { return it }
                val cacheDir = File(context.cacheDir, VOD_CACHE_DIR).apply { mkdirs() }
                return SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(VOD_CACHE_MAX_BYTES)
                ).also { sharedSimpleCache = it }
            }
        }
    }
}

private class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return when (dataType) {
            C.DATA_TYPE_MEDIA,
            C.DATA_TYPE_MANIFEST -> 6
            else -> 3
        }
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        if (!timeout) return super.getRetryDelayMsFor(loadErrorInfo)

        val retryDelayMs = when (loadErrorInfo.errorCount) {
            1 -> 750L
            2 -> 1_500L
            3 -> 3_000L
            4 -> 5_000L
            5 -> 8_000L
            else -> C.TIME_UNSET
        }
        Log.w(
            "PlayerMediaSource",
            "Timeout load error dataType=${dataTypeName(loadErrorInfo.mediaLoadData.dataType)} " +
                "host=${loadErrorInfo.loadEventInfo.uri.host ?: "unknown"} " +
                "attempt=${loadErrorInfo.errorCount} retryDelayMs=$retryDelayMs " +
                "error=${loadErrorInfo.exception.javaClass.simpleName}:${loadErrorInfo.exception.message}"
        )
        return retryDelayMs
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

private fun dataTypeName(dataType: Int): String = when (dataType) {
    C.DATA_TYPE_MEDIA -> "media"
    C.DATA_TYPE_MANIFEST -> "manifest"
    else -> "other($dataType)"
}

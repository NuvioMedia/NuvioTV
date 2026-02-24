package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

internal class PlayerMediaSourceFactory {
    private var okHttpClient: OkHttpClient? = null

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
        val defaultFactory = DefaultMediaSourceFactory(okHttpFactory)

        // Sidecar subtitles are more reliable through DefaultMediaSourceFactory.
        if (subtitleConfigurations.isNotEmpty()) {
            return defaultFactory.createMediaSource(mediaItem)
        }

        return when {
            isHls -> HlsMediaSource.Factory(okHttpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            isDash -> DashMediaSource.Factory(okHttpFactory)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
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
        return okHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(8000, TimeUnit.MILLISECONDS)
            .readTimeout(8000, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                var request = chain.request()
                val url = request.url
                // Some addons (like nzbdav, real-debrid, WebDAV servers) provide stream URLs containing Basic Auth credentials.
                // OkHttp does not automatically convert http://user:pass@host/ into an Authorization header,
                // which leads to HTTP 401 errors. This interceptor fixes it for the initial request natively.
                if (url.username.isNotEmpty() && url.password.isNotEmpty() && request.header("Authorization") == null) {
                    val credential = okhttp3.Credentials.basic(url.username, url.password)
                    request = request.newBuilder()
                        .header("Authorization", credential)
                        .build()
                }
                chain.proceed(request)
            }
            .authenticator { _, response ->
                // If a redirect occurs (e.g. nzbdav.com -> stream1.nzbdav.com), OkHttp drops the Authorization
                // header. Then the new host returns a 401. This authenticator traverses the prior response chain
                // to find the original URL containing the basic auth credentials, and retries the request with them.
                var priorResponse: okhttp3.Response? = response
                var originalUrlWithAuth: okhttp3.HttpUrl? = null

                while (priorResponse != null) {
                    val url = priorResponse.request.url
                    if (url.username.isNotEmpty() && url.password.isNotEmpty()) {
                        originalUrlWithAuth = url
                        break
                    }
                    priorResponse = priorResponse.priorResponse
                }

                if (originalUrlWithAuth != null) {
                    val credential = okhttp3.Credentials.basic(
                        originalUrlWithAuth.username, 
                        originalUrlWithAuth.password
                    )
                    
                    // If we already sent this exact credential for this specific 401 response and it still failed,
                    // we must return null to prevent an infinite loop.
                    if (response.request.header("Authorization") == credential) {
                        return@authenticator null
                    }
                    
                    return@authenticator response.request.newBuilder()
                        .header("Authorization", credential)
                        .build()
                }
                null
            }
            .build()
            .also { okHttpClient = it }
    }

    companion object {
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
    }
}

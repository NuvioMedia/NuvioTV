package com.nuvio.tv.reshaped.livetv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Load retries for Live TV channels. Nuvio fails a stream at once on HTTP 400/401/403/404/410,
 * which suits files. An IPTV server often refuses a channel only for a moment instead: the
 * channel just left still counts against a one-connection account for a second or two, or a
 * load balancer is busy. IPTV players such as TiviMate simply try again, so a channel gets a few
 * quick retries (0.7 s, 1.4 s, 2.1 s) before its error shows. Other errors keep Media3's defaults.
 */
@OptIn(UnstableApi::class)
object LiveTvLoadErrors {
    private const val HTTP_RETRIES = 3
    private const val HTTP_RETRY_STEP_MS = 700L

    private val policy = object : DefaultLoadErrorHandlingPolicy(
        DefaultLoadErrorHandlingPolicy.DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE,
    ) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (loadErrorInfo.exception.httpResponseCode() != null) {
                return if (loadErrorInfo.errorCount <= HTTP_RETRIES) HTTP_RETRY_STEP_MS * loadErrorInfo.errorCount else C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** Live TV's policy for a Live TV [url], else [fallback]. */
    fun policyFor(url: String, fallback: LoadErrorHandlingPolicy): LoadErrorHandlingPolicy =
        if (LiveTvPlaybackRegistry.isLiveTv(url)) policy else fallback

    private fun Throwable.httpResponseCode(): Int? {
        var current: Throwable? = this
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }
}

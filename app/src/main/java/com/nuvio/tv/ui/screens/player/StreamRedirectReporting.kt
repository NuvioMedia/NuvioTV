package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.nuvio.tv.domain.model.Stream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Reports the url the stream was actually served from whenever opening it followed a redirect.
 *
 * Only opens of [requestedUrl] are reported, so sidecar subtitles loading through the same chain
 * never count. It sits below the disk cache, which reads cached spans without opening the network.
 */
@OptIn(UnstableApi::class)
internal class RedirectReportingDataSource(
    private val upstream: DataSource,
    private val requestedUrl: String,
    private val onRedirect: (resolvedUrl: String) -> Unit
) : DataSource by upstream, androidx.media3.common.ByteBufferDataReader {

    // Interface delegation only covers DataSource, so without these the cache sees an upstream that
    // cannot do buffer reads and throws once a read crosses from a cached span back to the network.
    override fun supportsByteBufferRead(): Boolean =
        upstream is androidx.media3.common.ByteBufferDataReader && upstream.supportsByteBufferRead()

    override fun read(buffer: java.nio.ByteBuffer, length: Int): Int =
        (upstream as androidx.media3.common.ByteBufferDataReader).read(buffer, length)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        if (dataSpec.uri.toString() == requestedUrl) {
            val resolvedUrl = upstream.uri?.toString()
            if (resolvedUrl != null && canonicalStreamUrl(resolvedUrl) != canonicalStreamUrl(requestedUrl)) {
                onRedirect(resolvedUrl)
            }
        }
        return length
    }

    /**
     * Reports each new redirect target once. Seeks reopen the stream and land where the last open
     * did, while a release that fails mid-playback sends the reopen somewhere new.
     */
    class Factory(
        private val upstream: DataSource.Factory,
        private val requestedUrl: String,
        private val onRedirect: (resolvedUrl: String) -> Unit
    ) : DataSource.Factory {
        private val lastReportedUrl = AtomicReference<String?>(null)

        override fun createDataSource(): DataSource =
            RedirectReportingDataSource(upstream.createDataSource(), requestedUrl) { resolvedUrl ->
                if (lastReportedUrl.getAndSet(resolvedUrl) != resolvedUrl) onRedirect(resolvedUrl)
            }
    }
}

/**
 * Finds the listed stream a redirect landed on.
 *
 * Some addons answer a stream that will not open by redirecting the player to the next stream in
 * the same list, so the stream the player was launched with no longer describes what plays. A
 * redirect only identifies a stream when its target is another url the same addon listed: a debrid
 * link redirecting to its CDN lands on nothing listed and is left alone. An addon may carry the
 * requested url's query across the hop, so a target may also be a listed url plus that query.
 */
internal fun findRedirectTargetStream(
    streams: List<Stream>,
    addonName: String?,
    requestedUrl: String,
    resolvedUrl: String
): Stream? {
    val requested = canonicalStreamUrl(requestedUrl)
    val resolved = canonicalStreamUrl(resolvedUrl)
    if (resolved == requested) return null
    val requestedQuery = requestedUrl.substringAfter('?', missingDelimiterValue = "")
    return streams.firstOrNull { stream ->
        if (addonName != null && stream.addonName != addonName) return@firstOrNull false
        val url = stream.getStreamUrl() ?: return@firstOrNull false
        val candidateUrl = PlayerMediaSourceFactory.normalizePlaybackRequest(url, emptyMap()).url
        val candidate = canonicalStreamUrl(candidateUrl)
        if (candidate == requested) return@firstOrNull false
        candidate == resolved || (
            requestedQuery.isNotEmpty() &&
                canonicalStreamUrl("${candidateUrl.substringBefore('?')}?$requestedQuery") == resolved
            )
    }
}

// OkHttp re-encodes the url it reports after a redirect, so both sides are compared in its form.
private fun canonicalStreamUrl(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

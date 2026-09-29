@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/** An addon subtitle's original URL and own headers. */
internal data class SubtitleRoute(val url: String, val headers: Map<String, String>?)

private const val SUBTITLE_URI_MARKER = "nuvio_type=subtitle"

/** Where a request goes: its subtitle route, the media data source, or nowhere. */
internal sealed interface SubtitleRequestTarget {
    data class Routed(val route: SubtitleRoute) : SubtitleRequestTarget
    data object Media : SubtitleRequestTarget
    data object Refused : SubtitleRequestTarget
}

/**
 * An HTTP(S) URI in [routes] is routed. A marked HTTP(S) subtitle URI with no route is refused rather
 * than sent with the stream's headers. Everything else, including non-HTTP subtitles, goes to media.
 */
internal fun subtitleRequestTarget(uri: String, routes: Map<String, SubtitleRoute>): SubtitleRequestTarget {
    routes[uri]?.takeIf { it.url.toHttpUrlOrNull() != null }?.let { return SubtitleRequestTarget.Routed(it) }
    val isHttp = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
    val isMarked = SUBTITLE_URI_MARKER in uri.substringBefore('#').substringAfter('?', "").split('&')
    return if (isHttp && isMarked) SubtitleRequestTarget.Refused else SubtitleRequestTarget.Media
}

internal fun subtitleRouteRequest(
    route: SubtitleRoute,
    streamUrl: String,
    streamHeaders: Map<String, String>
): Request = buildSubtitleRequest(
    subtitleUrl = route.url.toHttpUrl(),
    streamUrl = streamUrl.toHttpUrlOrNull(),
    streamHeaders = streamHeaders,
    explicitHeaders = route.headers
)

/**
 * Serves addon subtitle tracks through the subtitle download path, so they get its header scoping,
 * redirect guard and TLS handling instead of the stream's data source and headers. Everything else
 * delegates to [media].
 *
 * [routes] is keyed by each subtitle configuration's URI as a string.
 */
internal class SubtitleRoutingDataSourceFactory(
    private val media: DataSource.Factory,
    private val streamUrl: String,
    private val streamHeaders: Map<String, String>,
    private val routes: Map<String, SubtitleRoute>
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SubtitleRoutingDataSource(media, routes) { route, dataSpec ->
        executeSubtitleRequest(subtitleRouteRequest(route, streamUrl, streamHeaders)).use { response ->
            if (!response.isSuccessful) {
                throw HttpDataSource.InvalidResponseCodeException(
                    response.code, response.message, null, response.headers.toMultimap(), dataSpec, ByteArray(0)
                )
            }
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Subtitle download returned no body")
        }
    }
}

private class SubtitleRoutingDataSource(
    private val media: DataSource.Factory,
    private val routes: Map<String, SubtitleRoute>,
    private val fetchSubtitle: (SubtitleRoute, DataSpec) -> ByteArray
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = subtitleRequestTarget(dataSpec.uri.toString(), routes)
        val source = when (target) {
            is SubtitleRequestTarget.Routed -> ByteArrayDataSource(fetchSubtitle(target.route, dataSpec))
            SubtitleRequestTarget.Media -> media.createDataSource()
            SubtitleRequestTarget.Refused -> throw IOException("No subtitle route for a marked subtitle URI")
        }
        transferListeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(if (target is SubtitleRequestTarget.Routed) dataSpec.withUri(Uri.parse(target.route.url)) else dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}

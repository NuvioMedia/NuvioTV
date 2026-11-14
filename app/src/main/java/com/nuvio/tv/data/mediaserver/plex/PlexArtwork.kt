package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerRepository
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Plex artwork needs the server token. Links the app keeps (rows, Continue watching, watch history) only carry a mark
 * in their fragment naming the connection, so no token is saved or synced; it is added when the picture is downloaded.
 */
object PlexArtworkLink {
    private const val MARK = "nuvio-plex="

    /** True for a link that only works through the app's image loader, which adds the token. */
    fun needsToken(url: String?): Boolean = url?.contains("#$MARK") == true

    /** A resized copy of [path] (a server path such as /library/metadata/1/thumb/2) from the server's picture service. */
    fun picture(address: String, connectionId: String, path: String, width: Int, height: Int): String? {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return path
        if (!path.startsWith("/")) return null
        val base = address.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + PHOTO_PATH)
            .query(null)
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("minSize", "1")
            .addQueryParameter("upscale", "1")
            .addQueryParameter("url", path)
            .build()
        return "$url#$MARK${URLEncoder.encode(connectionId, "UTF-8")}"
    }

    fun connectionId(url: HttpUrl): String? {
        val value = url.encodedFragment?.split('&')?.firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK) ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    internal const val PHOTO_PATH = "/photo/:/transcode"
}

/** The address and token a marked Plex link is fetched with. */
class PlexArtworkAccess(val address: String, val token: String) {
    override fun toString(): String = "PlexArtworkAccess(address=$address)"
}

@Singleton
class PlexArtworkSigner internal constructor(private val access: (String) -> PlexArtworkAccess?) {
    @Inject
    constructor(repository: ServerRepository) : this({ connectionId ->
        repository.session(connectionId)
            ?.takeIf { it.connection.providerId == PlexProvider.ID }
            ?.let { PlexArtworkAccess(it.connection.address, it.token) }
    })

    fun access(connectionId: String): PlexArtworkAccess? = access.invoke(connectionId)
}

/** Points a marked Plex artwork link saved before the server moved at the server's current address. */
class PlexArtworkInterceptor(private val signer: () -> PlexArtworkSigner?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connectionId = PlexArtworkLink.connectionId(request.url) ?: return chain.proceed(request)
        val access = signer()?.access(connectionId) ?: return chain.proceed(request)
        val server = access.address.toHttpUrlOrNull() ?: return chain.proceed(request)
        val url = moved(request.url, server)
        return chain.proceed(if (url == request.url) request else request.newBuilder().url(url).build())
    }

    /**
     * Adds the token as a network interceptor, so it goes out on the request to the connection's own server only.
     * A redirect is a new request without the link's mark and is never signed.
     */
    class Token(private val signer: () -> PlexArtworkSigner?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val connectionId = PlexArtworkLink.connectionId(request.url) ?: return chain.proceed(request)
            val access = signer()?.access(connectionId) ?: return chain.proceed(request)
            val server = access.address.toHttpUrlOrNull() ?: return chain.proceed(request)
            val url = request.url
            if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) return chain.proceed(request)
            return chain.proceed(request.newBuilder().header(PlexClient.TOKEN_HEADER, access.token).build())
        }
    }

    private fun moved(url: HttpUrl, server: HttpUrl): HttpUrl {
        if (server.scheme == url.scheme && server.host == url.host && server.port == url.port) return url
        if (!url.encodedPath.endsWith(PlexArtworkLink.PHOTO_PATH)) return url
        return url.newBuilder()
            .scheme(server.scheme)
            .host(server.host)
            .port(server.port)
            .encodedPath(server.encodedPath.trimEnd('/') + PlexArtworkLink.PHOTO_PATH)
            .build()
    }
}

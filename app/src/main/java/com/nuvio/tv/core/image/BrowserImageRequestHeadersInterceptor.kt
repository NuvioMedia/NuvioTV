package com.nuvio.tv.core.image

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

private const val BROWSER_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val BROWSER_IMAGE_ACCEPT =
    "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

/**
 * Gives remote poster hosts a browser-like request context.
 *
 * Plugin catalogs can point at hosts with hotlink protection. Coil loads their
 * poster URLs outside the plugin runtime, so it otherwise sends no referer and
 * uses OkHttp's default user agent. For every HTTP(S) image request, add safe
 * defaults only when the caller has not explicitly provided the header. The
 * referer is the image origin, which reveals no catalog or viewer information.
 */
internal class BrowserImageRequestHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().withBrowserImageRequestHeaders())
}

internal fun Request.withBrowserImageRequestHeaders(): Request {
    if (url.scheme !in setOf("http", "https")) return this

    val builder = newBuilder()
    if (header("User-Agent").isNullOrBlank()) {
        builder.header("User-Agent", BROWSER_IMAGE_USER_AGENT)
    }
    if (header("Accept").isNullOrBlank()) {
        builder.header("Accept", BROWSER_IMAGE_ACCEPT)
    }
    if (header("Referer").isNullOrBlank()) {
        val origin = url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        builder.header("Referer", origin.toString())
    }
    return builder.build()
}

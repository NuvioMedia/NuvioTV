package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}

/**
 * CloudflareKiller bypasses Cloudflare challenges using WebView.
 * Matches CloudStream's real implementation from RequestsHelper.kt + CloudflareKiller.kt.
 */
@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) { }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }
            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        Log.w(TAG, "Failed cloudflare at: ${request.url}")
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? {
        return try {
            CookieManager.getInstance()?.getCookie(url)
        } catch (_: Exception) { null }
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean {
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) {
                    Log.d(TAG, "Found cf_clearance cookie for ${request.url.host}")
                    savedCookies[request.url.host] = parseCookieMap(cookie)
                }
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val requestCookies = request.header("Cookie")?.let { parseCookieMap(it) } ?: emptyMap()
        val headers = getHeaders(
            request.headers.toMap() + userAgentMap,
            cookies + requestCookies
        )
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).execute()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
            try {
                WebViewResolver(
                    interceptUrl = Regex(".^"),
                    userAgent = null,
                    useOkhttp = false,
                    additionalUrls = listOf(Regex("."))
                ).resolveUsingWebView(url) {
                    trySolveWithSavedCookies(request)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebView bypass failed: ${e.message}")
                return null
            }
        }

        val cookies = savedCookies[request.url.host]
        if (cookies == null) {
            Log.w(TAG, "No cf_clearance obtained for ${request.url.host}")
            return null
        }
        return proceed(request, cookies)
    }

    private fun Headers.toMap(): Map<String, String> {
        return buildMap {
            for (i in 0 until size) {
                put(name(i), value(i))
            }
        }
    }
}

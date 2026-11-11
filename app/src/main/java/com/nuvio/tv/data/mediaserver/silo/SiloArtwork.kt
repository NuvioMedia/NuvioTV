package com.nuvio.tv.data.mediaserver.silo

import android.util.Log
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerRepository
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

enum class SiloArtworkKind { POSTER, BACKGROUND, LOGO }

/** The title a Silo artwork link belongs to, carried in the link's fragment (never sent to the server). */
data class SiloArtworkMark(val connectionId: String, val contentId: String, val kind: SiloArtworkKind) {
    val key: String
        get() = "$connectionId|$contentId|$kind"
}

/**
 * Silo signs its artwork links for a few hours. Each link the app keeps (rows, Continue watching, Library) names its
 * title, so an expired one can be swapped for a fresh link to the same picture.
 */
object SiloArtworkLink {
    private const val MARK = "nuvio-silo="

    fun mark(url: String, connectionId: String, contentId: String, kind: SiloArtworkKind): String {
        if ('#' in url || contentId.isBlank()) return url
        return "$url#$MARK${encode(connectionId)}.${encode(contentId)}.${kind.name.lowercase()}"
    }

    fun parse(url: HttpUrl): SiloArtworkMark? {
        val value = url.encodedFragment?.split('&')?.firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK) ?: return null
        val parts = value.split('.')
        if (parts.size != 3) return null
        val kind = SiloArtworkKind.entries.firstOrNull { it.name.equals(parts[2], ignoreCase = true) } ?: return null
        val connectionId = decode(parts[0])?.takeIf { it.isNotBlank() } ?: return null
        val contentId = decode(parts[1])?.takeIf { it.isNotBlank() } ?: return null
        return SiloArtworkMark(connectionId, contentId, kind)
    }

    /** Seconds since 1970 when Silo stops accepting [url]; null when the link carries no expiry of its own. */
    fun expiresAt(url: HttpUrl): Long? = url.queryParameter("exp")?.toLongOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace(".", "%2E")

    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}

/** Fresh Silo artwork links by title, fetched at most a few at a time and kept until shortly before they expire. */
@Singleton
class SiloArtworkRefresher internal constructor(
    private val fetch: suspend (SiloArtworkMark) -> Map<SiloArtworkKind, String>?,
    private val now: () -> Long
) {
    @Inject
    constructor(repository: ServerRepository) : this(
        fetch = { mark ->
            repository.call(mark.connectionId) { provider, session ->
                (provider as? SiloProvider)?.freshArtwork(session, mark.contentId)
            }
        },
        now = System::currentTimeMillis
    )

    private class Entry(val links: Map<SiloArtworkKind, String>?, val untilMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val pausedUntil = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(MAX_AT_ONCE)
    private val pace = Mutex()
    private var nextFetchMs = 0L

    /** A link to replace [stale] with, or null when there is no newer one. Blocks; called on the image threads. */
    fun fresh(mark: SiloArtworkMark, stale: HttpUrl): HttpUrl? = runBlocking { freshLink(mark, stale) }

    internal suspend fun freshLink(mark: SiloArtworkMark, stale: HttpUrl): HttpUrl? {
        val title = "${mark.connectionId}|${mark.contentId}"
        val entry = locks.getOrPut(title) { Mutex() }.withLock {
            entries[title]?.takeIf { now() < it.untilMs } ?: load(mark).also { entries[title] = it }
        }
        val fresh = entry.links?.get(mark.kind)?.toHttpUrlOrNull() ?: return null
        val staleBase = stale.newBuilder().fragment(null).build()
        return fresh.takeIf { it != staleBase }
    }

    /** Silo allows about 20 calls a second per user, shared with browsing and playback: a few lookups at a time. */
    private suspend fun load(mark: SiloArtworkMark): Entry {
        if (entries.size >= MAX_TITLES) {
            entries.clear()
            locks.clear()
        }
        pausedUntil[mark.connectionId]?.takeIf { now() < it }?.let { return Entry(null, it) }
        var unreachable = true
        val links = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                permits.withPermit {
                    awaitSlot()
                    fetch(mark).also { unreachable = false }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ServerException) {
                Log.w(TAG, "Fresh Silo artwork failed: ${error.failure} ${error.siloCode.orEmpty()}")
                unreachable = error.failure == ServerFailure.UNREACHABLE || error.siloCode == "rate_limited"
                null
            } catch (error: Exception) {
                Log.w(TAG, "Fresh Silo artwork failed: ${error.javaClass.simpleName}")
                unreachable = false
                null
            }
        }
        if (links == null) {
            val until = now() + RETRY_AFTER_MS
            if (unreachable) pausedUntil[mark.connectionId] = until
            return Entry(null, until)
        }
        val expiry = links.values.mapNotNull { it.toHttpUrlOrNull()?.let(SiloArtworkLink::expiresAt) }.minOrNull()
        val until = expiry?.let { it * 1_000L - RENEW_BEFORE_MS } ?: (now() + KEEP_MS)
        return Entry(links, until.coerceIn(now() + RETRY_AFTER_MS, now() + KEEP_MS))
    }

    private suspend fun awaitSlot() {
        val wait = pace.withLock {
            val start = maxOf(now(), nextFetchMs)
            nextFetchMs = start + FETCH_SPACING_MS
            start - now()
        }
        if (wait > 0) delay(wait)
    }

    private companion object {
        const val TAG = "SiloArtwork"
        const val MAX_AT_ONCE = 3
        const val FETCH_SPACING_MS = 200L
        const val MAX_TITLES = 512
        const val FETCH_TIMEOUT_MS = 6_000L
        const val RETRY_AFTER_MS = 60_000L
        const val RENEW_BEFORE_MS = 5 * 60_000L
        const val KEEP_MS = 60 * 60_000L
    }
}

/** Swaps an expired (or refused) Silo artwork link for a fresh one before the picture is downloaded. */
class SiloArtworkInterceptor(
    private val refresher: () -> SiloArtworkRefresher?,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val mark = SiloArtworkLink.parse(request.url) ?: return chain.proceed(request)
        val refresh = refresher() ?: return chain.proceed(request)
        val expiresAt = SiloArtworkLink.expiresAt(request.url)
        if (expiresAt != null && expiresAt - EXPIRY_MARGIN_S <= nowSeconds()) {
            refresh.fresh(mark, request.url)?.let { return chain.proceed(request.newBuilder().url(it).build()) }
        }
        val response = chain.proceed(request)
        if (response.code !in REFUSED) return response
        val fresh = refresh.fresh(mark, request.url) ?: return response
        response.close()
        return chain.proceed(request.newBuilder().url(fresh).build())
    }

    private companion object {
        const val EXPIRY_MARGIN_S = 60L
        val REFUSED = setOf(403, 404, 410)
    }
}

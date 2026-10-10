package com.nuvio.tv.core.usenet

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import java.io.IOException
import java.text.DateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Requests to an indexer are paused until [until] because it reported a limit. */
class IndexerCooldownException(val until: Long) : IllegalStateException("Indexer limit reached")

@Singleton
class NewznabClient @Inject constructor(
    storage: NewznabStateStorage
) {
    // API keys are in query strings: use a dedicated client without URL logging,
    // Sentry breadcrumbs or a disk response cache.
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val states = NewznabIndexerStates(storage, ::now)

    /** Caps cost an API hit on most indexers and rarely change; [live] skips the cache and any cooldown. */
    suspend fun capabilities(indexer: UsenetIndexer, live: Boolean = false): NewznabCapabilities {
        val cached = states.get(indexer)?.takeIf { it.caps != null }
        if (!live && cached != null && now() - cached.capsFetchedAt < CAPS_TTL_MS) return cached.caps!!
        val caps = try {
            request(indexer, NewznabProtocol.apiUrl(indexer, "caps").build(), live, NewznabProtocol::capabilities)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // An expired copy still describes the indexer better than failing the search.
            if (!live && cached != null) return cached.caps!! else throw e
        }
        states.update(indexer) { it.copy(caps = caps, capsFetchedAt = now()) }
        return caps
    }

    suspend fun search(indexer: UsenetIndexer, request: UsenetSearchRequest,
        caps: NewznabCapabilities): List<UsenetRelease> {
        val url = NewznabProtocol.searchUrl(indexer, request, caps) ?: return emptyList()
        val first = request(indexer, url) { NewznabProtocol.releases(it, indexer) }
        val limit = caps.limit.coerceIn(1, 100)
        // Bound both API usage and memory; never download NZBs during a search.
        val second = if (first.total > limit && first.releases.size >= limit) {
            try {
                NewznabProtocol.searchUrl(indexer, request, caps, limit)?.let { next ->
                    request(indexer, next) { NewznabProtocol.releases(it, indexer) }.releases
                }.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        } else emptyList()
        val idSearch = url.queryParameter("imdbid") != null || url.queryParameter("tmdbid") != null
        return (first.releases + second).filter { NewznabProtocol.matches(it, request, idSearch) }
    }

    /**
     * Skips indexers in a cooldown without a request, and opens one when an
     * indexer throttles us or reports a spent quota, so later searches do not
     * spend requests that can only fail.
     */
    private suspend fun <T> request(indexer: UsenetIndexer, url: HttpUrl, ignoreCooldown: Boolean = false,
        parse: (String) -> T): T {
        val blockedUntil = states.get(indexer)?.blockedUntil ?: 0
        if (!ignoreCooldown && blockedUntil > now()) throw IndexerCooldownException(blockedUntil)
        val response = get(url)
        val retryAfter = retryAfterMs(response.headers["Retry-After"])
        if (response.code == 429) throw cooldown(indexer, retryAfter?.coerceAtMost(MAX_THROTTLE_MS) ?: THROTTLE_MS)
        return try {
            if (response.code !in 200..299) {
                NewznabProtocol.throwIfQuotaError(response.body)
                error("Indexer HTTP ${response.code}")
            }
            parse(response.body).also {
                // Indexers announce a spent daily quota before they start refusing requests.
                if (REMAINING_HEADERS.any { response.headers[it]?.trim()?.toIntOrNull() == 0 }) {
                    cooldown(indexer, EXHAUSTED_MS)
                }
            }
        } catch (_: NewznabQuotaException) {
            throw cooldown(indexer, retryAfter ?: QUOTA_MS)
        }
    }

    /** Extends, never shortens, the cooldown: concurrent refusals must not undercut each other. */
    private fun cooldown(indexer: UsenetIndexer, durationMs: Long): IndexerCooldownException {
        states.update(indexer) { it.copy(blockedUntil = maxOf(it.blockedUntil, now() + durationMs)) }
        return IndexerCooldownException(states.get(indexer)?.blockedUntil ?: (now() + durationMs))
    }

    private fun retryAfterMs(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return if (it > 0) it * 1000 else null }
        return runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun now() = System.currentTimeMillis()

    private class IndexerResponse(val code: Int, val headers: Headers, val body: String)

    private suspend fun get(url: HttpUrl): IndexerResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Nuvio/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-store").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Could not reach indexer"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source() ?: error("Empty indexer response")
                        source.request(MAX_XML_BYTES + 1)
                        check(source.buffer.size <= MAX_XML_BYTES) { "Indexer response is too large" }
                        IndexerResponse(it.code, it.headers, source.readByteArray().toString(Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        const val MAX_XML_BYTES = 4L * 1024 * 1024
        const val CAPS_TTL_MS = 7L * 24 * 3600 * 1000
        const val THROTTLE_MS = 60_000L
        const val MAX_THROTTLE_MS = 15 * 60_000L
        const val QUOTA_MS = 30 * 60_000L
        const val EXHAUSTED_MS = 15 * 60_000L
        val REMAINING_HEADERS = listOf("X-RateLimit-Daily-Remaining", "x-api-remaining",
            "X-DNZBLimit-Daily-Remaining", "x-grab-remaining")
    }
}

data class BuiltInUsenetResult(val group: AddonStreams? = null, val failure: String? = null)

@Singleton
class BuiltInUsenetService @Inject constructor(
    private val client: NewznabClient,
    private val tmdb: TmdbService,
    private val tmdbApi: TmdbApi
) {
    fun search(config: UsenetSourceConfiguration, type: String, videoId: String,
        season: Int?, episode: Int?): Flow<BuiltInUsenetResult> = flow {
        if (!config.ready || type.lowercase() !in listOf("movie", "series", "tv", "show")) return@flow
        val series = type.lowercase() != "movie"
        val parts = videoId.split(':')
        val hasEpisodeSuffix = parts.size >= if (videoId.startsWith("tmdb:")) 4 else 3
        val resolvedSeason = season ?: if (hasEpisodeSuffix) parts[parts.lastIndex - 1].toIntOrNull() else null
        val resolvedEpisode = episode ?: if (hasEpisodeSuffix) parts.last().toIntOrNull() else null
        val baseImdb = videoId.substringBefore(':').takeIf { it.matches(Regex("tt\\d+")) }
        val baseTmdb = videoId.removePrefix("tmdb:").substringBefore(':')
            .takeIf { it.toIntOrNull() != null }
        val request = UsenetSearchRequest(baseImdb, baseTmdb, season = resolvedSeason,
            episode = resolvedEpisode, series = series)
        if (series && (resolvedSeason == null || resolvedSeason < 0 || resolvedEpisode == null || resolvedEpisode < 1)) return@flow
        coroutineScope {
            // A failed child would cancel this scope; keep lookup failures per indexer.
            val metadata = async(start = CoroutineStart.LAZY) {
                try { metadata(request, videoId, type) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            val releases = mutableListOf<UsenetRelease>()
            // Equal priorities are searched together; lower ones only when nothing playable was found.
            val tiers = config.indexers.filter { it.enabled }.groupBy { it.priority }.toSortedMap().values
            for (tier in tiers) {
                val results = Channel<Pair<UsenetIndexer, Result<List<UsenetRelease>>>>(Channel.UNLIMITED)
                val jobs = tier.map { indexer ->
                    launch {
                        val result = try {
                            val caps = client.capabilities(indexer)
                            val canSearchId = NewznabProtocol.searchUrl(indexer, request, caps) != null
                            val search = if (canSearchId) request else metadata.await() ?: error("Metadata unavailable")
                            Result.success(client.search(indexer, search, caps))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Result.failure(e) }
                        results.send(indexer to result)
                    }
                }
                launch { jobs.joinAll(); results.close() }
                for ((indexer, result) in results) {
                    if (result.isFailure) {
                        // Never display raw exception messages: network/parser errors can contain keys.
                        val cooldown = result.exceptionOrNull() as? IndexerCooldownException
                        emit(BuiltInUsenetResult(failure = if (cooldown != null) {
                            "${indexer.name}: indexer limit reached, paused until " +
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(cooldown.until)) + "."
                        } else "${indexer.name}: indexer search failed. Check the API URL, key and limits."))
                    } else {
                        releases += result.getOrThrow()
                        val arranged = NewznabProtocol.arrange(releases, config)
                        if (arranged.isNotEmpty()) emit(BuiltInUsenetResult(group = AddonStreams(GROUP_NAME, null,
                            arranged.map { it.toStream(config) })))
                    }
                }
                if (NewznabProtocol.arrange(releases, config).isNotEmpty()) break
            }
            metadata.cancel()
        }
    }

    private suspend fun metadata(request: UsenetSearchRequest, videoId: String, type: String): UsenetSearchRequest {
        val id = request.tmdbId ?: tmdb.ensureTmdbId(videoId, type)
        val imdb = request.imdbId ?: id?.toIntOrNull()?.let { tmdb.tmdbToImdb(it, type) }
        val details = try {
            id?.toIntOrNull()?.let {
                if (request.series) tmdbApi.getTvDetails(it, BuildConfig.TMDB_API_KEY).body()
                else tmdbApi.getMovieDetails(it, BuildConfig.TMDB_API_KEY).body()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        return request.copy(imdbId = imdb, tmdbId = id, title = details?.title ?: details?.name,
            year = (details?.releaseDate ?: details?.firstAirDate)?.take(4)?.toIntOrNull())
    }

    private fun UsenetRelease.toStream(config: UsenetSourceConfiguration): Stream {
        val sizeLabel = if (size > 0) "%.2f GB".format(Locale.ROOT, size / (1024.0 * 1024 * 1024)) else null
        val age = if (publishedAt > 0) "${((System.currentTimeMillis() - publishedAt) / 86400000).coerceAtLeast(0)}d" else null
        return Stream(name = "Usenet • $indexerName", title = title,
            description = listOfNotNull(sizeLabel, age, indexerName).joinToString(" • "),
            url = null, ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = null, addonName = GROUP_NAME, addonLogo = null,
            nzbUrl = nzbUrl, servers = config.providers.filter { it.enabled }.map { it.serverUrl() },
            quality = resolution.takeIf { it > 0 }?.let { "${it}p" }, qualityValue = resolution)
    }

    companion object { const val GROUP_NAME = "Built-in Usenet" }
}

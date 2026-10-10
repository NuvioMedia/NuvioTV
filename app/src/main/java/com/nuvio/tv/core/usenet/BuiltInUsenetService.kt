package com.nuvio.tv.core.usenet

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class NewznabClient @Inject constructor() {
    // API keys are in query strings: use a dedicated client without URL logging,
    // Sentry breadcrumbs or a disk response cache.
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val capabilities = ConcurrentHashMap<UsenetIndexer, Pair<Long, NewznabCapabilities>>()

    suspend fun capabilities(indexer: UsenetIndexer): NewznabCapabilities {
        capabilities[indexer]?.takeIf { System.currentTimeMillis() - it.first < 3600000 }?.let { return it.second }
        val caps = NewznabProtocol.capabilities(get(NewznabProtocol.apiUrl(indexer, "caps").build()))
        if (capabilities.size >= 100) capabilities.clear()
        capabilities[indexer] = System.currentTimeMillis() to caps
        return caps
    }

    suspend fun search(indexer: UsenetIndexer, request: UsenetSearchRequest,
        caps: NewznabCapabilities): List<UsenetRelease> {
        val url = NewznabProtocol.searchUrl(indexer, request, caps) ?: return emptyList()
        val first = NewznabProtocol.releases(get(url), indexer)
        val limit = caps.limit.coerceIn(1, 100)
        // Bound both API usage and memory; never download NZBs during a search.
        val second = if (first.total > limit && first.releases.size >= limit) {
            try {
                NewznabProtocol.searchUrl(indexer, request, caps, limit)?.let {
                    NewznabProtocol.releases(get(it), indexer).releases
                }.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        } else emptyList()
        val idSearch = url.queryParameter("imdbid") != null || url.queryParameter("tmdbid") != null
        return (first.releases + second).filter { NewznabProtocol.matches(it, request, idSearch) }
    }

    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Nuvio/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-store").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Could not reach indexer"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val xml = response.use {
                        check(it.isSuccessful) { if (it.code == 429) "Indexer rate limit reached" else "Indexer HTTP ${it.code}" }
                        val source = it.body?.source() ?: error("Empty indexer response")
                        source.request(MAX_XML_BYTES + 1)
                        check(source.buffer.size <= MAX_XML_BYTES) { "Indexer response is too large" }
                        val bytes = source.readByteArray()
                        bytes.toString(Charsets.UTF_8)
                    }
                    if (continuation.isActive) continuation.resume(xml)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private companion object { const val MAX_XML_BYTES = 4L * 1024 * 1024 }
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
            val metadata = async(start = CoroutineStart.LAZY) { metadata(request, videoId, type) }
            val results = Channel<Pair<UsenetIndexer, Result<List<UsenetRelease>>>>(Channel.UNLIMITED)
            val jobs = config.indexers.filter { it.enabled }.map { indexer ->
                launch {
                    val result = try {
                        val caps = client.capabilities(indexer)
                        val canSearchId = NewznabProtocol.searchUrl(indexer, request, caps) != null
                        Result.success(client.search(indexer, if (canSearchId) request else metadata.await(), caps))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                    results.send(indexer to result)
                }
            }
            launch { jobs.forEach { it.join() }; results.close() }
            val releases = mutableListOf<UsenetRelease>()
            for ((indexer, result) in results) {
                if (result.isFailure) {
                    // Never display raw exception messages: network/parser errors can contain keys.
                    emit(BuiltInUsenetResult(failure = "${indexer.name}: indexer search failed. Check the API URL, key and limits."))
                } else {
                    releases += result.getOrThrow()
                    val arranged = NewznabProtocol.arrange(releases, config)
                    if (arranged.isNotEmpty()) emit(BuiltInUsenetResult(group = AddonStreams(GROUP_NAME, null,
                        arranged.map { it.toStream(config) })))
                }
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

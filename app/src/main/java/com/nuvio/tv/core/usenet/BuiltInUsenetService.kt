package com.nuvio.tv.core.usenet

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamBehaviorHints
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
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
class NewznabClient internal constructor(
    storage: NewznabStateStorage,
    /** The User-Agent of the next request; settings can change it at any time. */
    private val agent: () -> String = { NUVIO_USER_AGENT }
) {
    @Inject constructor(storage: NewznabStateStorage, settings: UsenetSettings) : this(storage, { settings.settings.value.agent })

    // API keys are in query strings: use a dedicated client without URL logging,
    // Sentry breadcrumbs or a disk response cache.
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val states = NewznabIndexerStates(storage, ::now)
    private val seasons = object : LinkedHashMap<String, Pair<Long, List<UsenetRelease>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<UsenetRelease>>>) =
            size > MAX_SEASONS
    }

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
        seasonReleases(indexer, request, caps)
            ?.filter { NewznabProtocol.matches(it, request, idSearch = true, requireNumbering = true) }
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        val url = NewznabProtocol.searchUrl(indexer, request, caps) ?: return emptyList()
        return pages(indexer, url, request, caps)
            .filter { NewznabProtocol.matches(it, request, NewznabProtocol.isIdSearch(url)) }
    }

    /**
     * Checks the endpoint and the API key with live requests. Caps alone prove nothing about
     * the key, since many indexers serve them publicly; a one-result search uses it.
     */
    suspend fun verify(indexer: UsenetIndexer) {
        capabilities(indexer, live = true)
        val latest = NewznabProtocol.apiUrl(indexer, "search").setQueryParameter("limit", "1").build()
        // A search refused for its parameters still means the key was accepted; a page that is
        // no Newznab answer (a login page served with the public caps) does not.
        request(indexer, latest, ignoreCooldown = true, NewznabProtocol::requireSearchAnswer)
    }

    /**
     * Watching a series asks for one episode after another, and a single season
     * search answers them all for [SEASON_TTL_MS]. An episode it has nothing for
     * (one that aired since, or a season with more releases than two pages hold)
     * is then searched on its own. Memory only: release URLs carry the API key.
     */
    private suspend fun seasonReleases(indexer: UsenetIndexer, request: UsenetSearchRequest,
        caps: NewznabCapabilities): List<UsenetRelease>? {
        val url = NewznabProtocol.searchUrl(indexer, request, caps, wholeSeason = true) ?: return null
        val key = url.toString()
        // Keyed by the endpoint and key, which another profile or a renamed indexer can share:
        // reused releases take the asking indexer's id and name, which ordering and deduplication use.
        synchronized(seasons) { seasons[key] }?.takeIf { now() - it.first < SEASON_TTL_MS }?.let { cached ->
            return cached.second.map { it.copy(indexerId = indexer.id, indexerName = indexer.name) }
        }
        val releases = try {
            pages(indexer, url, request, caps, wholeSeason = true)
        } catch (e: CancellationException) { throw e }
        catch (e: IndexerCooldownException) { throw e }
        // Some indexers reject season-only searches; remember it so each episode does not retry.
        catch (_: Exception) { emptyList() }
        synchronized(seasons) { seasons[key] = now() to releases }
        return releases
    }

    /** Bound both API usage and memory to two pages; never download NZBs during a search. */
    private suspend fun pages(indexer: UsenetIndexer, url: HttpUrl, request: UsenetSearchRequest,
        caps: NewznabCapabilities, wholeSeason: Boolean = false): List<UsenetRelease> {
        val first = request(indexer, url) { NewznabProtocol.releases(it, indexer) }
        val limit = caps.limit.coerceIn(1, 100)
        val second = if (first.total > limit && first.releases.size >= limit) {
            try {
                NewznabProtocol.searchUrl(indexer, request, caps, limit, wholeSeason)?.let { next ->
                    request(indexer, next) { NewznabProtocol.releases(it, indexer) }.releases
                }.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        } else emptyList()
        return first.releases + second
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
        val response = try { get(url) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) { note(indexer, IndexerProblem.UNREACHABLE); throw e }
            catch (e: Exception) { note(indexer, IndexerProblem.RESPONSE); throw e }
        val retryAfter = retryAfterMs(response.headers["Retry-After"])
        if (response.code == 429) throw cooldown(indexer, retryAfter?.coerceAtMost(MAX_THROTTLE_MS) ?: THROTTLE_MS)
        return try {
            if (response.code !in 200..299) {
                NewznabProtocol.throwIfKnownError(response.body)
                if (response.code == 401 || response.code == 403) throw NewznabAuthException()
                error("Indexer HTTP ${response.code}")
            }
            parse(response.body).also {
                val apiHitsLeft = left(response.headers, API_LEFT_HEADERS)
                val grabsLeft = left(response.headers, GRABS_LEFT_HEADERS)
                // Indexers announce a spent daily quota before they start refusing requests.
                if (apiHitsLeft == 0 || grabsLeft == 0) cooldown(indexer, EXHAUSTED_MS)
                if (apiHitsLeft != null || grabsLeft != null) states.update(indexer) {
                    it.copy(problem = null, apiHitsLeft = apiHitsLeft, grabsLeft = grabsLeft, leftAt = now())
                } else note(indexer, null)
            }
        } catch (_: NewznabQuotaException) {
            throw cooldown(indexer, retryAfter ?: QUOTA_MS)
        } catch (e: NewznabAuthException) {
            note(indexer, IndexerProblem.AUTH); throw e
        } catch (e: Exception) {
            note(indexer, IndexerProblem.RESPONSE); throw e
        }
    }

    /** Most requests succeed: only a changed outcome is written. */
    private fun note(indexer: UsenetIndexer, problem: IndexerProblem?) {
        if (states.get(indexer)?.problem != problem) states.update(indexer) { it.copy(problem = problem) }
    }

    /** Increments whenever an indexer status may have changed. */
    val statusChanges: StateFlow<Int> get() = states.revision

    fun status(indexer: UsenetIndexer): IndexerStatus = states.get(indexer).let { state ->
        // The counts are daily: one from before the indexer's last reset would mislead.
        val fresh = state != null && now() - state.leftAt < LEFT_TTL_MS
        IndexerStatus(state?.blockedUntil ?: 0, state?.problem, state?.apiHitsLeft?.takeIf { fresh },
            state?.grabsLeft?.takeIf { fresh }, state?.leftAt?.takeIf { fresh } ?: 0)
    }

    private fun left(headers: Headers, names: List<String>) =
        names.firstNotNullOfOrNull { headers[it]?.trim()?.toIntOrNull() }?.coerceAtLeast(0)

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
        val call = client.newCall(Request.Builder().url(url).header(USER_AGENT, agent())
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
        const val SEASON_TTL_MS = 2 * 3600_000L
        const val MAX_SEASONS = 50
        const val LEFT_TTL_MS = 24 * 3600_000L
        val API_LEFT_HEADERS = listOf("X-RateLimit-Daily-Remaining", "x-api-remaining")
        val GRABS_LEFT_HEADERS = listOf("X-DNZBLimit-Daily-Remaining", "x-grab-remaining")
    }
}

data class BuiltInUsenetResult(val group: AddonStreams? = null, val failure: String? = null)

@Singleton
class BuiltInUsenetService @Inject constructor(
    private val client: NewznabClient,
    private val tmdb: TmdbService,
    private val tmdbApi: TmdbApi,
    private val unplayable: UnplayableReleases? = null
) {
    fun search(config: UsenetSourceConfiguration, type: String, videoId: String,
        season: Int?, episode: Int?): Flow<BuiltInUsenetResult> = flow {
        if (!config.ready || type.lowercase() !in BUILT_IN_USENET_TYPES) return@flow
        val series = type.lowercase() != "movie"
        val (idSeason, idEpisode) = episodeNumbers(videoId)
        val resolvedSeason = season ?: idSeason
        val resolvedEpisode = episode ?: idEpisode
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
            val servers = config.providers.filter { it.enabled }.map { it.serverUrl() }
            // Each season's episode count lets season packs, of one season or several, be filtered and
            // sorted by their size per episode. The show's seasons are asked of TMDB once the first pack
            // shows up, alongside the indexers, and the list never waits for them: packs count with their
            // whole size until they come, and the list is arranged again then. Without an answer within
            // a few seconds nothing changes.
            val seasonEpisodes = java.util.concurrent.atomic.AtomicReference<Map<Int, Int>?>(null)
            val seasonLookup = if (!series) null else launch(start = CoroutineStart.LAZY) {
                val started = System.currentTimeMillis()
                val counts = withTimeoutOrNull(SEASON_LOOKUP_TIMEOUT_MS) {
                    try {
                        (request.tmdbId ?: tmdb.ensureTmdbId(videoId, type))?.toIntOrNull()?.let { id ->
                            tmdbApi.getTvDetails(id, BuildConfig.TMDB_API_KEY).body()?.seasons?.mapNotNull {
                                val number = it.seasonNumber ?: return@mapNotNull null
                                it.episodeCount?.takeIf { count -> count > 0 }?.let { count -> number to count }
                            }?.toMap()
                        }?.takeIf { it.isNotEmpty() }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                seasonEpisodes.set(counts)
                runCatching { android.util.Log.i("UsenetSearch", "season episodes=$counts ms=${System.currentTimeMillis() - started}") }
            }
            fun seasonCounts(): Map<Int, Int>? = seasonEpisodes.get()
            fun posted(release: UsenetRelease) = release.publishedAt.takeIf { it > 0 }
            fun failed(release: UsenetRelease) = unplayable?.contains(release.nzbUrl, release.title, release.size, servers, posted(release)) == true
            fun played(release: UsenetRelease) = unplayable?.played(release.nzbUrl, release.title, release.size, servers, posted(release)) == true
            fun sibling(release: UsenetRelease) = unplayable?.sibling(release.title, servers, posted(release))
            // Releases that already failed for good with these providers go last, still selectable;
            // ones that played recently go first unless turned off.
            fun arranged(): List<UsenetRelease> {
                // Each release's outcome is looked up once: the sort compares releases many times.
                val ranks = releases.associate {
                    it.nzbUrl to outcomeRank(failed(it) || sibling(it)?.blank == true, played(it), config.availableFirst)
                }
                return NewznabProtocol.arrange(
                    if (config.hideDuplicates) NewznabProtocol.withoutDuplicates(releases, config, seasonEpisodes = seasonCounts(), series = series) else releases, config,
                    seasonEpisodes = seasonCounts(), series = series) { ranks[it.nzbUrl] ?: 1 }
            }
            val enabled = config.indexers.filter { it.enabled }
            // Every indexer is searched at once unless the user chose priority fallback: then equal
            // priorities are searched together and lower ones only when nothing playable was found.
            val tiers = if (config.indexerFallback) enabled.groupBy { it.priority }.toSortedMap().values else listOf(enabled)
            for (tier in tiers) {
                val results = Channel<Pair<UsenetIndexer, Result<List<UsenetRelease>>>>(Channel.UNLIMITED)
                val jobs = tier.map { indexer ->
                    launch {
                        val result = try {
                            val caps = client.capabilities(indexer)
                            val canSearchId = NewznabProtocol.searchUrl(indexer, request, caps) != null
                            val search = if (canSearchId) request else metadata.await() ?: error("Metadata unavailable")
                            val found = client.search(indexer, search, caps)
                            Result.success(if (found.isEmpty()) askAgain(indexer, search, caps) { metadata.await() } ?: found else found)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Result.failure(e) }
                        results.send(indexer to result)
                    }
                }
                launch { jobs.joinAll(); results.close() }
                val tierStarted = System.currentTimeMillis()
                fun log(message: String) = runCatching {
                    android.util.Log.i("UsenetSearch", "${System.currentTimeMillis() - tierStarted}ms $message")
                }
                suspend fun show() {
                    val arranged = arranged()
                    log("show ${arranged.size} results")
                    if (arranged.isNotEmpty()) emit(BuiltInUsenetResult(group = AddonStreams(GROUP_NAME, null,
                        arranged.map { it.toStream(config, failed(it), played(it), pack = series && it.packSeasons.isNotEmpty(),
                            seasonEpisodes = seasonCounts(), sibling = sibling(it)) })))
                }
                // Like other sources, the list appears whole once every indexer answered, unless one is
                // slow: after a short wait it shows what came and fills in from there. Progressive
                // results show each indexer's answer as it comes.
                val wait = if (config.progressiveResults) null else async { delay(COMPLETE_RESULTS_WAIT_MS) }
                var waiting = wait != null
                var lookupShown = false
                while (true) {
                    val event = select<Any> {
                        results.onReceiveCatching { it }
                        if (waiting) wait?.onAwait { WAIT_OVER }
                        // Arranged again once the episode count came, if the list is already shown.
                        if (!lookupShown && seasonLookup?.isActive == true) seasonLookup.onJoin { SEASON_COUNTED }
                    }
                    if (event === WAIT_OVER) {
                        log("wait over")
                        waiting = false
                        show()
                        continue
                    }
                    if (event === SEASON_COUNTED) {
                        lookupShown = true
                        if (!waiting && seasonEpisodes.get() != null) show()
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val received = event as ChannelResult<Pair<UsenetIndexer, Result<List<UsenetRelease>>>>
                    val (indexer, result) = received.getOrNull() ?: break
                    log("${indexer.name} answered: ${result.getOrNull()?.size ?: "failed"}")
                    if (result.isFailure) {
                        // Never display raw exception messages: network/parser errors can contain keys.
                        val cooldown = result.exceptionOrNull() as? IndexerCooldownException
                        emit(BuiltInUsenetResult(failure = if (cooldown != null) {
                            "${indexer.name}: indexer limit reached, paused until " +
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(cooldown.until)) + "."
                        } else "${indexer.name}: indexer search failed. Check the API URL, key and limits."))
                    } else {
                        releases += result.getOrThrow()
                        if (releases.any { it.packSeasons.isNotEmpty() }) seasonLookup?.start()
                        if (!waiting) show()
                    }
                }
                wait?.cancel()
                if (waiting) show()
                // Every indexer answered before the episode count: the list is shown, and is arranged
                // again when the count comes within its few seconds.
                if (!lookupShown && seasonLookup?.isActive == true) {
                    seasonLookup.join()
                    if (seasonEpisodes.get() != null) show()
                }
                if (arranged().isNotEmpty()) break
            }
            metadata.cancel()
            seasonLookup?.cancel()
        }
    }

    /**
     * Asks again when a search found nothing: by title after an ID search (some indexers have thin
     * ID coverage), then by the original title, under which foreign releases are often posted.
     * Each attempt is made only when the ones before it found nothing. Null when none found anything.
     */
    private suspend fun askAgain(indexer: UsenetIndexer, searched: UsenetSearchRequest, caps: NewznabCapabilities,
        metadata: suspend () -> UsenetSearchRequest?): List<UsenetRelease>? {
        val url = NewznabProtocol.searchUrl(indexer, searched, caps) ?: return null
        val byTitle = (metadata() ?: return null).copy(imdbId = null, tmdbId = null, tvdbId = null)
        val attempts = listOfNotNull(
            byTitle.takeIf { NewznabProtocol.isIdSearch(url) },
            // Indexers search Latin text; an original title in another script would find nothing.
            byTitle.originalTitle?.takeIf { NewznabProtocol.hasLatinLetter(it) }
                ?.let { byTitle.copy(title = it, originalTitle = byTitle.title) }
        )
        for (attempt in attempts) {
            if (NewznabProtocol.searchUrl(indexer, attempt, caps) == null) continue
            val found = try { client.search(indexer, attempt, caps) }
            catch (e: CancellationException) { throw e }
            catch (e: IndexerCooldownException) { throw e }
            // The first search succeeded; a failed later attempt only means nothing more was found.
            catch (_: Exception) { continue }
            if (found.isNotEmpty()) return found
        }
        return null
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
        val tvdb = if (!request.series) null else try {
            id?.toIntOrNull()?.let { tmdbApi.getTvExternalIds(it, BuildConfig.TMDB_API_KEY).body()?.tvdbId?.toString() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val title = details?.title ?: details?.name
        val original = (details?.originalTitle ?: details?.originalName)
            ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        return request.copy(imdbId = imdb, tmdbId = id, tvdbId = tvdb, title = title, originalTitle = original,
            year = (details?.releaseDate ?: details?.firstAirDate)?.take(4)?.toIntOrNull())
    }

    private fun UsenetRelease.toStream(config: UsenetSourceConfiguration, failed: Boolean = false,
        played: Boolean = false, pack: Boolean = false, seasonEpisodes: Map<Int, Int>? = null, sibling: SiblingOutcome? = null): Stream {
        fun gb(bytes: Long) = "%.2f GB".format(Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
        // A pack's size is its seasons', not the episode's that plays: with their episode counts, the
        // size badge shows an episode's share and the details keep the whole size.
        val episodeShare = episodeSize(seasonEpisodes).takeIf { pack && it != size }
        val whole = if (packSeasons.size == 1) "whole season" else "whole pack"
        val sizeLabel = if (size <= 0) null else when {
            !pack -> gb(size)
            episodeShare != null -> "${gb(size)} $whole (~${gb(episodeShare)} per episode)"
            else -> "${gb(size)} ($whole)"
        }
        val age = if (publishedAt > 0) "${((System.currentTimeMillis() - publishedAt) / 86400000).coerceAtLeast(0)}d" else null
        val details = listOfNotNull(sizeLabel, age, indexerName).joinToString(" • ")
        val resolutionLabel = when {
            resolution >= 2160 -> "4K"
            resolution > 0 -> "${resolution}p"
            else -> null
        }
        return Stream(name = listOfNotNull(resolutionLabel, "Usenet", indexerName).joinToString(" • "), title = title,
            description = if (config.showReleaseNames) "$title\n$details" else details,
            url = null, ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            // The size badge, as other sources show it, and the upload's binge group.
            behaviorHints = usenetHints(bingeGroup(title), (episodeShare ?: size).takeIf { it > 0 }),
            addonName = GROUP_NAME, addonLogo = null,
            badges = listOfNotNull(
                UNAVAILABLE_BADGE.takeIf { failed },
                AVAILABLE_BADGE.takeIf { played && !failed },
                sibling?.takeIf { !failed && !played }?.let(::siblingBadge),
                SEASON_PACK_BADGE.takeIf { pack },
                blurayLabel?.let {
                    StreamBadge(name = it, tagColor = BLURAY_COLOR, textColor = "#FFFFFF", tagStyle = StreamBadge.STYLE_LABEL)
                }),
            nzbUrl = nzbUrl, servers = config.servers(),
            publishedAt = publishedAt.takeIf { it > 0 },
            quality = resolution.takeIf { it > 0 }?.let { "${it}p" }, qualityValue = resolution)
    }

    companion object {
        const val GROUP_NAME = "Built-in Usenet"
        private val WAIT_OVER = Any()
        private val SEASON_COUNTED = Any()
        /** How long a search may use for a season's episode count; the list never waits for it. */
        private const val SEASON_LOOKUP_TIMEOUT_MS = 3_000L
        /** How long a search waits for every indexer before it shows what came. */
        const val COMPLETE_RESULTS_WAIT_MS = 5_000L
        private const val BLURAY_COLOR = "#0B5ED7"
        /**
         * One upload's episodes share a binge group, so the next episode plays from the same upload
         * when binge groups are preferred: its title without the episode's number and name. A
         * season pack is its own group. Movies have none.
         */
        fun bingeGroup(title: String?): String? {
            val name = title?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val upload = NewznabProtocol.episodeTemplate(name)?.first
                ?: name.takeIf { NewznabProtocol.packSeasons(it).isNotEmpty() } ?: return null
            return "$GROUP_NAME|$upload"
        }

        private fun usenetHints(bingeGroup: String?, size: Long?): StreamBehaviorHints? =
            if (bingeGroup == null && size == null) null
            else StreamBehaviorHints(null, bingeGroup, null, null, videoSize = size)

        /** On a release whose articles were missing or damaged when it was last tried. */
        const val UNAVAILABLE_LABEL = "UNAVAILABLE"
        private val UNAVAILABLE_BADGE = StreamBadge(name = UNAVAILABLE_LABEL, tagColor = "#6C757D",
            textColor = "#FFFFFF", tagStyle = StreamBadge.STYLE_LABEL)

        /**
         * On a release whose articles were there recently with these providers: it showed its first
         * frame, perhaps for another episode of the same pack.
         */
        const val AVAILABLE_LABEL = "AVAILABLE"
        private val AVAILABLE_BADGE = StreamBadge(name = AVAILABLE_LABEL, tagColor = "#198754",
            textColor = "#FFFFFF", tagStyle = StreamBadge.STYLE_LABEL)

        /**
         * On an untried release whose upload's other episode failed ("E02 FAILED") or played
         * ("E02 PLAYED"): episodes posted together are usually removed, or kept, together.
         */
        private const val SIBLING_FAILED_COLOR = "#FD7E14"
        private const val SIBLING_PLAYED_COLOR = "#0AA2C0"
        private val SIBLING_LABEL = Regex("E\\d+ (FAILED|PLAYED)")

        fun siblingBadge(outcome: SiblingOutcome): StreamBadge =
            StreamBadge(name = "E%02d %s".format(Locale.ROOT, outcome.episode, if (outcome.failed) "FAILED" else "PLAYED"),
                tagColor = if (outcome.failed) SIBLING_FAILED_COLOR else SIBLING_PLAYED_COLOR,
                textColor = "#FFFFFF", tagStyle = StreamBadge.STYLE_LABEL)

        /** On a release holding a whole season or several, whose size is not the episode's. */
        const val SEASON_PACK_LABEL = "SEASON PACK"
        private val SEASON_PACK_BADGE = StreamBadge(name = SEASON_PACK_LABEL, tagColor = "#6F42C1",
            textColor = "#FFFFFF", tagStyle = StreamBadge.STYLE_LABEL)

        /**
         * A release played before, to open again without a search: reusing the last link keeps
         * the release, never the engine's session link, which ends with its session. The providers
         * are today's, so no credential is kept with it, and its binge group its upload's. Null when
         * no provider is enabled.
         */
        fun restoredStream(nzbUrl: String, name: String, title: String?, size: Long?, publishedAt: Long?,
            config: UsenetSourceConfiguration): Stream? {
            val servers = config.servers().ifEmpty { return null }
            return Stream(name = name, title = title, description = null, url = null, ytId = null, infoHash = null,
                fileIdx = null, externalUrl = null,
                behaviorHints = usenetHints(bingeGroup(title), size),
                addonName = GROUP_NAME, addonLogo = null, nzbUrl = nzbUrl, servers = servers, publishedAt = publishedAt)
        }

        /** Available releases first when chosen, unplayable ones last; the sort applies within each. */
        private fun outcomeRank(failed: Boolean, available: Boolean, availableFirst: Boolean) = when {
            failed -> 2
            available && availableFirst -> 0
            else -> 1
        }

        /**
         * Labels results already listed, cached or shown, by how their releases last went, and orders
         * the built-in group as a fresh search would: unplayable releases last, available ones first
         * when [availableFirst]. An untried release says how its upload's other episode went; one
         * whose other episode was blank goes last too. Other groups are unchanged.
         */
        fun arrangeOutcomes(groups: List<AddonStreams>, unplayable: (Stream) -> Boolean,
            available: (Stream) -> Boolean = { false }, availableFirst: Boolean = false,
            sibling: (Stream) -> SiblingOutcome? = { null }): List<AddonStreams> =
            groups.map { group ->
                if (group.addonName != GROUP_NAME) return@map group
                val marked = group.streams.map { stream ->
                    val failed = unplayable(stream)
                    val good = !failed && available(stream)
                    val other = if (failed || good) null else sibling(stream)
                    val others = stream.badges.filterNot {
                        it.name == UNAVAILABLE_LABEL || it.name == AVAILABLE_LABEL || SIBLING_LABEL.matches(it.name)
                    }
                    val badges = when {
                        failed -> listOf(UNAVAILABLE_BADGE) + others
                        good -> listOf(AVAILABLE_BADGE) + others
                        other != null -> listOf(siblingBadge(other)) + others
                        else -> others
                    }
                    (if (badges == stream.badges) stream else stream.copy(badges = badges)) to
                        outcomeRank(failed || other?.blank == true, good, availableFirst)
                }
                val streams = marked.sortedBy { it.second }.map { it.first }
                if (streams == group.streams) group else group.copy(streams = streams)
            }
    }
}

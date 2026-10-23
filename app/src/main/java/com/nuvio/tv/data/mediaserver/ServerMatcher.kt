package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.MetaRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class MatchRequest(
    val kind: ServerMediaKind,
    val parentId: String,
    val ids: TrackingExternalIds,
    val season: Int? = null,
    val episode: Int? = null,
    val name: String? = null,
    val year: Int? = null
)

class LibraryIndex(entries: List<ServerIndexEntry>) {
    private val idsByItem = entries.associate { it.itemId to it.ids }
    private val itemsByKey: Map<String, Set<String>> = entries
        .flatMap { entry -> entry.ids.keys().map { it to entry.itemId } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.toSet() }

    fun lookup(ids: TrackingExternalIds): List<String> =
        ids.keys()
            .flatMap { itemsByKey[it].orEmpty() }
            .distinct()
            .filterNot { itemId -> idsByItem[itemId]?.conflictsWith(ids) == true }
}

/** Items among [entries] whose provider ids are [query]'s; the year only guards matches that do not rest on the IMDb id. */
internal fun verifiedTitleMatches(entries: List<ServerIndexEntry>, query: ServerTitleQuery): List<String> {
    val byItem = entries.associateBy { it.itemId }
    return LibraryIndex(entries).lookup(query.ids).filter { itemId ->
        val entry = byItem.getValue(itemId)
        (entry.ids.imdb != null && query.ids.imdb != null) || yearsClose(query.year, entry.year)
    }
}

@Singleton
class ServerMatcher internal constructor(
    private val repository: ServerRepository,
    private val tmdbService: TmdbService,
    private val metaRepository: MetaRepository,
    private val originalTitle: (tmdbId: Long, kind: ServerMediaKind) -> String? = { _, _ -> null }
) {
    @Inject
    constructor(
        repository: ServerRepository,
        tmdbService: TmdbService,
        metaRepository: MetaRepository,
        tmdbMetadata: TmdbMetadataService
    ) : this(
        repository = repository,
        tmdbService = tmdbService,
        metaRepository = metaRepository,
        originalTitle = { tmdbId, kind ->
            tmdbMetadata.cachedOriginalTitle(tmdbId.toString(), ContentType.fromString(kind.contentType))
        }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val indexes = mutableMapOf<String, IndexBuild>()
    private val lookups = object : LinkedHashMap<LookupKey, TitleLookup>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LookupKey, TitleLookup>): Boolean =
            size > LOOKUP_CACHE_SIZE
    }
    private val convertedImdb = mutableMapOf<Long, String>()

    fun request(type: String, videoId: String, season: Int?, episode: Int?): MatchRequest? {
        val title = titleRequest(type, videoId) ?: return null
        if (title.kind == ServerMediaKind.MOVIE) return title
        val position = videoId.split(':').drop(parentSize(videoId)).map { it.toIntOrNull() }.takeIf { it.size == 2 }
        val requestedSeason = season ?: position?.get(0) ?: return null
        val requestedEpisode = episode ?: position?.get(1) ?: return null
        return title.copy(season = requestedSeason, episode = requestedEpisode)
    }

    private fun titleRequest(type: String, contentId: String): MatchRequest? {
        if (ServerItemRef.isServerId(contentId)) return null
        val kind = ServerMediaKind.fromContentType(type) ?: return null
        val parentId = contentId.split(':').take(parentSize(contentId)).joinToString(":")
        val meta = metaRepository.getCachedMeta(type, parentId)
        val metaImdb = meta?.imdbId?.takeIf { it.startsWith("tt") }
        val ids = parseTrackingExternalIds(parentId).mergeMissing(TrackingExternalIds(imdb = metaImdb))
        if (ids.catalogIds().isEmpty()) return null
        return MatchRequest(kind, parentId, ids, name = meta?.name?.takeIf { it.isNotBlank() }, year = meta?.releaseYear())
    }

    private fun parentSize(id: String): Int = if (id.startsWith("tt")) 1 else 2

    fun supports(connection: ServerConnection, kind: ServerMediaKind): Boolean =
        connection.selectedLibraries(kind).isNotEmpty() &&
            repository.provider(connection)?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true

    suspend fun match(connection: ServerConnection, request: MatchRequest, forceRefresh: Boolean): List<ServerItemRef> {
        val itemIds = titleItemIds(connection, request, forceRefresh)
        if (request.kind == ServerMediaKind.MOVIE) return itemIds.map { ServerItemRef(connection.id, it) }
        val season = request.season ?: return emptyList()
        val episode = request.episode ?: return emptyList()
        val catalogDate = metaRepository.getCachedMeta(request.kind.contentType, request.parentId)
            ?.videos
            ?.firstOrNull { it.season == season && it.episode == episode }
            ?.released
        return itemIds.mapNotNull { seriesId ->
            val match = repository.call(connection.id) { provider, session ->
                provider.findEpisode(session, seriesId, season, episode)
            } ?: return@mapNotNull null
            match.takeIf { datesCompatible(catalogDate, it.premiereDate) }?.let { ServerItemRef(connection.id, it.itemId) }
        }
    }

    private suspend fun titleItemIds(connection: ServerConnection, request: MatchRequest, forceRefresh: Boolean): List<String> {
        val ids = withConvertedIds(request)
        val libraries = connection.selectedLibraries(request.kind)
        val original = ids.tmdb?.let { originalTitle(it, request.kind) }?.takeIf { it.isNotBlank() }
        val query = ServerTitleQuery(ids, request.name, request.year, original)
        lookupItemIds(connection, request.kind, query, libraries, forceRefresh)?.let { return it }
        return libraries
            .flatMap { library -> index(connection, library, forceRefresh).lookup(ids) }
            .distinct()
    }

    private suspend fun lookupItemIds(
        connection: ServerConnection,
        kind: ServerMediaKind,
        query: ServerTitleQuery,
        libraries: List<ServerLibrary>,
        forceRefresh: Boolean
    ): List<String>? {
        val lookup = lookup(connection, kind, query, libraries, forceRefresh)
        var answered = false
        val itemIds = withTimeoutOrNull(LOOKUP_WAIT_MS) { lookup.result.await().also { answered = true } }
        if (!answered) throw ServerException(ServerFailure.INCOMPLETE)
        return itemIds
    }

    private fun lookup(
        connection: ServerConnection,
        kind: ServerMediaKind,
        query: ServerTitleQuery,
        libraries: List<ServerLibrary>,
        forceRefresh: Boolean
    ): TitleLookup {
        val key = LookupKey(connection.id, kind, query)
        val revision = repository.uiState.value.revision
        val now = System.currentTimeMillis()
        val lookup = synchronized(lock) {
            lookups[key]?.takeIf { existing ->
                !forceRefresh && !existing.failed && existing.revision == revision && now - existing.startedAtMs < LOOKUP_TTL_MS
            }?.let { return it }
            TitleLookup(revision, now).also { lookups[key] = it }
        }
        scope.launch {
            try {
                lookup.result.complete(findTitle(connection, query, libraries))
            } catch (error: Throwable) {
                lookup.failed = true
                lookup.result.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        return lookup
    }

    private suspend fun findTitle(
        connection: ServerConnection,
        query: ServerTitleQuery,
        libraries: List<ServerLibrary>
    ): List<String>? = coroutineScope {
        val found = libraries.map { library ->
            async { repository.call(connection.id) { provider, session -> provider.lookupTitle(session, library, query) } }
        }.awaitAll()
        if (found.any { it == null }) return@coroutineScope null
        found.flatMap { verifiedTitleMatches(it.orEmpty(), query) }.distinct()
    }

    private suspend fun index(connection: ServerConnection, library: ServerLibrary, forceRefresh: Boolean): LibraryIndex {
        val build = build(connection, library, forceRefresh)
        return withTimeoutOrNull(INDEX_WAIT_MS) { build.result.await() } ?: run {
            Log.w(TAG, "${connection.providerId} library ${library.name} was not indexed within ${INDEX_WAIT_MS}ms")
            throw ServerException(ServerFailure.INCOMPLETE)
        }
    }

    private fun build(connection: ServerConnection, library: ServerLibrary, forceRefresh: Boolean): IndexBuild {
        val key = "${connection.id}:${library.id}"
        val revision = repository.uiState.value.revision
        val now = System.currentTimeMillis()
        val build = synchronized(lock) {
            indexes[key]?.takeIf { existing ->
                !forceRefresh && !existing.failed && existing.revision == revision && now - existing.startedAtMs < INDEX_TTL_MS
            }?.let { return it }
            indexes.values.removeAll { it.revision != revision }
            IndexBuild(revision, now).also { indexes[key] = it }
        }
        scope.launch {
            try {
                val entries = fetchEntries(connection, library)
                Log.i(TAG, "Indexed ${entries.size} items in ${connection.providerId} library ${library.name} in ${System.currentTimeMillis() - now}ms")
                build.result.complete(LibraryIndex(entries))
            } catch (error: Throwable) {
                build.failed = true
                build.result.completeExceptionally(error)
                if (error is CancellationException) throw error
                Log.w(TAG, "Indexing ${connection.providerId} library ${library.name} failed", error)
            }
        }
        return build
    }

    private suspend fun fetchEntries(connection: ServerConnection, library: ServerLibrary): List<ServerIndexEntry> {
        val entries = mutableListOf<ServerIndexEntry>()
        while (true) {
            val page = repository.call(connection.id) { provider, session ->
                provider.externalIdIndex(session, library, entries.size, INDEX_PAGE_SIZE)
            }
            entries += page.items
            val total = page.totalCount
            if (page.items.size < INDEX_PAGE_SIZE || (total != null && entries.size >= total)) return entries
        }
    }

    private suspend fun withConvertedIds(request: MatchRequest): TrackingExternalIds {
        val ids = withConvertedImdb(request)
        if (ids.tmdb != null) return ids
        val imdb = ids.imdb ?: return ids
        val tmdb = withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
            runCatching { tmdbService.ensureTmdbId(imdb, request.kind.contentType) }.getOrNull()
        }?.toLongOrNull() ?: return ids
        return ids.copy(tmdb = tmdb)
    }

    private suspend fun withConvertedImdb(request: MatchRequest): TrackingExternalIds {
        val ids = request.ids
        val tmdb = ids.tmdb
        if (ids.imdb != null || tmdb == null) return ids
        synchronized(lock) { convertedImdb[tmdb] }?.let { return ids.copy(imdb = it) }
        val imdb = withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
            runCatching { tmdbService.tmdbToImdb(tmdb.toInt(), request.kind.contentType) }.getOrNull()
        }?.takeIf { it.startsWith("tt") } ?: return ids
        synchronized(lock) { convertedImdb[tmdb] = imdb }
        return ids.copy(imdb = imdb)
    }

    private class IndexBuild(val revision: Int, val startedAtMs: Long) {
        val result = CompletableDeferred<LibraryIndex>()

        @Volatile
        var failed = false
    }

    private data class LookupKey(
        val connectionId: String,
        val kind: ServerMediaKind,
        val query: ServerTitleQuery
    )

    private class TitleLookup(val revision: Int, val startedAtMs: Long) {
        val result = CompletableDeferred<List<String>?>()

        @Volatile
        var failed = false
    }

    private companion object {
        const val TAG = "ServerMatcher"
        const val INDEX_PAGE_SIZE = 500
        const val INDEX_TTL_MS = 10 * 60_000L
        const val INDEX_WAIT_MS = 12_000L
        const val LOOKUP_TTL_MS = 10 * 60_000L
        const val LOOKUP_WAIT_MS = 6_000L
        const val LOOKUP_CACHE_SIZE = 300
        const val CONVERSION_TIMEOUT_MS = 5_000L
    }
}

internal fun datesCompatible(catalogDate: String?, serverDate: String?): Boolean {
    val catalogDay = catalogDate?.epochDay() ?: return true
    val serverDay = serverDate?.epochDay() ?: return true
    return abs(catalogDay - serverDay) <= MAX_AIR_DATE_DRIFT_DAYS
}

private fun String.epochDay(): Long? = runCatching { LocalDate.parse(take(10)).toEpochDay() }.getOrNull()

private fun Meta.releaseYear(): Int? =
    listOfNotNull(releaseInfo, released).firstNotNullOfOrNull { YEAR.find(it)?.value?.toIntOrNull() }

private fun yearsClose(requested: Int?, server: Int?): Boolean =
    requested == null || server == null || abs(requested - server) <= MAX_YEAR_DRIFT

fun TrackingExternalIds.catalogIds(): List<String> = listOfNotNull(
    imdb,
    tmdb?.let { "tmdb:$it" },
    tvdb?.let { "tvdb:$it" },
    kitsu?.let { "kitsu:$it" },
    mal?.let { "mal:$it" },
    anilist?.let { "anilist:$it" },
    anidb?.let { "anidb:$it" },
    trakt?.let { "trakt:$it" },
    simkl?.let { "simkl:$it" }
)

private fun TrackingExternalIds.keys(): List<String> = catalogIds().map { it.lowercase() }

private fun TrackingExternalIds.conflictsWith(other: TrackingExternalIds): Boolean {
    val mine = catalogIds().associateBy { it.lowercase().substringBefore(':', "imdb") }
    return other.catalogIds().any { id ->
        val existing = mine[id.lowercase().substringBefore(':', "imdb")]
        existing != null && !existing.equals(id, ignoreCase = true)
    }
}

private val YEAR = Regex("""\b\d{4}\b""")

private const val MAX_AIR_DATE_DRIFT_DAYS = 2L
private const val MAX_YEAR_DRIFT = 1

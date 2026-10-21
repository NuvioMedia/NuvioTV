package com.nuvio.tv.core.tmdb

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_ALIAS_LOOKUP_CONCURRENCY = 4

/** The numeric TMDB id of a "tmdb:<id>" content id, or null for any other id. */
internal fun tmdbNumericId(contentId: String): Int? =
    contentId.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()

/**
 * Resolves an IMDb alias for every "tmdb:" id in [items] (content id to api type).
 *
 * Items from TMDB collection sources (LIST, DISCOVER, ...) are identified as "tmdb:<id>",
 * while watch history is keyed by the IMDb id a title was played under, so without an
 * alias those items never match a watched entry.
 *
 * Other ids are skipped, so this costs nothing for addon catalogs. Lookups go through
 * [TmdbService.tmdbToImdb], which caches results and de-duplicates in-flight requests.
 */
internal suspend fun TmdbService.resolveImdbAliases(
    items: Collection<Pair<String, String>>
): Map<String, String> = coroutineScope {
    val lookups = items
        .distinctBy { it.first }
        .mapNotNull { (contentId, apiType) ->
            tmdbNumericId(contentId)?.let { Triple(contentId, it, apiType) }
        }
    if (lookups.isEmpty()) return@coroutineScope emptyMap()
    val permits = Semaphore(MAX_ALIAS_LOOKUP_CONCURRENCY)
    lookups
        .map { (contentId, tmdbId, apiType) ->
            async {
                permits.withPermit { tmdbToImdb(tmdbId, apiType) }
                    ?.let { imdbId -> contentId to imdbId }
            }
        }
        .awaitAll()
        .filterNotNull()
        .toMap()
}

/**
 * True when [contentId] or its [imdbAlias] is in [watchedIds] — the same rule
 * isPostPlayCandidateWatched applies with MetaPreview.imdbId.
 */
internal fun isWatchedWithAlias(
    contentId: String,
    imdbAlias: String?,
    watchedIds: Set<String>
): Boolean = contentId in watchedIds || imdbAlias?.let(watchedIds::contains) == true

package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.TraktApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktEpisodeMappingService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val addonEpisodeListService: AddonEpisodeListService
) {
    companion object {
        private const val TAG = "TraktEpMapSvc"
    }

    internal suspend fun isTraktAuthenticated(): Boolean =
        traktAuthService.getCurrentAuthState().isAuthenticated

    private val cacheMutex = Mutex()
    private val mappingCache = mutableMapOf<String, EpisodeMappingEntry>()
    private val traktEpisodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    private val reverseMappingCache = mutableMapOf<String, EpisodeMappingEntry>()
    private val traktEpisodesInFlight = mutableMapOf<String, CompletableDeferred<List<EpisodeMappingEntry>>>()

    internal suspend fun prefetchEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): EpisodeMappingEntry? {
        return resolveEpisodeMapping(contentId, contentType, videoId, season, episode)
    }

    /**
     * Pre-fetches addon episode data for [contentIds] so that subsequent calls to
     * [resolveAddonEpisodeMapping] hit the cache. Limits concurrency to [concurrency].
     */
    internal suspend fun prefetchAddonEpisodes(
        contentIds: List<String>,
        concurrency: Int = 8
    ) = addonEpisodeListService.prefetch(contentIds, concurrency)

    internal suspend fun resolveAddonEpisodeMapping(
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String? = null
    ): EpisodeMappingEntry? {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return null

        val requestedSeason = season ?: return null
        val requestedEpisode = episode ?: return null
        val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: return null
        val resolvedContentType = contentType?.takeIf { it.isNotBlank() } ?: return null
        val reverseKey = reverseCacheKey(
            contentId = resolvedContentId,
            contentType = resolvedContentType,
            season = requestedSeason,
            episode = requestedEpisode,
            title = episodeTitle
        )
        cacheMutex.withLock {
            reverseMappingCache[reverseKey]?.let { return it }
        }

        val addonEpisodes = getAddonEpisodes(resolvedContentId, resolvedContentType)
        if (addonEpisodes.isEmpty()) return null

        // Like the mobile app, only remap when the addon lacks this exact episode. Remapping
        // episodes the addon already has mis-maps long shows whose season counts differ
        // somewhere (e.g. The Daily Show), since the fallback is a whole-show index.
        val addonHasEpisode = addonEpisodes.any {
            it.season == requestedSeason && it.episode == requestedEpisode
        }
        if (addonHasEpisode) return null

        val showLookupId = resolveShowLookupId(contentId = resolvedContentId, videoId = null) ?: return null
        val traktEpisodes = getTraktEpisodes(showLookupId)
        if (traktEpisodes.isEmpty()) return null

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = requestedSeason,
            requestedEpisode = requestedEpisode,
            requestedTitle = episodeTitle,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        ) ?: return null

        cacheMutex.withLock {
            reverseMappingCache[reverseKey] = mapped
        }
        return mapped
    }

    internal suspend fun getCachedEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): EpisodeMappingEntry? {
        val key = cacheKey(contentId, contentType, videoId, season, episode) ?: return null
        return cacheMutex.withLock { mappingCache[key] }
    }

    internal suspend fun resolveEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String? = null
    ): EpisodeMappingEntry? {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return null

        val key = cacheKey(contentId, contentType, videoId, season, episode) ?: return null
        cacheMutex.withLock {
            mappingCache[key]?.let { return it }
        }

        val requestedSeason = season ?: return null
        val requestedEpisode = episode ?: return null
        val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: return null
        val resolvedContentType = contentType?.takeIf { it.isNotBlank() } ?: return null

        val addonEpisodes = getAddonEpisodes(resolvedContentId, resolvedContentType)
        if (addonEpisodes.isEmpty()) return null

        val showLookupId = resolveShowLookupId(contentId = resolvedContentId, videoId = videoId) ?: return null
        val traktEpisodes = getTraktEpisodes(showLookupId)
        if (traktEpisodes.isEmpty()) return null

        if (hasSameSeasonStructure(addonEpisodes, traktEpisodes)) {
            return null
        }

        val mapped = remapEpisodeByTitleOrIndex(
            requestedSeason = requestedSeason,
            requestedEpisode = requestedEpisode,
            requestedVideoId = videoId,
            requestedTitle = episodeTitle,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        ) ?: return null

        cacheMutex.withLock {
            mappingCache[key] = mapped
        }
        return mapped
    }

    private suspend fun getAddonEpisodes(
        contentId: String,
        contentType: String
    ): List<EpisodeMappingEntry> = addonEpisodeListService.episodes(contentId, contentType)

    private suspend fun getTraktEpisodes(showLookupId: String): List<EpisodeMappingEntry> {
        cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
        }

        val existingDeferred = cacheMutex.withLock { traktEpisodesInFlight[showLookupId] }
        if (existingDeferred != null) {
            return try { existingDeferred.await() } catch (_: Exception) { emptyList() }
        }

        val deferred = CompletableDeferred<List<EpisodeMappingEntry>>()
        val weOwn = cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
            if (traktEpisodesInFlight.containsKey(showLookupId)) {
                false
            } else {
                traktEpisodesInFlight[showLookupId] = deferred
                true
            }
        }
        if (!weOwn) {
            val other = cacheMutex.withLock { traktEpisodesInFlight[showLookupId] }
            return try { other?.await() ?: emptyList() } catch (_: Exception) { emptyList() }
        }

        val seasonsResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getShowSeasons(
                authorization = authHeader,
                id = showLookupId,
                extended = "episodes"
            )
        } ?: run {
            cleanupTraktFlight(showLookupId)
            return emptyList()
        }
        if (!seasonsResponse.isSuccessful) {
            Log.w(
                TAG,
                "getTraktEpisodes: seasons request failed code=${seasonsResponse.code()} id=$showLookupId"
            )
            cleanupTraktFlight(showLookupId)
            return emptyList()
        }

        val traktEpisodes = seasonsResponse.body()
            .orEmpty()
            .asSequence()
            .filter { (it.number ?: 0) > 0 }
            .sortedBy { it.number }
            .flatMap { seasonDto ->
                seasonDto.episodes.orEmpty().asSequence()
                    .mapNotNull { episodeDto ->
                        val seasonNumber = episodeDto.season ?: seasonDto.number ?: return@mapNotNull null
                        val episodeNumber = episodeDto.number ?: return@mapNotNull null
                        EpisodeMappingEntry(
                            season = seasonNumber,
                            episode = episodeNumber,
                            title = episodeDto.title
                        )
                    }
            }
            .toList()

        if (traktEpisodes.isNotEmpty()) {
            cacheMutex.withLock {
                traktEpisodesCache[showLookupId] = traktEpisodes
            }
        }
        deferred.complete(traktEpisodes)
        cleanupTraktFlight(showLookupId)
        return traktEpisodes
    }

    private suspend fun cleanupTraktFlight(showLookupId: String) {
        cacheMutex.withLock { traktEpisodesInFlight.remove(showLookupId) }
    }

    private fun cacheKey(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): String? {
        val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedContentType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedSeason = season ?: return null
        val resolvedEpisode = episode ?: return null
        val resolvedVideoId = videoId?.trim().orEmpty()
        return "$resolvedContentType|$resolvedContentId|$resolvedVideoId|$resolvedSeason|$resolvedEpisode"
    }

    private fun reverseCacheKey(
        contentId: String,
        contentType: String,
        season: Int,
        episode: Int,
        title: String?
    ): String {
        val normalizedTitle = title?.trim()?.lowercase().orEmpty()
        return "reverse|${contentType.trim().lowercase()}|${contentId.trim()}|$season|$episode|$normalizedTitle"
    }

    private fun resolveShowLookupId(contentId: String?, videoId: String?): String? {
        val contentIds = toTraktIds(parseContentIds(contentId))
        if (contentIds.hasAnyId()) {
            return when {
                !contentIds.imdb.isNullOrBlank() -> contentIds.imdb
                contentIds.trakt != null -> contentIds.trakt.toString()
                !contentIds.slug.isNullOrBlank() -> contentIds.slug
                else -> null
            }
        }

        val videoIds = toTraktIds(parseContentIds(videoId))
        return when {
            !videoIds.imdb.isNullOrBlank() -> videoIds.imdb
            videoIds.trakt != null -> videoIds.trakt.toString()
            !videoIds.slug.isNullOrBlank() -> videoIds.slug
            else -> null
        }
    }
}

package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktEpisodeMappingService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository
) {
    companion object {
        private const val TAG = "TraktEpMapSvc"
    }

    private val cacheMutex = Mutex()
    private val mappingCache = mutableMapOf<String, EpisodeMappingEntry>()
    private val addonEpisodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    private val traktEpisodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    private val reverseMappingCache = mutableMapOf<String, EpisodeMappingEntry>()

    internal suspend fun prefetchEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): EpisodeMappingEntry? {
        return resolveEpisodeMapping(contentId, contentType, videoId, season, episode)
    }

    internal suspend fun resolveAddonEpisodeMapping(
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String? = null
    ): EpisodeMappingEntry? {
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
        episode: Int?
    ): EpisodeMappingEntry? {
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

        val mapped = remapEpisodeByTitleOrIndex(
            requestedSeason = requestedSeason,
            requestedEpisode = requestedEpisode,
            requestedVideoId = videoId,
            requestedTitle = null,
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
    ): List<EpisodeMappingEntry> {
        val cacheKey = addonEpisodesCacheKey(contentId, contentType)
        cacheMutex.withLock {
            addonEpisodesCache[cacheKey]?.let { return it }
        }

        val meta = fetchSeriesMeta(contentId, contentType) ?: return emptyList()
        val addonEpisodes = meta.videos.toEpisodeMappingEntries()
        if (addonEpisodes.isEmpty()) return emptyList()

        cacheMutex.withLock {
            addonEpisodesCache[cacheKey] = addonEpisodes
        }
        return addonEpisodes
    }

    private suspend fun getTraktEpisodes(showLookupId: String): List<EpisodeMappingEntry> {
        cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
        }

        val seasonsResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getShowSeasons(
                authorization = authHeader,
                id = showLookupId,
                extended = "episodes"
            )
        } ?: return emptyList()
        if (!seasonsResponse.isSuccessful) {
            Log.w(
                TAG,
                "getTraktEpisodes: seasons request failed code=${seasonsResponse.code()} id=$showLookupId"
            )
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
        return traktEpisodes
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

    private fun addonEpisodesCacheKey(contentId: String, contentType: String): String {
        return "${contentType.trim().lowercase()}|${contentId.trim()}"
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

    private suspend fun fetchSeriesMeta(contentId: String, contentType: String): Meta? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            }
        }.distinct()
        if (typeCandidates.isEmpty()) return null

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue
                val meta = (result as? NetworkResult.Success)?.data ?: continue
                if (meta.videos.any { it.season != null && it.episode != null }) {
                    return meta
                }
            }
        }
        return null
    }

    private fun List<Video>.toEpisodeMappingEntries(): List<EpisodeMappingEntry> {
        return asSequence()
            .mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                if (season <= 0) return@mapNotNull null
                EpisodeMappingEntry(
                    season = season,
                    episode = episode,
                    title = video.title,
                    videoId = video.id.takeIf { it.isNotBlank() }
                )
            }
            .distinctBy { it.videoId ?: "${it.season}:${it.episode}" }
            .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))
            .toList()
    }
}

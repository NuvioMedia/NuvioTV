package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Episode lists as the installed addons number them, for remapping episodes between the addon's
 * numbering and a tracking provider's own numbering.
 */
@Singleton
class AddonEpisodeListService @Inject constructor(
    private val metaRepository: MetaRepository
) {
    private val cacheMutex = Mutex()
    private val episodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    // In-flight dedup: prevents multiple concurrent coroutines from fetching
    // the same show's addon episodes simultaneously.
    private val episodesInFlight = mutableMapOf<String, CompletableDeferred<List<EpisodeMappingEntry>>>()

    /**
     * Pre-fetches addon episode data for [contentIds] so that later lookups hit the cache.
     * Limits concurrency to [concurrency].
     */
    internal suspend fun prefetch(
        contentIds: List<String>,
        concurrency: Int = 8
    ) {
        val unique = contentIds.distinct()
        if (unique.isEmpty()) return
        val semaphore = Semaphore(concurrency)
        supervisorScope {
            unique.forEach { contentId ->
                launch {
                    try {
                        semaphore.withPermit {
                            episodes(contentId, "series")
                        }
                    } catch (_: Exception) {
                        // Individual prefetch failures are non-fatal. The mapping phase
                        // will retry or proceed without cached data for this show.
                    }
                }
            }
        }
    }

    internal suspend fun episodes(
        contentId: String,
        contentType: String
    ): List<EpisodeMappingEntry> {
        val cacheKey = cacheKey(contentId, contentType)

        // Fast path: cache hit
        cacheMutex.withLock {
            episodesCache[cacheKey]?.let { return it }
        }

        // Dedup: if another coroutine is already fetching this show, await its result.
        val existingDeferred = cacheMutex.withLock { episodesInFlight[cacheKey] }
        if (existingDeferred != null) {
            return try { existingDeferred.await() } catch (_: Exception) { emptyList() }
        }

        // Register ourselves as the in-flight fetcher.
        val deferred = CompletableDeferred<List<EpisodeMappingEntry>>()
        val weOwn = cacheMutex.withLock {
            // Double-check: cache or another flight may have appeared while we waited.
            episodesCache[cacheKey]?.let { return it }
            if (episodesInFlight.containsKey(cacheKey)) {
                false
            } else {
                episodesInFlight[cacheKey] = deferred
                true
            }
        }
        if (!weOwn) {
            val other = cacheMutex.withLock { episodesInFlight[cacheKey] }
            return try { other?.await() ?: emptyList() } catch (_: Exception) { emptyList() }
        }

        return try {
            val meta = fetchSeriesMeta(contentId, contentType)
            val episodes = meta?.videos?.toEpisodeMappingEntries() ?: emptyList()
            if (episodes.isNotEmpty()) {
                cacheMutex.withLock { episodesCache[cacheKey] = episodes }
            }
            deferred.complete(episodes)
            episodes
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            emptyList()
        } finally {
            cacheMutex.withLock { episodesInFlight.remove(cacheKey) }
        }
    }

    private fun cacheKey(contentId: String, contentType: String): String {
        return "${contentType.trim().lowercase()}|${contentId.trim()}"
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
                val result = try {
                    withTimeoutOrNull(8000) {
                        metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                            .dropWhile { it is NetworkResult.Loading }
                            .firstOrNull()
                    }
                } catch (_: Exception) {
                    null
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

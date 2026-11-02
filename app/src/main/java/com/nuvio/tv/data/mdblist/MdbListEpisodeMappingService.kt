package com.nuvio.tv.data.mdblist

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.repository.AddonEpisodeListService
import com.nuvio.tv.data.repository.EpisodeMappingEntry
import com.nuvio.tv.data.repository.hasSameSeasonStructure
import com.nuvio.tv.data.repository.remapEpisodeByTitleOrIndex
import com.nuvio.tv.data.repository.reverseRemapEpisodeByTitleOrIndex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Remaps episodes between the addon's numbering and MDBList's, which follows TMDB.
 *
 * Anime is often split into seasons differently on TMDB and in the metadata an addon serves, so
 * TMDB S1E35 can be S2E11 for the addon. When the two sides have different seasons, episodes are
 * matched by title and otherwise by their position in the whole episode list, the same way Trakt
 * numbering is remapped.
 */
@Singleton
class MdbListEpisodeMappingService @Inject constructor(
    private val addonEpisodes: AddonEpisodeListService,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    private val resolved = ConcurrentHashMap<String, MdbListEpisodeMapping>()
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<MdbListEpisodeMapping?>>()

    /** The mapping for [contentId] when it was already resolved, without touching the network. */
    internal fun cached(contentId: String): MdbListEpisodeMapping? = resolved[contentId.trim()]

    /**
     * Resolves the mapping for the show the addon serves as [contentId]. [tmdbShowId] skips the
     * TMDB id lookup when MDBList already reported it. Returns null when either side's episodes
     * could not be loaded, so the caller keeps the numbering it has.
     */
    internal suspend fun resolve(contentId: String, tmdbShowId: Long? = null): MdbListEpisodeMapping? {
        val key = contentId.trim().takeIf(String::isNotBlank) ?: return null
        resolved[key]?.let { return it }
        val (deferred, owner) = inFlightMutex.withLock {
            resolved[key]?.let { return it }
            inFlight[key]?.let { it to false }
                ?: CompletableDeferred<MdbListEpisodeMapping?>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        return try {
            load(key, tmdbShowId).also { mapping ->
                if (mapping != null) resolved[key] = mapping
                deferred.complete(mapping)
            }
        } catch (error: CancellationException) {
            deferred.complete(null)
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "episode mapping failed for $key", error)
            deferred.complete(null)
            null
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    /**
     * [reference] with its episode moved from the addon's numbering to TMDB's, which MDBList reads
     * season and episode numbers in. References that need no remapping come back unchanged.
     */
    internal suspend fun toMdbListNumbering(
        reference: TrackingMediaReference,
        mediaIndex: MdbListMediaIndex?
    ): TrackingMediaReference {
        if (reference.kind == TrackingMediaKind.MOVIE) return reference
        val episode = reference.episode ?: return reference
        val season = episode.season ?: return reference
        val catalog = reference.catalog ?: return reference
        val tmdbShowId = reference.ids.tmdb ?: reference.ids.toMdbListIds()?.let { ids ->
            mediaIndex?.resolve(MdbListItemType.SHOW, ids)?.ids?.tmdb
        }
        val tmdb = resolve(catalog.contentId, tmdbShowId)
            ?.toTmdb(season, episode.number, catalog.videoId, episode.title) ?: return reference
        if (tmdb.season == season && tmdb.episode == episode.number) return reference
        return reference.copy(episode = episode.copy(season = tmdb.season, number = tmdb.episode))
    }

    private suspend fun load(contentId: String, tmdbShowId: Long?): MdbListEpisodeMapping? {
        val addon = addonEpisodes.episodes(contentId, "series")
        if (addon.isEmpty()) return null
        val tmdbId = tmdbShowId?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: lookupTmdbShowId(contentId) ?: return null
        val seasons = tmdbApi.getTvDetails(tmdbId, BuildConfig.TMDB_API_KEY, TMDB_LANGUAGE).body()
            ?.seasons.orEmpty()
            .mapNotNull { season ->
                val number = season.seasonNumber?.takeIf { it > 0 } ?: return@mapNotNull null
                val count = season.episodeCount?.takeIf { it > 0 } ?: return@mapNotNull null
                number to count
            }
            .toMap()
        if (seasons.isEmpty()) return null
        // Same seasons with a few episodes more or fewer, as when one side merges two-parters, keep
        // their numbers: matching those by position would shift every later season.
        if (seasons.keys == addon.seasonCounts().keys) return MdbListEpisodeMapping.Unchanged

        val tmdbEpisodes = coroutineScope {
            val semaphore = Semaphore(TMDB_SEASON_REQUEST_CONCURRENCY)
            seasons.keys.sorted().map { season ->
                async {
                    semaphore.withPermit {
                        tmdbApi.getTvSeasonDetails(tmdbId, season, BuildConfig.TMDB_API_KEY, TMDB_LANGUAGE).body()
                            ?.episodes.orEmpty()
                            .mapNotNull { episode ->
                                val number = episode.episodeNumber?.takeIf { it > 0 } ?: return@mapNotNull null
                                EpisodeMappingEntry(season = season, episode = number, title = episode.name)
                            }
                    }
                }
            }.awaitAll().flatten()
        }
        if (tmdbEpisodes.isEmpty()) return null
        if (hasSameSeasonStructure(addon, tmdbEpisodes)) return MdbListEpisodeMapping.Unchanged
        Log.d(TAG, "remapping $contentId: addon=${addon.seasonCounts()} tmdb=${tmdbEpisodes.seasonCounts()}")
        return MdbListEpisodeMapping(addon, tmdbEpisodes)
    }

    private suspend fun lookupTmdbShowId(contentId: String): Int? {
        val ids = parseTrackingExternalIds(contentId)
        ids.tmdb?.takeIf { it in 1..Int.MAX_VALUE }?.let { return it.toInt() }
        ids.imdb?.let { return tmdbService.imdbToTmdb(it, "series") }
        ids.tvdb?.let { tvdb ->
            return tmdbApi.findByExternalId(tvdb, BuildConfig.TMDB_API_KEY, "tvdb_id").body()
                ?.tvResults?.firstOrNull()?.id
        }
        return null
    }

    private fun List<EpisodeMappingEntry>.seasonCounts(): Map<Int, Int> =
        groupBy { it.season }.mapValues { it.value.size }

    private companion object {
        const val TAG = "MdbListEpMap"
        const val TMDB_LANGUAGE = "en-US"
        const val TMDB_SEASON_REQUEST_CONCURRENCY = 4
    }
}

/** Episode numbering of one show, translated between the addon and MDBList (TMDB). */
internal class MdbListEpisodeMapping(
    private val addonEpisodes: List<EpisodeMappingEntry>,
    private val tmdbEpisodes: List<EpisodeMappingEntry>
) {
    /** False when both sides split the show the same way and episodes keep their numbers. */
    val remaps: Boolean
        get() = addonEpisodes.isNotEmpty()

    private val toAddonCache = ConcurrentHashMap<Pair<Int, Int>, EpisodeMappingEntry>()
    private val toTmdbCache = ConcurrentHashMap<String, EpisodeMappingEntry>()

    /** The addon's episode for TMDB [season]/[episode], or null when nothing needs remapping. */
    fun toAddon(season: Int, episode: Int, title: String? = null): EpisodeMappingEntry? {
        if (!remaps) return null
        toAddonCache[season to episode]?.let { return it }
        return reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = season,
            requestedEpisode = episode,
            requestedTitle = title,
            addonEpisodes = addonEpisodes,
            traktEpisodes = tmdbEpisodes
        )?.also { toAddonCache[season to episode] = it }
    }

    /** TMDB's episode for the addon's [season]/[episode], or null when nothing needs remapping. */
    fun toTmdb(season: Int, episode: Int, videoId: String? = null, title: String? = null): EpisodeMappingEntry? {
        if (!remaps) return null
        val key = "$season:$episode:${videoId.orEmpty()}"
        toTmdbCache[key]?.let { return it }
        return remapEpisodeByTitleOrIndex(
            requestedSeason = season,
            requestedEpisode = episode,
            requestedVideoId = videoId,
            requestedTitle = title,
            addonEpisodes = addonEpisodes,
            traktEpisodes = tmdbEpisodes
        )?.also { toTmdbCache[key] = it }
    }

    companion object {
        val Unchanged = MdbListEpisodeMapping(emptyList(), emptyList())
    }
}

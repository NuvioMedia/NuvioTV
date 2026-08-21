package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Repository for managing watch progress data.
 */
interface WatchProgressRepository {
    
    /**
     * Get all watch progress items sorted by last watched (most recent first)
     */
    val allProgress: Flow<List<WatchProgress>>
    
    /**
     * Get items currently in progress (not completed, suitable for "Continue Watching")
     */
    val continueWatching: Flow<List<WatchProgress>>

    val watchedItems: Flow<List<WatchedItem>>
        get() = flowOf(emptyList())
    
    /**
     * Get watch progress for a specific content item (movie or series)
     */
    fun getProgress(contentId: String): Flow<WatchProgress?>
    
    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?>
    
    /**
     * Get all episode progress for a series as a map of (season, episode) to progress
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>

    /**
     * Get the aired episode order for a series when available from the current progress backend.
     */
    fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>>

    /**
     * Get completed series episode seeds suitable for building a lightweight "Next Up".
     */
    fun observeNextUpSeeds(): Flow<List<WatchProgress>>

    /**
     * Emits true when the remote progress source has completed its initial load.
     */
    fun observeRemoteProgressLoaded(): Flow<Boolean>

    /**
     * Emits immediate optimistic updates that should patch Continue Watching
     * without waiting for the regular progress flows to settle.
     */
    fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress>

    suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress


    /**
     * Returns whether the item is marked as watched/completed.
     * For series episodes pass both [season] and [episode].
     */
    fun isWatched(contentId: String, videoId: String? = null, season: Int? = null, episode: Int? = null): Flow<Boolean>
    
    fun observeWatchedMovieIds(): Flow<Set<String>>

    /**
     * Apply an optimistic addition or removal to the watched movie IDs set.
     * Used for immediate badge feedback before the backend confirms the change.
     * Pass [add] = true to mark as watched, false to unmark.
     * The [ids] should include all ID variants for the content (e.g. "tmdb:123", "tt1234567").
     */
    fun applyOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {}

    /**
     * Revert a previous optimistic update (e.g. on failure).
     */
    fun revertOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {}

    /**
     * Returns per-show watched episodes from the active source.
     * Empty map when no data is available.
     */
    suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>

    /**
     * Returns sibling ID mapping: each content ID maps to its alternate IDs
     * from the same show (e.g. IMDB ↔ TMDB). Empty map for non-Trakt sources.
     */
    suspend fun getShowIdSiblings(): Map<String, Set<String>>

    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? = null

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean = true)

    /**
     * Save or update multiple watch progress entries in a single batch.
     */
    suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean = true)
    
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null)

    suspend fun removeFromHistory(contentId: String, videoId: String? = null, season: Int? = null, episode: Int? = null)

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress, broadcastTrackingHistory: Boolean = true)

    /**
     * Mark multiple episodes as completed in a single batch operation.
     * More efficient than calling [markAsCompleted] in a loop.
     */
    suspend fun markAsCompletedBatch(progressList: List<WatchProgress>)

    /**
     * Remove multiple episodes from history in a single batch operation.
     */
    suspend fun removeFromHistoryBatch(
        contentId: String,
        videoId: String?,
        episodes: List<Triple<Int, Int, String?>>
    )
    
    /**
     * Clear all watch progress
     */
    suspend fun clearAll()

    /**
     * Returns true if the show is dropped/hidden from progress on the active source.
     */
    fun isDroppedShow(contentId: String): Boolean

    fun hasActiveTrackingProgressProvider(): Boolean
    fun activeProviderOwnsCompletedHistoryProjection(): Boolean
    fun activeProviderContinueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long?
    fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean
    suspend fun normalizeParentContentId(parentContentId: String, videoId: String?): String
}

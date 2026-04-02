package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow

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
     * Emits immediate optimistic updates that should patch Continue Watching
     * without waiting for the regular progress flows to settle.
     */
    fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress>


    /**
     * Returns whether the item is marked as watched/completed.
     * For series episodes pass both [season] and [episode].
     */
    fun isWatched(contentId: String, videoId: String? = null, season: Int? = null, episode: Int? = null): Flow<Boolean>
    
    fun observeWatchedMovieIds(): Flow<Set<String>>

    /**
     * Returns per-show watched episodes from the active source.
     * Empty map when no data is available.
     */
    suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean = true)

    /**
     * Save or update multiple watch progress entries in a single batch.
     */
    suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean = true)
    
    /**
     * Remove watch progress (playback only, does not affect Trakt history)
     */
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null)

    /**
     * Remove from watch history (marks as unwatched on Trakt)
     */
    suspend fun removeFromHistory(contentId: String, videoId: String? = null, season: Int? = null, episode: Int? = null)

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress)
    
    /**
     * Clear all watch progress
     */
    suspend fun clearAll()
}

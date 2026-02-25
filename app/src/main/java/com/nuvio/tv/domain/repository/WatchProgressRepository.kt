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
     * Returns whether the item is marked as watched/completed.
     * For series episodes pass both [season] and [episode].
     */
    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean>
    
    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean = true)
    
    /**
     * Remove watch progress (playback only, does not affect Trakt history)
     */
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null)

    /**
     * Remove from watch history (marks as unwatched on Trakt)
     */
    suspend fun removeFromHistory(contentId: String, season: Int? = null, episode: Int? = null)

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress)
    
    /**
     * Clear all watch progress
     */
    suspend fun clearAll()
}

package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
    /** Suspends local plugin work while playback owns the device, then resumes the same search. */
    fun setLocalPluginSearchPaused(paused: Boolean)

    /**
     * Fetches streams from all installed addons for a given video ID
     * @param type The content type (movie, series, etc.)
     * @param videoId The video ID (for movies: IMDB ID, for series: IMDB_ID:season:episode)
     * @param season Optional season number for TV shows (used by local plugins)
     * @param episode Optional episode number for TV shows (used by local plugins)
     * @return Flow of AddonStreams grouped by addon
     */
    fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false
    ): Flow<NetworkResult<List<AddonStreams>>>

    /**
     * Fetches streams from a specific addon.
     *
     * Takes the installed [Addon] rather than a bare base URL so the display name and logo
     * stamped onto the returned streams come from the manifest already held in memory.
     *
     * @param addon The installed addon to query
     * @param type The content type
     * @param videoId The video ID
     * @return NetworkResult containing list of streams
     */
    suspend fun getStreamsFromAddon(
        addon: Addon,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>>
}

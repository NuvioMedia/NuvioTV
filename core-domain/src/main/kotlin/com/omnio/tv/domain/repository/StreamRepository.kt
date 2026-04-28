package com.omnio.tv.domain.repository

import com.omnio.tv.domain.result.NetworkResult
import com.omnio.tv.domain.model.AddonStreams
import com.omnio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
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
        episode: Int? = null
    ): Flow<NetworkResult<List<AddonStreams>>>

    /**
     * Fetches streams from a specific addon
     * @param baseUrl The addon base URL
     * @param type The content type
     * @param videoId The video ID
     * @return NetworkResult containing list of streams
     */
    suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>>
}

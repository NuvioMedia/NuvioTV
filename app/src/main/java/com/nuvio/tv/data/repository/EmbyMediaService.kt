package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.EmbyAuthDataStore
import com.nuvio.tv.data.remote.api.EmbyApi
import com.nuvio.tv.data.remote.dto.emby.EmbyItemDto
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmbyMediaService"

/**
 * Side-channel metadata for Emby streams.
 * Maps stream URL -> EmbyItemId so the player can report Now Playing without modifying Stream.kt.
 */
data class EmbyStreamMetadata(
    val embyItemId: String,
    val mediaSourceId: String,
    val runTimeTicks: Long?
)

@Singleton
class EmbyMediaService @Inject constructor(
    private val embyApi: EmbyApi,
    private val embyAuthDataStore: EmbyAuthDataStore
) {
    /**
     * Side-channel: maps streamUrl -> EmbyStreamMetadata.
     * Written when Emby streams are resolved, read by the player to get embyItemId.
     */
    private val streamMetadataMap = ConcurrentHashMap<String, EmbyStreamMetadata>()

    fun getMetadataForStream(streamUrl: String): EmbyStreamMetadata? = streamMetadataMap[streamUrl]

    fun metadataMapSize(): Int = streamMetadataMap.size

    fun findMetadataByItemUrlPattern(streamUrl: String): EmbyStreamMetadata? {
        if (streamMetadataMap.isEmpty()) return null
        val videosPattern = Regex("""/Videos/([^/]+)/stream""")
        val match = videosPattern.find(streamUrl) ?: return null
        val itemId = match.groupValues[1]
        return streamMetadataMap.values.firstOrNull { it.embyItemId == itemId }
    }

    fun clearMetadata() {
        streamMetadataMap.clear()
    }

    /**
     * Find media on Emby server matching the given contentId.
     * Returns the direct-play stream URL prepended to the stream list, or null if not found/not connected.
     *
     * @param contentId Stremio-format content ID (e.g., "tt1234567", "tmdb:12345")
     * @param contentType "movie" or "series"/"tv"
     * @param season Season number (for series)
     * @param episode Episode number (for series)
     * @return Pair of (streamUrl, displayName) or null if not found
     */
    suspend fun findEmbyStream(
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?
    ): Pair<String, String>? {
        if (contentId.isNullOrBlank()) return null

        val authState = embyAuthDataStore.state.first()
        if (!authState.isConnected) return null

        val serverUrl = authState.serverUrl!!.trimEnd('/')
        val apiKey = authState.apiKey!!
        val userId = authState.userId!!

        try {
            // Parse contentId to get IMDb/TMDB IDs
            val parsedIds = parseContentIds(contentId)
            val providerIdFilters = buildProviderIdFilter(parsedIds)
            if (providerIdFilters.isBlank()) {
                Log.d(TAG, "No provider IDs to search for: $contentId")
                return null
            }

            val normalizedType = contentType?.lowercase()
            val isEpisode = normalizedType in listOf("series", "tv") && season != null && episode != null
            val includeItemTypes = if (isEpisode) "Series" else "Movie"

            // Search for the item on Emby
            val response = embyApi.getUserItems(
                userId = userId,
                includeItemTypes = includeItemTypes,
                providerIds = providerIdFilters
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "Emby search failed: ${response.code()} ${response.message()}")
                return null
            }

            val items = response.body()?.items ?: emptyList()
            val matchedItem = items.firstOrNull()
            if (matchedItem == null) {
                Log.d(TAG, "No Emby item found for $contentId ($includeItemTypes)")
                return null
            }

            // For episodes, resolve the specific episode
            val targetItem: EmbyItemDto = if (isEpisode && season != null && episode != null) {
                val episodeResponse = embyApi.getEpisodes(
                    seriesId = matchedItem.id,
                    season = season,
                    episode = episode
                )
                val ep = episodeResponse.body()?.items?.firstOrNull()
                if (ep == null) {
                    Log.d(TAG, "Episode S${season}E${episode} not found in Emby for series ${matchedItem.id}")
                    return null
                }
                ep
            } else {
                matchedItem
            }

            // Build direct-play URL
            val streamUrl = "$serverUrl/Videos/${targetItem.id}/stream?api_key=$apiKey&static=true"
            val mediaSourceId = targetItem.mediaSources?.firstOrNull()?.id ?: targetItem.id

            // Store in side-channel
            val metadata = EmbyStreamMetadata(
                embyItemId = targetItem.id,
                mediaSourceId = mediaSourceId,
                runTimeTicks = targetItem.runTimeTicks
            )
            streamMetadataMap[streamUrl] = metadata
            Log.d(TAG, "Emby stream resolved: ${targetItem.id} -> $streamUrl")

            val displayName = buildDisplayName(matchedItem, targetItem, isEpisode, season, episode)
            return Pair(streamUrl, displayName)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Emby stream: ${e.message}", e)
            return null
        }
    }

    private fun buildProviderIdFilter(ids: ParsedContentIds): String {
        val filters = mutableListOf<String>()
        ids.imdb?.let { filters.add("imdb.$it") }
        ids.tmdb?.let { filters.add("tmdb.$it") }
        return filters.joinToString(",")
    }

    private fun buildDisplayName(
        series: EmbyItemDto,
        target: EmbyItemDto,
        isEpisode: Boolean,
        season: Int?,
        episode: Int?
    ): String {
        return if (isEpisode) {
            val showName = series.name ?: "Unknown Show"
            val epName = target.name
            "Emby: $showName S${season}E${episode}${if (epName != null) " - $epName" else ""}"
        } else {
            "Emby: ${target.name ?: "Unknown"}"
        }
    }
}

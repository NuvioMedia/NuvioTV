package com.omnio.tv.data.repository

import android.util.Log
import com.omnio.tv.data.local.EmbyCredentialsDataStore
import com.omnio.tv.data.remote.api.EmbyApi
import com.omnio.tv.data.remote.dto.emby.EmbyItemDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmbyMediaService"
private const val EMBY_PROVIDER_NAME = "emby"

data class EmbyResolvedStream(
    val streamUrl: String,
    val streamHeaders: Map<String, String>,
    val displayName: String,
    val itemId: String,
    val mediaSourceId: String,
    val runTimeTicks: Long?
)

@Singleton
class EmbyMediaService @Inject constructor(
    private val embyApi: EmbyApi,
    private val embyCredentialsDataStore: EmbyCredentialsDataStore
) {
    fun isConfigured(): Boolean = embyCredentialsDataStore.cachedCredentials.isConfigured

    suspend fun findEmbyStream(
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?
    ): EmbyResolvedStream? {
        if (contentId.isNullOrBlank()) return null

        val credentials = embyCredentialsDataStore.credentials.first()
        if (!credentials.isConfigured) return null

        val providerFilters = buildProviderIdFilter(parseContentIds(contentId))
        if (providerFilters.isBlank()) {
            Log.d(TAG, "No provider IDs to search for: $contentId")
            return null
        }

        return try {
            val normalizedType = contentType?.lowercase()
            val isEpisode = normalizedType in setOf("series", "tv") && season != null && episode != null
            val includeItemTypes = if (isEpisode) "Series" else "Movie"

            val response = embyApi.getUserItems(
                userId = credentials.userId,
                includeItemTypes = includeItemTypes,
                providerIds = providerFilters
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "Emby search failed: ${response.code()} ${response.message()}")
                return null
            }

            val matchedItem = response.body()?.items?.firstOrNull()
            if (matchedItem == null) {
                Log.d(TAG, "No Emby item found for $contentId ($includeItemTypes)")
                return null
            }

            val targetItem = if (isEpisode) {
                val requestedSeason = requireNotNull(season)
                val requestedEpisode = requireNotNull(episode)
                val episodeResponse = embyApi.getEpisodes(
                    seriesId = matchedItem.id,
                    season = requestedSeason
                )

                if (!episodeResponse.isSuccessful) {
                    Log.w(
                        TAG,
                        "Emby episode lookup failed for series ${matchedItem.id} S${requestedSeason}E${requestedEpisode}: ${episodeResponse.code()} ${episodeResponse.message()}"
                    )
                    return null
                }

                val episodes = episodeResponse.body()?.items ?: emptyList()
                val seasonEpisodes = episodes.filter { candidate ->
                    val matchesType = candidate.type?.equals("Episode", ignoreCase = true) != false
                    val matchesSeason = candidate.parentIndexNumber?.let { it == requestedSeason } != false
                    matchesType && matchesSeason
                }

                val episodeItem = seasonEpisodes.firstOrNull { it.indexNumber == requestedEpisode }
                if (episodeItem == null) {
                    Log.d(
                        TAG,
                        "Episode S${requestedSeason}E${requestedEpisode} not found in Emby for series ${matchedItem.id}"
                    )
                    return null
                }

                episodeItem
            } else {
                matchedItem
            }

            val streamUrl = "${credentials.serverUrl}/Videos/${targetItem.id}/stream?static=true"
            val mediaSourceId = targetItem.mediaSources?.firstOrNull()?.id ?: targetItem.id
            val headers = mapOf(
                "X-Emby-Token" to credentials.apiKey
            )

            EmbyResolvedStream(
                streamUrl = streamUrl,
                streamHeaders = headers,
                displayName = buildDisplayName(matchedItem, targetItem, isEpisode, season, episode),
                itemId = targetItem.id,
                mediaSourceId = mediaSourceId,
                runTimeTicks = targetItem.runTimeTicks
            )
        } catch (error: Exception) {
            Log.e(TAG, "Error finding Emby stream: ${error.message}", error)
            null
        }
    }

    fun clearMetadata() {
        // Metadata now travels with Stream provider fields. Keep method for disconnect callers.
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
            val episodeName = target.name
            "Emby: $showName S${season}E${episode}${if (episodeName != null) " - $episodeName" else ""}"
        } else {
            "Emby: ${target.name ?: "Unknown"}"
        }
    }

    fun providerName(): String = EMBY_PROVIDER_NAME
}

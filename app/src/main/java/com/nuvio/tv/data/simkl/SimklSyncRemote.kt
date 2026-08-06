package com.nuvio.tv.data.simkl

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

sealed interface SimklAllItemsRequest {
    val type: SimklMediaType?

    data class Bootstrap(override val type: SimklMediaType) : SimklAllItemsRequest

    data class Changes(val dateFrom: String) : SimklAllItemsRequest {
        override val type: SimklMediaType? = null
    }

    data object CurrentIds : SimklAllItemsRequest {
        override val type: SimklMediaType? = null
    }
}

interface SimklSyncRemote {
    suspend fun fetchActivities(): SimklActivities
    suspend fun fetchAllItems(request: SimklAllItemsRequest): SimklAllItemsResponse
    suspend fun fetchPlayback(): List<SimklPlaybackSession>
}

class SimklApiSyncRemote @Inject constructor(
    private val client: SimklApiClient
) : SimklSyncRemote {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun fetchActivities(): SimklActivities = client.execute(
        SimklApiRequest(SimklHttpMethod.GET, "/sync/activities")
    ).body.decode()

    override suspend fun fetchAllItems(request: SimklAllItemsRequest): SimklAllItemsResponse {
        val path = request.type?.let { type -> "/sync/all-items/${type.apiValue}" } ?: "/sync/all-items"
        val query = when (request) {
            is SimklAllItemsRequest.Bootstrap -> mapOf(
                "extended" to "full_anime_seasons",
                "episode_watched_at" to "yes",
                "episode_tvdb_id" to "yes",
                "include_all_episodes" to "yes",
                "language" to "en"
            )
            is SimklAllItemsRequest.Changes -> mapOf(
                "date_from" to request.dateFrom,
                "extended" to "full_anime_seasons",
                "episode_watched_at" to "yes",
                "episode_tvdb_id" to "yes",
                "include_all_episodes" to "yes",
                "language" to "en"
            )
            SimklAllItemsRequest.CurrentIds -> mapOf("extended" to "simkl_ids_only")
        }
        return client.execute(
            SimklApiRequest(SimklHttpMethod.GET, path, query)
        ).body.decodeAllItems()
    }

    override suspend fun fetchPlayback(): List<SimklPlaybackSession> = client.execute(
        SimklApiRequest(SimklHttpMethod.GET, "/sync/playback")
    ).body.decode()

    private inline fun <reified T> String.decode(): T = json.decodeFromString(this)

    private fun String.decodeAllItems(): SimklAllItemsResponse {
        val element = json.parseToJsonElement(this)
        return when {
            element is JsonNull -> SimklAllItemsResponse()
            element is JsonArray && element.isEmpty() -> SimklAllItemsResponse()
            else -> json.decodeFromJsonElement(element)
        }
    }
}

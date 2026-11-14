package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.streams.supportsResource
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class AddonPlayerAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop")
}

enum class AddonLibraryAction(val wireValue: String) {
    LIBRARY_ADD("libraryAdd"),
    LIBRARY_REMOVE("libraryRemove"),
    WATCHED("watched"),
    UNWATCHED("unwatched")
}

data class AddonPlayerEventTarget(
    val type: String,
    val videoId: String
)

const val ADDON_PLAYER_RESOURCE = "player"
const val ADDON_LIBRARY_RESOURCE = "library"

val Addon.isTrackingAddon: Boolean
    get() = resources.any { it.name == ADDON_PLAYER_RESOURCE || it.name == ADDON_LIBRARY_RESOURCE }

@Singleton
class AddonEventDispatcher @Inject constructor(
    private val api: AddonApi,
    private val addonRepository: AddonRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<AddonEvent>(Channel.UNLIMITED)
    private val addonQueues = mutableMapOf<String, Channel<AddonDelivery>>()

    init {
        scope.launch {
            for (event in events) {
                route(event)
            }
        }
    }

    suspend fun hasEnabledAddonFor(resource: String): Boolean =
        runCatching { addonRepository.getInstalledAddons().first() }
            .getOrDefault(emptyList())
            .any { addon -> addon.enabled && addon.resources.any { it.name == resource } }

    fun player(
        target: AddonPlayerEventTarget,
        action: AddonPlayerAction,
        currentTimeMs: Long,
        durationMs: Long
    ) {
        dispatch(
            resource = ADDON_PLAYER_RESOURCE,
            type = target.type,
            id = target.videoId,
            extra = listOf(
                "action" to action.wireValue,
                "currentTime" to currentTimeMs.coerceAtLeast(0L).toString(),
                "duration" to durationMs.coerceAtLeast(0L).toString()
            )
        )
    }

    fun library(type: String, id: String, action: AddonLibraryAction) {
        dispatch(
            resource = ADDON_LIBRARY_RESOURCE,
            type = type,
            id = id,
            extra = listOf("action" to action.wireValue)
        )
    }

    fun libraryVideos(
        type: String,
        id: String,
        action: AddonLibraryAction,
        videoIds: Collection<String>
    ) {
        libraryVideoIdChunks(videoIds).forEach { chunk ->
            dispatch(
                resource = ADDON_LIBRARY_RESOURCE,
                type = type,
                id = id,
                extra = listOf(
                    "action" to action.wireValue,
                    "videoId" to chunk.joinToString(",")
                )
            )
        }
    }

    private fun dispatch(
        resource: String,
        type: String,
        id: String,
        extra: List<Pair<String, String>>
    ) {
        if (type.isBlank() || id.isBlank() || ServerItemRef.isServerId(id)) return
        events.trySend(AddonEvent(resource, type, id, extra))
    }

    private suspend fun route(event: AddonEvent) {
        val addons = runCatching { addonRepository.getInstalledAddons().first() }
            .getOrDefault(emptyList())
            .filter { addon -> addon.enabled && addon.supportsResource(event.resource, event.type, event.id) }
            .distinctBy { it.baseUrl }
        addons.forEach { addon ->
            val url = buildAddonEventUrl(addon.baseUrl, event.resource, event.type, event.id, event.extra)
            queueFor(addon.baseUrl).trySend(AddonDelivery(addon.id, event.resource, url))
        }
    }

    private fun queueFor(baseUrl: String): Channel<AddonDelivery> =
        addonQueues.getOrPut(baseUrl) {
            Channel<AddonDelivery>(Channel.UNLIMITED).also { queue ->
                scope.launch {
                    for (delivery in queue) {
                        send(delivery)
                    }
                }
            }
        }

    private suspend fun send(delivery: AddonDelivery) {
        runCatching {
            withTimeout(EVENT_TIMEOUT_MS) { api.sendEvent(delivery.url).body()?.close() }
        }.onFailure { error ->
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            Log.w(TAG, "${delivery.addonId} ${delivery.resource} event failed", error)
        }
    }

    private companion object {
        const val TAG = "AddonEvents"
        const val EVENT_TIMEOUT_MS = 10_000L
    }
}

private const val LIBRARY_EVENT_VIDEOS_COUNT = 100

internal fun libraryVideoIdChunks(videoIds: Collection<String>): List<List<String>> =
    videoIds
        .filter { it.isNotBlank() }
        .distinct()
        .chunked(LIBRARY_EVENT_VIDEOS_COUNT)

internal fun buildAddonEventUrl(
    baseUrl: String,
    resource: String,
    type: String,
    id: String,
    extra: List<Pair<String, String>>
): String {
    val rawBaseUrl = baseUrl.trimEnd('/')
    val queryStart = rawBaseUrl.indexOf('?')
    val basePath = if (queryStart >= 0) rawBaseUrl.substring(0, queryStart).trimEnd('/') else rawBaseUrl
    val baseQuery = if (queryStart >= 0) rawBaseUrl.substring(queryStart) else ""
    val extraSegment = extra.joinToString("&") { (name, value) ->
        "${encodeAddonEventSegment(name)}=${encodeAddonEventSegment(value)}"
    }
    return "$basePath/$resource/${encodeAddonEventSegment(type)}/${encodeAddonEventSegment(id)}/$extraSegment.json$baseQuery"
}

private fun encodeAddonEventSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private data class AddonEvent(
    val resource: String,
    val type: String,
    val id: String,
    val extra: List<Pair<String, String>>
)

private data class AddonDelivery(
    val addonId: String,
    val resource: String,
    val url: String
)

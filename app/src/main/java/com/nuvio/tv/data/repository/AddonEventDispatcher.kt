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
import kotlinx.coroutines.coroutineScope
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

    init {
        scope.launch {
            for (event in events) {
                deliver(event)
            }
        }
    }

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
        videoIds
            .filter { it.isNotBlank() }
            .distinct()
            .chunked(LIBRARY_EVENT_VIDEOS_COUNT)
            .forEach { chunk ->
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

    private suspend fun deliver(event: AddonEvent) {
        val addons = runCatching { addonRepository.getInstalledAddons().first() }
            .getOrDefault(emptyList())
            .filter { addon -> addon.enabled && addon.supportsResource(event.resource, event.type, event.id) }
            .distinctBy { it.baseUrl }
        if (addons.isEmpty()) return

        val extraSegment = event.extra.joinToString("&") { (name, value) ->
            "${encodePathSegment(name)}=${encodePathSegment(value)}"
        }
        coroutineScope {
            addons.forEach { addon ->
                val url = buildEventUrl(addon.baseUrl, event.resource, event.type, event.id, extraSegment)
                launch {
                    runCatching {
                        withTimeout(EVENT_TIMEOUT_MS) { api.sendEvent(url).body()?.close() }
                    }.onFailure { error ->
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        Log.w(TAG, "${addon.id} ${event.resource} event failed", error)
                    }
                }
            }
        }
    }

    private fun buildEventUrl(
        baseUrl: String,
        resource: String,
        type: String,
        id: String,
        extraSegment: String
    ): String {
        val rawBaseUrl = baseUrl.trimEnd('/')
        val queryStart = rawBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) rawBaseUrl.substring(0, queryStart).trimEnd('/') else rawBaseUrl
        val baseQuery = if (queryStart >= 0) rawBaseUrl.substring(queryStart) else ""
        return "$basePath/$resource/${encodePathSegment(type)}/${encodePathSegment(id)}/$extraSegment.json$baseQuery"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val TAG = "AddonEvents"
        const val LIBRARY_EVENT_VIDEOS_COUNT = 100
        const val EVENT_TIMEOUT_MS = 10_000L
    }
}

private data class AddonEvent(
    val resource: String,
    val type: String,
    val id: String,
    val extra: List<Pair<String, String>>
)

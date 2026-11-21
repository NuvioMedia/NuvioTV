package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Player route arguments for [stream], in the form [PlayerNavigationArgs.from] reads them back:
 * keys it URL-decodes a second time are stored encoded, the others raw. Mirrors the arguments a
 * normal launch passes (see `StreamScreenViewModel.getStreamForPlayback`).
 */
internal fun playerStreamRouteValues(
    stream: Stream,
    streamUrl: String,
    headers: Map<String, String>,
    videoId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?
): Map<String, String> {
    fun encoded(value: String?): String =
        value?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""

    val hints = stream.behaviorHints
    return mapOf(
        "streamUrl" to streamUrl,
        "headers" to encoded(
            headers.takeIf { it.isNotEmpty() }
                ?.let { map -> JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString() }
        ),
        "videoId" to videoId.orEmpty(),
        "season" to season?.toString().orEmpty(),
        "episode" to episode?.toString().orEmpty(),
        "episodeTitle" to encoded(episodeTitle),
        "streamName" to encoded(stream.name ?: stream.addonName),
        "bingeGroup" to hints?.bingeGroup.orEmpty(),
        "filename" to encoded(hints?.filename),
        "videoHash" to hints?.videoHash.orEmpty(),
        "videoSize" to hints?.videoSize?.toString().orEmpty(),
        "addonName" to encoded(stream.addonName),
        "addonLogo" to encoded(stream.addonLogo),
        "streamDescription" to encoded(stream.description),
        "infoHash" to stream.getEffectiveInfoHash().orEmpty(),
        "fileIdx" to stream.getEffectiveFileIdx()?.toString().orEmpty(),
        "sources" to encoded(
            stream.sources?.let { sources -> JsonArray(sources.map(::JsonPrimitive)).toString() }
        ),
        "audioDelayMs" to hints?.audioDelayMs?.toString().orEmpty(),
        "audioDelayLanguage" to encoded(hints?.audioDelayLanguage),
        // The new stream resumes from its saved progress, whatever the first one was opened with.
        "startFromBeginning" to "false"
    )
}

/**
 * Points the player route at [stream] after an in-player episode or source switch. The player
 * switches in place, so without this a player restored after process death (TV standby, a long
 * screensaver) reopens the stream it was first launched with, usually an earlier episode.
 */
internal fun PlayerRuntimeController.persistStreamRouteState(stream: Stream, isTorrent: Boolean) {
    val values = playerStreamRouteValues(
        stream = stream,
        // Same form the stream screen passes; the startup turns it back into a torrent session.
        streamUrl = if (isTorrent) "torrent://${stream.getEffectiveInfoHash()}" else currentStreamUrl,
        headers = if (isTorrent) emptyMap() else currentHeaders,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle
    )
    values.forEach { (key, value) -> savedStateHandle[key] = value }
}

package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import org.json.JSONArray
import java.net.URLDecoder

internal data class PlayerNavigationArgs(
    val streamUrl: String,
    val title: String,
    val streamName: String?,
    val year: String?,
    val headersJson: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val initialSeason: Int?,
    val initialEpisode: Int?,
    val initialEpisodeTitle: String?,
    val bingeGroup: String?,
    val filename: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val startFromBeginning: Boolean,
    val addonName: String?,
    val addonLogo: String?,
    val streamDescription: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val sourcesJson: String?,
    val contentLanguage: String?
) {
    val torrentTrackers: List<String>
        get() {
            val json = sourcesJson ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length())
                    .mapNotNull { arr.optString(it) }
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
            } catch (_: Exception) {
                emptyList()
            }
        }

    companion object {
        fun from(savedStateHandle: SavedStateHandle): PlayerNavigationArgs {
            fun decodedOrNull(key: String): String? {
                val value = savedStateHandle.get<String>(key) ?: return null
                if (value.isEmpty()) return null
                // Stream metadata occasionally contains stray `%` or malformed escapes
                // (e.g. via AIOStreams Formatter). Fall back to the raw value rather
                // than crashing the player on launch.
                return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }

            return PlayerNavigationArgs(
                streamUrl = savedStateHandle.get<String>("streamUrl") ?: "",
                title = decodedOrNull("title") ?: "",
                streamName = decodedOrNull("streamName"),
                year = decodedOrNull("year"),
                headersJson = decodedOrNull("headers"),
                // NavController already decodes these IDs.
                contentId = savedStateHandle.get<String>("contentId")?.takeIf { it.isNotEmpty() },
                contentType = savedStateHandle.get<String>("contentType")?.takeIf { it.isNotEmpty() },
                contentName = decodedOrNull("contentName"),
                poster = decodedOrNull("poster"),
                backdrop = decodedOrNull("backdrop"),
                logo = decodedOrNull("logo"),
                videoId = savedStateHandle.get<String>("videoId")?.takeIf { it.isNotEmpty() },
                initialSeason = savedStateHandle.get<String>("season")?.toIntOrNull(),
                initialEpisode = savedStateHandle.get<String>("episode")?.toIntOrNull(),
                initialEpisodeTitle = decodedOrNull("episodeTitle"),
                bingeGroup = decodedOrNull("bingeGroup"),
                filename = decodedOrNull("filename"),
                videoHash = savedStateHandle.get<String>("videoHash")?.takeIf { it.isNotEmpty() },
                videoSize = savedStateHandle.get<String>("videoSize")?.toLongOrNull(),
                startFromBeginning = savedStateHandle.get<String>("startFromBeginning")?.toBooleanStrictOrNull() == true,
                addonName = decodedOrNull("addonName"),
                addonLogo = decodedOrNull("addonLogo"),
                streamDescription = decodedOrNull("streamDescription"),
                infoHash = savedStateHandle.get<String>("infoHash")?.takeIf { it.isNotEmpty() },
                fileIdx = savedStateHandle.get<String>("fileIdx")?.toIntOrNull(),
                sourcesJson = decodedOrNull("sources"),
                contentLanguage = decodedOrNull("contentLanguage")
            )
        }
    }
}

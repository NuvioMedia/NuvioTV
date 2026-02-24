package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
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
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?
) {
    companion object {
        fun from(savedStateHandle: SavedStateHandle): PlayerNavigationArgs {
            fun decodedOrNull(key: String): String? {
                val value = savedStateHandle.get<String>(key) ?: return null
                return if (value.isNotEmpty()) URLDecoder.decode(value, "UTF-8") else null
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
                rememberedAudioLanguage = decodedOrNull("rememberedAudioLanguage"),
                rememberedAudioName = decodedOrNull("rememberedAudioName")
            )
        }
    }
}

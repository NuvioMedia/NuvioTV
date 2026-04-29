package com.omnio.phone.ui.screens.detail

import com.omnio.tv.domain.model.Meta
import com.omnio.tv.domain.model.Stream
import com.omnio.tv.domain.model.Video

data class DetailUiState(
    val isLoading: Boolean = true,
    val meta: Meta? = null,
    val error: String? = null,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val episodesForSeason: List<Video> = emptyList(),
    val isInLibrary: Boolean = false,
    val userMessage: String? = null,
    val isResolvingPlayback: Boolean = false
)

data class PlaybackRequest(
    val stream: Stream,
    val title: String,
    val contentName: String?,
    val contentId: String,
    val contentType: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val year: String?
)

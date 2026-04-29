package com.omnio.phone.ui.screens.detail

import com.omnio.tv.domain.model.AddonStreams
import com.omnio.tv.domain.model.LibraryListTab
import com.omnio.tv.domain.model.LibrarySourceMode
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
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val userMessage: String? = null,
    val isResolvingPlayback: Boolean = false,
    val streamSelection: StreamSelectionState? = null,
    val listPicker: ListPickerState? = null
)

data class StreamSelectionState(
    val targetVideo: Video? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val groups: List<AddonStreams> = emptyList(),
    val addonFilter: String? = null
)

data class ListPickerState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val tabs: List<LibraryListTab> = emptyList(),
    val membership: Map<String, Boolean> = emptyMap(),
    val originalMembership: Map<String, Boolean> = emptyMap()
) {
    val hasChanges: Boolean
        get() = tabs.any { tab ->
            (membership[tab.key] == true) != (originalMembership[tab.key] == true)
        }
}

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

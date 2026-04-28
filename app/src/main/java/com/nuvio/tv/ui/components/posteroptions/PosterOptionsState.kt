package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview

@Immutable
data class PosterOptionsState(
    val target: MetaPreview? = null,
    val addonBaseUrl: String = "",
    val isInLibrary: Boolean = false,
    val isWatched: Boolean = false,
    val isLibraryPending: Boolean = false,
    val isWatchedPending: Boolean = false,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val libraryListTabs: List<LibraryListTab> = emptyList(),
    val listPickerActive: Boolean = false,
    val listPickerTitle: String? = null,
    val listPickerMembership: Map<String, Boolean> = emptyMap(),
    val listPickerPending: Boolean = false,
    val listPickerError: String? = null
)

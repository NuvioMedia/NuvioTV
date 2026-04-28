package com.nuvio.tv.ui.components.posteroptions

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reusable controller for the long-press / hold-down poster options menu.
 *
 * Inject into a ViewModel via Hilt and call [bind] from the VM's init with
 * `viewModelScope`. The screen renders [PosterOptionsHost] with [state] and
 * wires [show] to each card's `onLongPress`.
 */
class PosterOptionsController @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val tmdbService: TmdbService
) {
    private val _state = MutableStateFlow(PosterOptionsState())
    val state: StateFlow<PosterOptionsState> = _state.asStateFlow()

    private val targetFlow = MutableStateFlow<MetaPreview?>(null)

    private var scope: CoroutineScope? = null
    private var bound = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bind(scope: CoroutineScope) {
        if (bound) return
        bound = true
        this.scope = scope

        libraryRepository.sourceMode
            .distinctUntilChanged()
            .onEach { mode ->
                _state.update { current ->
                    val resetPicker = mode != LibrarySourceMode.TRAKT
                    if (resetPicker) {
                        current.copy(
                            librarySourceMode = mode,
                            listPickerActive = false,
                            listPickerPending = false,
                            listPickerError = null,
                            listPickerTitle = null,
                            listPickerMembership = emptyMap()
                        )
                    } else {
                        current.copy(librarySourceMode = mode)
                    }
                }
            }
            .launchIn(scope)

        libraryRepository.listTabs
            .distinctUntilChanged()
            .onEach { tabs ->
                _state.update { current ->
                    current.copy(
                        libraryListTabs = tabs,
                        listPickerMembership = mergeMembershipWithTabs(
                            tabs = tabs,
                            membership = current.listPickerMembership
                        )
                    )
                }
            }
            .launchIn(scope)

        targetFlow
            .flatMapLatest { item ->
                if (item == null) {
                    flowOf(false to false)
                } else {
                    combine(
                        libraryRepository.isInLibrary(item.id, item.apiType),
                        watchProgressRepository.isWatched(item.id, videoId = item.imdbId)
                    ) { lib, watched -> lib to watched }
                }
            }
            .onEach { (isInLibrary, isWatched) ->
                _state.update { current ->
                    current.copy(
                        isInLibrary = isInLibrary,
                        isWatched = isWatched
                    )
                }
            }
            .launchIn(scope)
    }

    fun show(item: MetaPreview, addonBaseUrl: String?) {
        _state.update { current ->
            current.copy(
                target = item,
                addonBaseUrl = addonBaseUrl.orEmpty(),
                isInLibrary = false,
                isWatched = false,
                isLibraryPending = false,
                isWatchedPending = false
            )
        }
        targetFlow.value = item

        // Resolve TMDB→IMDB in the background so the dialog observes — and writes — under
        // the same canonical id the details screen uses. Without this, adding from a
        // TMDB-rooted screen (cast filmography, TMDB lists) and then again from details
        // creates duplicate library/watched entries (the storage key-matches exactly).
        scope?.launch {
            val canonical = canonicalize(item)
            if (canonical.id != item.id && _state.value.target?.id == item.id) {
                _state.update { it.copy(target = canonical) }
                targetFlow.value = canonical
            }
        }
    }

    private suspend fun canonicalize(item: MetaPreview): MetaPreview {
        if (item.id.startsWith("tt", ignoreCase = false)) return item
        val tmdbNumber = parseContentIds(item.id).tmdb ?: item.id.toIntOrNull() ?: return item
        val mediaType = if (item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true)
        ) "tv" else "movie"
        val imdb = runCatching { tmdbService.tmdbToImdb(tmdbNumber, mediaType) }.getOrNull()
        return if (!imdb.isNullOrBlank()) item.copy(id = imdb) else item
    }

    private suspend fun ensureCanonical(): MetaPreview? {
        val current = _state.value.target ?: return null
        val canonical = canonicalize(current)
        if (canonical.id != current.id && _state.value.target?.id == current.id) {
            _state.update { it.copy(target = canonical) }
            targetFlow.value = canonical
        }
        return canonical
    }

    fun dismiss() {
        targetFlow.value = null
        _state.update { it.copy(target = null) }
    }

    fun toggleLibrary() {
        val state = _state.value
        if (state.target == null) return
        if (state.isLibraryPending) return
        val scope = this.scope ?: return

        _state.update { it.copy(isLibraryPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            runCatching {
                libraryRepository.toggleDefault(
                    canonical.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() })
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle library for ${canonical.id}: ${error.message}")
            }
            _state.update { it.copy(isLibraryPending = false) }
        }
    }

    fun openListPicker() {
        val state = _state.value
        val item = state.target ?: return
        if (state.librarySourceMode != LibrarySourceMode.TRAKT) {
            toggleLibrary()
            dismiss()
            return
        }
        val scope = this.scope ?: return

        _state.update { current ->
            current.copy(
                target = null,
                listPickerActive = true,
                listPickerTitle = item.name,
                listPickerPending = true,
                listPickerError = null,
                listPickerMembership = mergeMembershipWithTabs(
                    tabs = current.libraryListTabs,
                    membership = emptyMap()
                )
            )
        }
        targetFlow.value = null

        scope.launch {
            val canonical = canonicalize(item)
            val input = canonical.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() })
            activeListPickerInput = input
            runCatching {
                libraryRepository.getMembershipSnapshot(input)
            }.onSuccess { snapshot ->
                _state.update { current ->
                    current.copy(
                        listPickerPending = false,
                        listPickerError = null,
                        listPickerMembership = mergeMembershipWithTabs(
                            tabs = current.libraryListTabs,
                            membership = snapshot.listMembership
                        )
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to load list picker for ${canonical.id}: ${error.message}")
                _state.update { current ->
                    current.copy(
                        listPickerPending = false,
                        listPickerError = error.message ?: "Failed to load lists"
                    )
                }
            }
        }
    }

    fun toggleListMembership(listKey: String) {
        _state.update { current ->
            val nextMembership = current.listPickerMembership.toMutableMap().apply {
                this[listKey] = !(this[listKey] == true)
            }
            current.copy(
                listPickerMembership = nextMembership,
                listPickerError = null
            )
        }
    }

    fun saveListPicker() {
        val state = _state.value
        if (state.listPickerPending) return
        if (state.librarySourceMode != LibrarySourceMode.TRAKT) return
        val input = activeListPickerInput ?: return
        val scope = this.scope ?: return

        _state.update { it.copy(listPickerPending = true, listPickerError = null) }
        scope.launch {
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = input,
                    changes = ListMembershipChanges(
                        desiredMembership = _state.value.listPickerMembership
                    )
                )
            }.onSuccess {
                activeListPickerInput = null
                _state.update {
                    it.copy(
                        listPickerActive = false,
                        listPickerPending = false,
                        listPickerError = null,
                        listPickerTitle = null
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to save list picker: ${error.message}")
                _state.update {
                    it.copy(
                        listPickerPending = false,
                        listPickerError = error.message ?: "Failed to update lists"
                    )
                }
            }
        }
    }

    fun dismissListPicker() {
        activeListPickerInput = null
        _state.update {
            it.copy(
                listPickerActive = false,
                listPickerPending = false,
                listPickerError = null,
                listPickerTitle = null
            )
        }
    }

    fun toggleMovieWatched() {
        val state = _state.value
        val item = state.target ?: return
        if (!item.apiType.equals("movie", ignoreCase = true)) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            val currentlyWatched = _state.value.isWatched
            runCatching {
                if (currentlyWatched) {
                    watchProgressRepository.removeFromHistory(canonical.id, videoId = canonical.imdbId)
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(canonical))
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle watched for ${canonical.id}: ${error.message}")
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    private var activeListPickerInput: LibraryEntryInput? = null

    companion object {
        private const val TAG = "PosterOptionsCtrl"
    }
}

private fun mergeMembershipWithTabs(
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>
): Map<String, Boolean> {
    return if (tabs.isEmpty()) {
        membership
    } else {
        tabs.associate { tab -> tab.key to (membership[tab.key] == true) }
    }
}

private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
    return WatchProgress(
        contentId = item.id,
        contentType = item.apiType,
        name = item.name,
        poster = item.poster,
        backdrop = item.backdropUrl,
        logo = item.logo,
        videoId = item.id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )
}

private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
    val year = Regex("(\\d{4})").find(releaseInfo ?: "")
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val parsedIds = parseContentIds(id)
    // The library renders as portrait. If the source MetaPreview was built for landscape
    // display (e.g. TMDB collection / more-like-this), its `poster` field holds the backdrop.
    // Prefer `rawPosterUrl` (the proper portrait) when available so the saved entry isn't a
    // stretched/cropped landscape inside a portrait card.
    val isLandscapeSource = posterShape == com.nuvio.tv.domain.model.PosterShape.LANDSCAPE
    val portraitPoster = rawPosterUrl?.takeIf { it.isNotBlank() }
    val savedPoster = if (isLandscapeSource && portraitPoster != null) portraitPoster else poster
    val savedShape = if (isLandscapeSource) com.nuvio.tv.domain.model.PosterShape.POSTER else posterShape
    return LibraryEntryInput(
        itemId = id,
        itemType = apiType,
        title = name,
        year = year,
        traktId = parsedIds.trakt,
        imdbId = parsedIds.imdb,
        tmdbId = parsedIds.tmdb,
        poster = savedPoster,
        posterShape = savedShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}

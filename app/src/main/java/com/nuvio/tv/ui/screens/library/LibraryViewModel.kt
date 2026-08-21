package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.cloud.CloudLibraryFile
import com.nuvio.tv.core.cloud.CloudLibraryItem
import com.nuvio.tv.core.cloud.CloudLibraryItemType
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackContext
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackResult
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.cloud.CloudLibraryUiState
import com.nuvio.tv.core.player.ExternalPlaybackMetadata
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nuvio.tv.R
import java.util.Locale
import javax.inject.Inject

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "")
    }
}

enum class LibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    DEFAULT("default", R.string.library_sort_provider_order),
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc);

    companion object {
        val TrackingOptions = listOf(DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
    }
}

enum class LibraryWatchedFilter(val key: String, val labelResId: Int) {
    ALL("all", R.string.library_watched_filter_all),
    WATCHED("watched", R.string.library_watched_filter_watched),
    UNWATCHED("unwatched", R.string.library_watched_filter_unwatched);
}

data class FilterOption(
    val key: String,
    val label: String,
    val count: Int
)

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: TraktListPrivacy = TraktListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val cloudLibrary: CloudLibraryUiState = CloudLibraryUiState(),
    val visibleCloudItems: List<CloudLibraryItem> = emptyList(),
    val availableCloudProviders: List<FilterOption> = emptyList(),
    val availableCloudTypes: List<FilterOption> = emptyList(),
    val selectedCloudProviderId: String? = null,
    val selectedCloudType: CloudLibraryItemType? = null,
    /** Free-text filter applied to the cloud library only. Never queries addons or the saved list. */
    val cloudSearchQuery: String = "",
    val resolvingCloudFileKey: String? = null,
    val cloudLibrarySettingsVersion: Long = 0L,
    val listTabs: List<LibraryListTab> = emptyList(),
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val sortSelectionVersion: Long = 0L,
    val availableGenres: List<FilterOption> = emptyList(),
    val availableYears: List<FilterOption> = emptyList(),
    val selectedGenre: String? = null,
    val selectedYear: String? = null,
    val selectedWatchedFilter: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedSeriesIds: Set<String> = emptySet(),
    val isNuvioAccount: Boolean = false,
    val isTrackingAuthenticated: Boolean = false,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val externalPlaybackTracker: ExternalPlaybackTracker,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val libraryPreferences: LibraryPreferences,
    private val authManager: AuthManager,
    private val trackingProviderRegistry: TrackingLibraryProviderRegistry,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private var messageClearJob: Job? = null
    private var cloudRefreshJob: Job? = null

    init {
        posterOptions.bind(viewModelScope)
        observeLayoutPreferences()
        observeLibraryData()
        observeCloudLibrarySettings()
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids ->
                    _watchedMovieIds.value = ids
                    _uiState.update { current ->
                        current.copy(watchedMovieIds = ids).withVisibleItems()
                    }
                }
        }
        viewModelScope.launch {
            watchedSeriesStateHolder.fullyWatchedSeriesIds
                .collect { ids ->
                    _uiState.update { current ->
                        current.copy(watchedSeriesIds = ids).withVisibleItems()
                    }
                }
        }
    }

    private val metaPrefetchedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var metaPrefetchJob: Job? = null

    /**
     * Prefetch meta from addons in background when an item receives focus.
     * Warms the MetaRepository cache so the detail screen loads instantly.
     * Debounced to avoid flooding the network during rapid scrolling.
     */
    fun prefetchMetaOnFocus(id: String, type: String) {
        if (id.isBlank() || id in metaPrefetchedIds) return
        metaPrefetchJob?.cancel()
        metaPrefetchJob = viewModelScope.launch {
            delay(150)
            if (id in metaPrefetchedIds) return@launch
            metaPrefetchedIds.add(id)
            metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
        }
    }

    fun getCachedBackdrop(id: String, type: String): String? {
        return metaRepository.getCachedMeta(type, id)?.backdropUrl
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val updated = current.copy(selectedTypeTab = tab)
            updated.withVisibleItems()
        }
        viewModelScope.launch {
            libraryPreferences.setLastSelectedType(tab.key)
        }
    }

    fun onSelectListTab(listKey: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
        viewModelScope.launch {
            libraryPreferences.setLastSelectedList(listKey)
        }
    }

    fun onSelectGenre(key: String?) {
        _uiState.update { current ->
            val updated = current.copy(selectedGenre = key)
            updated.withVisibleItems()
        }
    }

    fun onSelectYear(key: String?) {
        _uiState.update { current ->
            val updated = current.copy(selectedYear = key)
            updated.withVisibleItems()
        }
    }

    fun onSelectWatchedFilter(filter: LibraryWatchedFilter) {
        _uiState.update { current ->
            val updated = current.copy(selectedWatchedFilter = filter)
            updated.withVisibleItems()
        }
    }

    fun onSelectSortOption(option: LibrarySortOption) {
        _uiState.update { current ->
            val nextVersion = if (current.selectedSortOption != option) {
                current.sortSelectionVersion + 1L
            } else {
                current.sortSelectionVersion
            }
            val updated = current.copy(
                selectedSortOption = option,
                sortSelectionVersion = nextVersion
            )
            updated.withVisibleItems()
        }
        viewModelScope.launch { libraryPreferences.setSortOption(option.key) }
    }

    fun ensureCloudLibraryLoaded() {
        val current = _uiState.value.cloudLibrary
        if (current.isLoaded || current.isRefreshing) return
        refreshCloudLibrary()
    }

    fun refreshCloudLibrary() {
        val current = _uiState.value.cloudLibrary
        if (current.isRefreshing) return
        cloudRefreshJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    cloudLibrary = state.cloudLibrary.copy(
                        isRefreshing = true,
                        providers = state.cloudLibrary.providers.map { provider -> provider.copy(isLoading = true, errorMessage = null) }
                    )
                )
            }
            runCatching {
                cloudLibraryRepository.refresh()
            }.onSuccess { refreshed ->
                _uiState.update { state ->
                    state.copy(cloudLibrary = refreshed).withVisibleCloudItems()
                }
            }.onFailure { error ->
                if (error is CancellationException) return@launch
                setError(error.message ?: context.getString(R.string.cloud_library_play_failed))
                _uiState.update { state ->
                    state.copy(cloudLibrary = state.cloudLibrary.copy(isLoaded = true, isRefreshing = false)).withVisibleCloudItems()
                }
            }
        }
    }

    fun onSelectCloudProvider(providerId: String?) {
        _uiState.update { current ->
            current.copy(selectedCloudProviderId = providerId).withVisibleCloudItems()
        }
    }

    fun onCloudSearchQueryChange(query: String) {
        _uiState.update { current ->
            current.copy(cloudSearchQuery = query).withVisibleCloudItems()
        }
    }

    fun onSelectCloudType(type: CloudLibraryItemType?) {
        _uiState.update { current ->
            current.copy(selectedCloudType = type).withVisibleCloudItems()
        }
    }

    fun onCloudItemHasNoPlayableFiles() {
        setError(context.getString(R.string.cloud_library_no_playable_files))
    }

    fun resolveCloudPlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        onResolved: (CloudLibraryPlaybackInfo) -> Unit
    ) {
        val resolveKey = "${item.stableKey}:${file.stableKey}"
        if (_uiState.value.resolvingCloudFileKey != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(resolvingCloudFileKey = resolveKey, errorMessage = null) }
            val result = cloudLibraryRepository.resolvePlayback(item, file)
            _uiState.update { it.copy(resolvingCloudFileKey = null) }
            when (result) {
                is CloudLibraryPlaybackResult.Success -> {
                    val playbackContext = CloudLibraryPlaybackContext.create(item, file)
                    if (playbackContext == null) {
                        setError(context.getString(R.string.cloud_library_play_failed))
                        return@launch
                    }
                    val sessionToken = cloudPlaybackSessionStore.create(playbackContext)
                    onResolved(
                        CloudLibraryPlaybackInfo(
                            item = item,
                            file = file,
                            url = result.url,
                            filename = result.filename ?: file.name.takeIf { it.isNotBlank() },
                            videoSizeBytes = result.videoSizeBytes ?: file.sizeBytes,
                            sessionToken = sessionToken,
                            sequenceIndex = playbackContext.currentIndex
                        )
                    )
                }
                CloudLibraryPlaybackResult.MissingCredentials -> {
                    setError(context.getString(R.string.cloud_library_connect_message))
                }
                CloudLibraryPlaybackResult.NotPlayable -> {
                    setError(context.getString(R.string.cloud_library_no_playable_files))
                }
                is CloudLibraryPlaybackResult.Failed -> {
                    setError(result.message ?: context.getString(R.string.cloud_library_play_failed))
                }
            }
        }
    }

    suspend fun launchCloudPlaybackExternally(
        info: CloudLibraryPlaybackInfo,
        activityContext: Context
    ): Boolean {
        val filename = info.filename ?: info.file.name
        val metadata = ExternalPlaybackMetadata(
            contentId = info.item.stableKey,
            contentType = "cloud",
            contentName = info.item.name,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "${info.item.stableKey}:${info.file.stableKey}",
            season = 1,
            episode = info.sequenceIndex + 1,
            episodeTitle = filename,
            year = null
        )
        val launched = runCatching {
            externalPlaybackTracker.launchPlayer(
                metadata = metadata,
                url = info.url,
                title = filename,
                headers = null,
                cloudSessionToken = info.sessionToken,
                context = activityContext
            )
        }.getOrDefault(false)
        if (!launched) setError(context.getString(R.string.cloud_library_play_failed))
        return launched
    }

    suspend fun getPlayerPreference(): PlayerPreference =
        playerSettingsDataStore.playerSettings.first().playerPreference

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage(context.getString(R.string.library_syncing_library))
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage(context.getString(R.string.library_synced))
            }.onFailure { error ->
                setError(error.message ?: context.getString(R.string.library_error_refresh_failed))
            }
        }
    }

    fun onOpenManageLists() {
        _uiState.update { current ->
            if (current.sourceMode != LibrarySourceMode.TRAKT) {
                return@update current
            }
            current.copy(
                showManageDialog = true,
                manageSelectedListKey = current.manageSelectedListKey
                    ?: current.listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        _uiState.update { current ->
            current.copy(
                showManageDialog = false,
                listEditorState = null,
                errorMessage = null
            )
        }
    }

    fun onSelectManageList(listKey: String) {
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE),
                errorMessage = null
            )
        }
    }

    fun onStartEditList() {
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.traktListId?.toString(),
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: TraktListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(name = value))
        }
    }

    fun onUpdateEditorDescription(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(description = value))
        }
    }

    fun onUpdateEditorPrivacy(value: TraktListPrivacy) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(privacy = value))
        }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError(context.getString(R.string.library_error_list_name_required))
            return
        }
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                when (editor.mode) {
                    LibraryListEditorState.Mode.CREATE -> {
                        libraryRepository.createPersonalList(
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage(context.getString(R.string.library_list_created))
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException(context.getString(R.string.library_error_invalid_list))
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage(context.getString(R.string.library_list_updated))
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: context.getString(R.string.library_error_save_list_failed))
            }
        }
    }

    fun onDeleteSelectedList() {
        val selected = selectedManagePersonalList() ?: return
        val listId = selected.traktListId?.toString() ?: return
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.deletePersonalList(listId)
                setTransientMessage(context.getString(R.string.library_list_deleted))
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: context.getString(R.string.library_error_delete_list_failed))
            }
        }
    }

    fun onMoveSelectedListUp() {
        reorderSelectedList(moveUp = true)
    }

    fun onMoveSelectedListDown() {
        reorderSelectedList(moveUp = false)
    }

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun observeLibraryData() {
        val selectedProviderAuthenticated = libraryRepository.sourceMode.flatMapLatest { sourceMode ->
            sourceMode.providerId
                ?.let { providerId -> trackingProviderRegistry.provider(providerId)?.isAuthenticated }
                ?: flowOf(true)
        }
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                libraryRepository.listTabs,
                libraryPreferences.sortOption,
                authManager.authState,
                selectedProviderAuthenticated,
                libraryPreferences.lastSelectedList,
                libraryPreferences.lastSelectedType
            ) { args ->
                val sourceMode = args[0] as LibrarySourceMode
                val isSyncing = args[1] as Boolean
                @Suppress("UNCHECKED_CAST")
                val items = args[2] as List<LibraryEntry>
                @Suppress("UNCHECKED_CAST")
                val listTabs = args[3] as List<LibraryListTab>
                val persistedSortKey = args[4] as String?
                val authState = args[5] as AuthState
                val isTrackingAuthenticated = args[6] as Boolean
                val persistedListKey = args[7] as String?
                val persistedTypeKey = args[8] as String?
                DataBundle(
                    sourceMode = sourceMode,
                    isSyncing = isSyncing,
                    items = items,
                    listTabs = listTabs,
                    persistedSortKey = persistedSortKey,
                    authState = authState,
                    isTrackingAuthenticated = isTrackingAuthenticated,
                    persistedListKey = persistedListKey,
                    persistedTypeKey = persistedTypeKey
                )
            }.collectLatest { bundle ->
                val (sourceMode, isSyncing, items, listTabs, persistedSortKey, authState, isTrackingAuthenticated, persistedListKey, persistedTypeKey) = bundle
                _uiState.update { current ->
                    val nextSelectedList = when {
                        sourceMode.providerId != null && isTrackingAuthenticated -> {
                            current.selectedListKey
                                ?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: persistedListKey?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: listTabs.firstOrNull()?.key
                        }
                        else -> null
                    }

                    val nextManageSelected = current.manageSelectedListKey
                        ?.takeIf { key ->
                            listTabs.any { tab ->
                                tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                            }
                        }
                        ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

                    val nextSelectedType = current.selectedTypeTab
                        ?: persistedTypeKey?.let { key -> LibraryTypeTab(key = key, label = "") }
                        ?: LibraryTypeTab.All.copy(label = context.getString(R.string.library_type_all))
                    val sortOptions = if (sourceMode.providerId != null && isTrackingAuthenticated) {
                        LibrarySortOption.TrackingOptions
                    } else {
                        LibrarySortOption.LocalOptions
                    }
                    val modeDefault = if (sourceMode.providerId != null && isTrackingAuthenticated) LibrarySortOption.DEFAULT else LibrarySortOption.ADDED_DESC
                    val persistedSort = persistedSortKey?.let { key ->
                        LibrarySortOption.entries.find { it.key == key }
                    }
                    val nextSelectedSort = (persistedSort ?: current.selectedSortOption)
                        .takeIf { it in sortOptions }
                        ?: modeDefault

                    val isNuvioAccount = sourceMode == LibrarySourceMode.LOCAL && authState is AuthState.FullAccount

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        allItems = items,
                        listTabs = listTabs,
                        availableSortOptions = sortOptions,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        selectedSortOption = nextSelectedSort,
                        manageSelectedListKey = nextManageSelected,
                        isNuvioAccount = isNuvioAccount,
                        isTrackingAuthenticated = isTrackingAuthenticated,
                        isSyncing = isSyncing,
                        isLoading = isSyncing && items.isEmpty()
                    )
                    updated.withVisibleItems().withVisibleCloudItems()
                }
            }
        }
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, cornerRadiusDp ->
                widthDp to cornerRadiusDp
            }.collectLatest { (widthDp, cornerRadiusDp) ->
                _uiState.update { current ->
                    if (current.posterCardWidthDp == widthDp &&
                        current.posterCardCornerRadiusDp == cornerRadiusDp
                    ) {
                        current
                    } else {
                        current.copy(
                            posterCardWidthDp = widthDp,
                            posterCardCornerRadiusDp = cornerRadiusDp
                        )
                    }
                }
            }
        }
    }

    private fun observeCloudLibrarySettings() {
        viewModelScope.launch {
            debridSettingsDataStore.settings
                .map { settings ->
                    CloudLibrarySettingsSnapshot(
                        enabled = settings.cloudLibraryEnabled,
                        connectionKeys = DebridProviders.configuredServices(settings)
                            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
                            .map { credential -> "${credential.provider.id}:${credential.apiKey}" }
                    )
                }
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    if (!snapshot.enabled) {
                        cloudRefreshJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                cloudLibrary = CloudLibraryUiState(isLoaded = true, isEnabled = false),
                                visibleCloudItems = emptyList(),
                                availableCloudProviders = emptyList(),
                                availableCloudTypes = emptyList(),
                                selectedCloudProviderId = null,
                                selectedCloudType = null,
                                resolvingCloudFileKey = null,
                                cloudLibrarySettingsVersion = state.cloudLibrarySettingsVersion + 1L
                            )
                        }
                    } else if (snapshot.connectionKeys.isEmpty()) {
                        cloudRefreshJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                cloudLibrary = CloudLibraryUiState(isLoaded = true, isEnabled = true),
                                visibleCloudItems = emptyList(),
                                availableCloudProviders = emptyList(),
                                availableCloudTypes = emptyList(),
                                selectedCloudProviderId = null,
                                selectedCloudType = null,
                                resolvingCloudFileKey = null,
                                cloudLibrarySettingsVersion = state.cloudLibrarySettingsVersion + 1L
                            )
                        }
                    } else {
                        cloudRefreshJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                cloudLibrary = CloudLibraryUiState(isLoaded = false, isEnabled = true),
                                visibleCloudItems = emptyList(),
                                availableCloudProviders = emptyList(),
                                availableCloudTypes = emptyList(),
                                selectedCloudProviderId = null,
                                selectedCloudType = null,
                                resolvingCloudFileKey = null,
                                cloudLibrarySettingsVersion = state.cloudLibrarySettingsVersion + 1L
                            )
                        }
                    }
                }
        }
    }

    private data class DataBundle(
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>,
        val persistedSortKey: String?,
        val authState: AuthState,
        val isTrackingAuthenticated: Boolean,
        val persistedListKey: String? = null,
        val persistedTypeKey: String? = null
    )

    private data class CloudLibrarySettingsSnapshot(
        val enabled: Boolean,
        val connectionKeys: List<String>
    )

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.pendingOperation) return

        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedKey = state.manageSelectedListKey ?: return
        val selectedIndex = personalTabs.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return

        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return

        val reordered = personalTabs.toMutableList().apply {
            add(targetIndex, removeAt(selectedIndex))
        }
        val orderedIds = reordered.mapNotNull { tab ->
            tab.traktListId?.toString() ?: tab.key.removePrefix(TraktLibraryService.PERSONAL_KEY_PREFIX)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.reorderPersonalLists(orderedIds)
                setTransientMessage(context.getString(R.string.library_list_order_updated))
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: context.getString(R.string.library_error_reorder_lists_failed))
            }
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun prettifyTypeLabel(key: String): String {
        return key
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            .ifBlank { context.getString(R.string.type_unknown) }
    }

    private fun LibraryEntry.extractYear(): String? =
        releaseInfo?.let { YEAR_REGEX.find(it)?.value }

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        val listFiltered = if (sourceMode.providerId != null) {
            val listKey = selectedListKey ?: ""
            allItems.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            allItems
        }

        // Step 2: Type filter
        val selectedTypeKey = selectedTypeTab?.key
        val typeFiltered = listFiltered.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                (entry.mediaCategory ?: entry.type).trim().lowercase(Locale.ROOT) == selectedTypeKey
        }

        // Step 3: Genre filter
        val genreFiltered = if (selectedGenre != null) {
            typeFiltered.filter { entry ->
                entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            }
        } else {
            typeFiltered
        }

        // Step 4: Year filter
        val yearFiltered = if (selectedYear != null) {
            genreFiltered.filter { entry -> entry.extractYear() == selectedYear }
        } else {
            genreFiltered
        }

        // Step 5: Watched status filter
        val watchedFiltered = when (selectedWatchedFilter) {
            LibraryWatchedFilter.ALL -> yearFiltered
            LibraryWatchedFilter.WATCHED -> yearFiltered.filter { entry ->
                val isMovie = entry.type.equals("movie", ignoreCase = true)
                if (isMovie) watchedMovieIds.contains(entry.id)
                else watchedSeriesIds.contains(entry.id)
            }
            LibraryWatchedFilter.UNWATCHED -> yearFiltered.filter { entry ->
                val isMovie = entry.type.equals("movie", ignoreCase = true)
                if (isMovie) !watchedMovieIds.contains(entry.id)
                else !watchedSeriesIds.contains(entry.id)
            }
        }

        // Faceted counts — each filter counts items matching all OTHER active filters

        // Genre counts: from typeFiltered (after list+type), applying year filter but NOT genre filter
        val itemsForGenreCounts = if (selectedYear != null) {
            typeFiltered.filter { it.extractYear() == selectedYear }
        } else {
            typeFiltered
        }
        val genreCounts = mutableMapOf<String, Int>()
        itemsForGenreCounts.forEach { entry ->
            entry.genres.forEach { genre ->
                val normalized = genre.trim()
                if (normalized.isNotBlank()) {
                    genreCounts[normalized] = (genreCounts[normalized] ?: 0) + 1
                }
            }
        }
        val genreOptions = genreCounts.entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { (genre, count) -> FilterOption(key = genre, label = genre, count = count) }

        // Year counts: from typeFiltered (after list+type), applying genre filter but NOT year filter
        val itemsForYearCounts = if (selectedGenre != null) {
            typeFiltered.filter { entry ->
                entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            }
        } else {
            typeFiltered
        }
        val yearCounts = mutableMapOf<String, Int>()
        itemsForYearCounts.forEach { entry ->
            val year = entry.extractYear() ?: return@forEach
            yearCounts[year] = (yearCounts[year] ?: 0) + 1
        }
        val yearOptions = yearCounts.entries
            .sortedByDescending { it.key }
            .map { (year, count) -> FilterOption(key = year, label = year, count = count) }

        // Type tab counts: from listFiltered, applying genre+year filters
        val itemsForTypeCounts = listFiltered.filter { entry ->
            val genreMatch = selectedGenre == null || entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            val yearMatch = selectedYear == null || entry.extractYear() == selectedYear
            genreMatch && yearMatch
        }

        // Step 6: Sort
        val sorted = when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> if (sourceMode.providerId != null) {
                watchedFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                watchedFiltered
            }
            LibrarySortOption.ADDED_DESC -> watchedFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> watchedFiltered.sortedWith(
                compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> watchedFiltered.sortedWith(
                compareBy<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> watchedFiltered.sortedWith(
                compareByDescending<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }
                    .thenBy { it.id }
            )
        }

        // Rebuild type tabs with counts
        val typeTabsWithCounts = buildTypeTabsWithCounts(listFiltered, itemsForTypeCounts)

        // Validate selections — clear if no longer valid
        val validGenre = selectedGenre?.takeIf { g -> genreOptions.any { it.key.equals(g, ignoreCase = true) } }
        val validYear = selectedYear?.takeIf { y -> yearOptions.any { it.key == y } }

        return copy(
            visibleItems = sorted,
            availableTypeTabs = typeTabsWithCounts,
            availableGenres = genreOptions,
            availableYears = yearOptions,
            selectedGenre = validGenre,
            selectedYear = validYear
        )
    }

    private fun LibraryUiState.withVisibleCloudItems(): LibraryUiState {
        val allCloudItems = cloudLibrary.items
        val providerFiltered = if (selectedCloudProviderId != null) {
            allCloudItems.filter { it.providerId == selectedCloudProviderId }
        } else {
            allCloudItems
        }
        val typeFiltered = if (selectedCloudType != null) {
            providerFiltered.filter { it.type == selectedCloudType }
        } else {
            providerFiltered
        }
        // Matches the item name or any of its file names, so a release title or a filename both work.
        val query = cloudSearchQuery.trim()
        val visible = if (query.isEmpty()) {
            typeFiltered
        } else {
            typeFiltered.filter { item ->
                item.name.contains(query, ignoreCase = true) ||
                    item.files.any { file -> file.name.contains(query, ignoreCase = true) }
            }
        }
        val providerCounts = allCloudItems
            .groupBy { it.providerId to it.providerName }
            .map { (provider, items) -> FilterOption(key = provider.first, label = provider.second, count = items.size) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        val typeCounts = providerFiltered
            .groupBy { it.type }
            .map { (type, items) -> FilterOption(key = type.name, label = cloudTypeLabel(type), count = items.size) }
            .sortedBy { option -> CloudLibraryItemType.valueOf(option.key).ordinal }
        val validProvider = selectedCloudProviderId?.takeIf { providerId -> providerCounts.any { it.key == providerId } }
        val validType = selectedCloudType?.takeIf { type -> typeCounts.any { it.key == type.name } }
        return copy(
            visibleCloudItems = visible,
            availableCloudProviders = providerCounts,
            availableCloudTypes = typeCounts,
            selectedCloudProviderId = validProvider,
            selectedCloudType = validType
        )
    }

    private fun cloudTypeLabel(type: CloudLibraryItemType): String =
        when (type) {
            CloudLibraryItemType.Torrent -> context.getString(R.string.cloud_library_type_torrents)
            CloudLibraryItemType.Usenet -> context.getString(R.string.cloud_library_type_usenet)
            CloudLibraryItemType.WebDownload -> context.getString(R.string.cloud_library_type_web)
            CloudLibraryItemType.File -> context.getString(R.string.cloud_library_type_files)
        }

    private fun buildTypeTabsWithCounts(
        allTypeItems: List<LibraryEntry>,
        filteredItems: List<LibraryEntry>
    ): List<LibraryTypeTab> {
        val byKey = linkedMapOf<String, String>()
        allTypeItems.forEach { entry ->
            // Use mediaCategory (e.g. "anime") as the display type when present,
            // so anime shows as a separate filter from movies/series.
            val key = (entry.mediaCategory ?: entry.type).trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            if (!byKey.containsKey(key)) {
                byKey[key] = prettifyTypeLabel(key)
            }
        }
        val countByType = mutableMapOf<String, Int>()
        filteredItems.forEach { entry ->
            val key = (entry.mediaCategory ?: entry.type).trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            countByType[key] = (countByType[key] ?: 0) + 1
        }
        val allCount = filteredItems.size
        val allTab = LibraryTypeTab(key = LibraryTypeTab.ALL_KEY, label = "${context.getString(R.string.library_type_all)} ($allCount)")
        // Stable order: movies, series/tv/show, anime, then anything else alphabetically.
        val typeOrder = listOf("movie", "series", "tv", "show", "anime")
        val sortedKeys = byKey.keys.sortedWith(compareBy { key ->
            val idx = typeOrder.indexOf(key)
            if (idx >= 0) idx else typeOrder.size
        })
        return listOf(allTab) + sortedKeys.map { key ->
            LibraryTypeTab(key = key, label = "${byKey[key]} (${countByType[key] ?: 0})")
        }
    }
}

// Strip leading English articles ("The", "A", "An") when sorting by title, so
// "The Walking Dead" sorts under W and not T. Matches how streaming services
// and most media libraries order titles alphabetically.
private val LEADING_ARTICLE_REGEX = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

private fun titleSortKey(title: String): String =
    title.trim().replace(LEADING_ARTICLE_REGEX, "").lowercase(Locale.ROOT)

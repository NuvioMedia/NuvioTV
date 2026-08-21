package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.cloud.CloudLibraryFile
import com.nuvio.tv.core.cloud.CloudLibraryItem
import com.nuvio.tv.core.cloud.CloudLibraryItemType
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.stream.PlayerChoiceDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatAddonTypeLabel
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

private const val KEY_REPEAT_THROTTLE_MS = 80L

private enum class LibraryViewMode {
    Saved,
    Cloud
}

@Composable
private fun localizedTypeLabel(key: String): String = when (key.lowercase()) {
    LibraryTypeTab.ALL_KEY -> stringResource(R.string.library_type_all)
    else -> localizedContentType(key)
}

@Composable
private fun LibraryListTab.localizedTitle(): String {
    return when {
        key == "simkl:status:watching" -> stringResource(R.string.library_status_watching)
        key == "simkl:status:plantowatch" -> stringResource(R.string.library_status_plan_to_watch)
        key == "simkl:status:hold" -> stringResource(R.string.library_status_on_hold)
        key == "simkl:status:completed" -> stringResource(R.string.library_status_completed)
        key == "simkl:status:dropped" -> stringResource(R.string.library_status_dropped)
        type == LibraryListTab.Type.WATCHLIST -> stringResource(R.string.library_watchlist)
        else -> title
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String?) -> Unit,
    onCloudPlaybackResolved: (CloudLibraryPlaybackInfo) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    val activityContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.Saved) }
    var activeCloudItem by remember { mutableStateOf<CloudLibraryItem?>(null) }
    var pendingCloudPlayback by remember { mutableStateOf<CloudLibraryPlaybackInfo?>(null) }
    var showCloudPlayerChoice by remember { mutableStateOf(false) }
    val primaryFocusRequester = remember { FocusRequester() }
    val selectorFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var pendingPrimaryFocus by remember { mutableStateOf(true) }
    var lastFocusedPosterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleItemKeys = remember(uiState.visibleItems) {
        uiState.visibleItems.map { "${it.type}:${it.id}" }
    }
    val visibleItemIndexByKey = remember(visibleItemKeys) {
        visibleItemKeys.withIndex().associate { (index, key) -> key to index }
    }
    val posterFocusRequesters = remember(visibleItemKeys) {
        visibleItemKeys.associateWith { FocusRequester() }
    }
    val firstVisiblePosterKey = visibleItemKeys.firstOrNull()
    val posterCardStyle = PosterCardDefaults.Style

    val routeCloudPlayback: (CloudLibraryPlaybackInfo) -> Unit = { info ->
        scope.launch {
            when (viewModel.getPlayerPreference()) {
                PlayerPreference.INTERNAL -> onCloudPlaybackResolved(info)
                PlayerPreference.EXTERNAL -> {
                    viewModel.launchCloudPlaybackExternally(info, activityContext)
                }
                PlayerPreference.ASK_EVERY_TIME -> {
                    pendingCloudPlayback = info
                    showCloudPlayerChoice = true
                }
            }
        }
    }

    LaunchedEffect(viewMode, uiState.cloudLibrary.isEnabled, uiState.cloudLibrary.isLoaded, uiState.cloudLibrarySettingsVersion) {
        if (viewMode == LibraryViewMode.Cloud) {
            viewModel.ensureCloudLibraryLoaded()
        }
    }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            pendingPrimaryFocus = true
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size) {
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val restoreKey = lastFocusedPosterKey
            val restoreIndex = restoreKey?.let { visibleItemIndexByKey[it] }
            val restoreRequester = restoreKey?.let { posterFocusRequesters[it] }

            var focused = false
            if (restoreIndex != null && restoreRequester != null) {
                if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
                    runCatching { gridState.scrollToItem(restoreIndex) }
                }
                focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                if (!focused) {
                    delay(16)
                    focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                }
            }

            if (!focused) {
                focused = runCatching { primaryFocusRequester.requestFocus() }.isSuccess
            }
            if (!focused) {
                delay(16)
                runCatching { primaryFocusRequester.requestFocus() }
            }
            pendingPrimaryFocus = false
        }
    }

    LaunchedEffect(uiState.sortSelectionVersion, firstVisiblePosterKey) {
        if (uiState.sortSelectionVersion <= 0L) return@LaunchedEffect
        val targetKey = firstVisiblePosterKey ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        var focused = false
        repeat(6) {
            focused = posterFocusRequesters[targetKey]
                ?.let { requester -> runCatching { requester.requestFocus() }.isSuccess }
                ?: false
            if (focused) return@LaunchedEffect
            delay(24)
        }
    }

    if (uiState.isLoading) {
        val loadingFocusRequester = remember { FocusRequester() }
        LaunchedEffect(uiState.isLoading) {
            loadingFocusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(loadingFocusRequester)
                    .focusable()
            )
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LoadingIndicator()
                Text(
                    text = stringResource(R.string.library_syncing_library),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        return
    }

    val lastKeyRepeatTime = remember { longArrayOf(0L) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = posterCardStyle.width),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeatTime[0] < KEY_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true
                    }
                    lastKeyRepeatTime[0] = now
                }
                false
            },
        contentPadding = PaddingValues(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = when {
                        viewMode == LibraryViewMode.Cloud -> stringResource(R.string.library_source_cloud).uppercase()
                        uiState.sourceMode == LibrarySourceMode.TRAKT -> "TRAKT"
                        uiState.sourceMode == LibrarySourceMode.SIMKL -> "SIMKL"
                        uiState.isNuvioAccount -> "NUVIO"
                        else -> stringResource(R.string.library_source_local)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showBuiltInHeader) NuvioTheme.colors.TextTertiary else Color.Transparent,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryViewModeRow(
                selectedMode = viewMode,
                primaryFocusRequester = primaryFocusRequester,
                onSelected = { mode ->
                    viewMode = mode
                    expandedPicker = null
                },
                // Refresh belongs to the cloud view only, pinned right in line with the tabs.
                trailing = if (viewMode == LibraryViewMode.Cloud) {
                    {
                        Button(
                            onClick = viewModel::refreshCloudLibrary,
                            enabled = !uiState.cloudLibrary.isRefreshing,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Text(
                                if (uiState.cloudLibrary.isRefreshing) {
                                    stringResource(R.string.library_syncing_btn)
                                } else {
                                    stringResource(R.string.cloud_library_refresh)
                                }
                            )
                        }
                    }
                } else {
                    null
                }
            )
        }

        if (viewMode == LibraryViewMode.Saved) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibrarySelectorsRow(
                    sourceMode = uiState.sourceMode,
                    listTabs = uiState.listTabs,
                    typeTabs = uiState.availableTypeTabs,
                    sortOptions = uiState.availableSortOptions,
                    genres = uiState.availableGenres,
                    years = uiState.availableYears,
                    selectedListKey = uiState.selectedListKey,
                    selectedTypeTab = uiState.selectedTypeTab,
                    selectedSortOption = uiState.selectedSortOption,
                    selectedGenre = uiState.selectedGenre,
                    selectedYear = uiState.selectedYear,
                    selectedWatchedFilter = uiState.selectedWatchedFilter,
                    primaryFocusRequester = selectorFocusRequester,
                    expandedPicker = expandedPicker,
                    onExpandedChange = { picker, shouldExpand ->
                        expandedPicker = if (shouldExpand) picker else null
                    },
                    onSelectList = { key ->
                        viewModel.onSelectListTab(key)
                        expandedPicker = null
                    },
                    onSelectType = { type ->
                        viewModel.onSelectTypeTab(type)
                        expandedPicker = null
                    },
                    onSelectSort = { sort ->
                        viewModel.onSelectSortOption(sort)
                        expandedPicker = null
                    },
                    onSelectGenre = { key ->
                        viewModel.onSelectGenre(key)
                        expandedPicker = null
                    },
                    onSelectYear = { key ->
                        viewModel.onSelectYear(key)
                        expandedPicker = null
                    },
                    onSelectWatchedFilter = { filter ->
                        viewModel.onSelectWatchedFilter(filter)
                        expandedPicker = null
                    }
                )
            }

            if (uiState.sourceMode == LibrarySourceMode.TRAKT && uiState.isTrackingAuthenticated) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryActionsRow(
                        pending = uiState.pendingOperation,
                        isSyncing = uiState.isSyncing,
                        showManageLists = uiState.sourceMode == LibrarySourceMode.TRAKT,
                        onManageLists = viewModel::onOpenManageLists,
                        onRefresh = viewModel::onRefresh
                    )
                }
            }

            if (uiState.visibleItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val selectedTypeLabel = uiState.selectedTypeTab?.let { localizedTypeLabel(it.key) }?.lowercase() ?: stringResource(R.string.library_type_items)
                    val title = when {
                        uiState.sourceMode == LibrarySourceMode.TRAKT && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_trakt_not_auth_title)
                        uiState.sourceMode == LibrarySourceMode.SIMKL && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_simkl_not_auth_title)
                        uiState.sourceMode == LibrarySourceMode.TRAKT -> stringResource(R.string.library_empty_trakt_title, selectedTypeLabel)
                        uiState.sourceMode == LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_title, selectedTypeLabel)
                        else -> stringResource(R.string.library_empty_local_title, selectedTypeLabel)
                    }
                    val subtitle = when {
                        uiState.sourceMode == LibrarySourceMode.TRAKT && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_trakt_not_auth_subtitle)
                        uiState.sourceMode == LibrarySourceMode.SIMKL && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_simkl_not_auth_subtitle)
                        uiState.sourceMode == LibrarySourceMode.TRAKT -> stringResource(R.string.library_empty_trakt_subtitle)
                        uiState.sourceMode == LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_subtitle)
                        else -> stringResource(R.string.library_empty_local_subtitle)
                    }
                    EmptyScreenState(
                        title = title,
                        subtitle = subtitle,
                        icon = Icons.Default.BookmarkBorder
                    )
                }
            }

            items(uiState.visibleItems, key = { "${it.type}:${it.id}" }) { item ->
                val focusKey = "${item.type}:${item.id}"
                val isSeries = item.type.equals("series", ignoreCase = true) || item.type.equals("tv", ignoreCase = true)
                val previewForLongPress = remember(item) {
                    item.toMetaPreview().copy(posterShape = PosterShape.POSTER)
                }
                GridContentCard(
                    item = previewForLongPress,
                    posterCardStyle = posterCardStyle,
                    isWatched = if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds,
                    focusRequester = posterFocusRequesters[focusKey],
                    showLabel = true,
                    onFocused = {
                        lastFocusedPosterKey = focusKey
                        viewModel.prefetchMetaOnFocus(item.id, item.type)
                    },
                    onClick = {
                        lastFocusedPosterKey = focusKey
                        val backdrop = viewModel.getCachedBackdrop(item.id, item.type)
                        HeroBackdropState.update(backdrop)
                        onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                    },
                    onLongPress = {
                        lastFocusedPosterKey = focusKey
                        viewModel.posterOptions.show(previewForLongPress, item.addonBaseUrl)
                    }
                )
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CloudLibrarySelectorsRow(
                    providerOptions = uiState.availableCloudProviders,
                    typeOptions = uiState.availableCloudTypes,
                    selectedProviderId = uiState.selectedCloudProviderId,
                    selectedType = uiState.selectedCloudType,
                    expandedPicker = expandedPicker,
                    onExpandedChange = { picker, shouldExpand ->
                        expandedPicker = if (shouldExpand) picker else null
                    },
                    onSelectProvider = { providerId ->
                        viewModel.onSelectCloudProvider(providerId)
                        expandedPicker = null
                    },
                    onSelectType = { type ->
                        viewModel.onSelectCloudType(type)
                        expandedPicker = null
                    }
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                CloudLibrarySearchRow(
                    query = uiState.cloudSearchQuery,
                    onQueryChange = viewModel::onCloudSearchQueryChange
                )
            }

            if (uiState.cloudLibrary.isRefreshing && uiState.visibleCloudItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CloudLibraryLoadingState()
                }
            } else if (!uiState.cloudLibrary.isEnabled) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.cloud_library_disabled_title),
                        subtitle = stringResource(R.string.cloud_library_disabled_message),
                        icon = Icons.Default.BookmarkBorder,
                        height = 260.dp
                    )
                }
            } else if (!uiState.cloudLibrary.hasConnectedProvider) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.cloud_library_connect_title),
                        subtitle = stringResource(R.string.cloud_library_connect_message),
                        icon = Icons.Default.BookmarkBorder,
                        height = 260.dp
                    )
                }
            } else if (uiState.visibleCloudItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.cloud_library_empty_title),
                        subtitle = stringResource(R.string.cloud_library_empty_message),
                        icon = Icons.Default.BookmarkBorder,
                        height = 260.dp
                    )
                }
            }

            items(
                items = uiState.visibleCloudItems,
                key = { it.stableKey },
                span = { GridItemSpan(maxLineSpan) }
            ) { item ->
                CloudLibraryCard(
                    item = item,
                    isResolving = uiState.resolvingCloudFileKey?.startsWith(item.stableKey) == true,
                    onClick = {
                        val playableFiles = item.playableFiles
                        when (playableFiles.size) {
                            0 -> viewModel.onCloudItemHasNoPlayableFiles()
                            1 -> viewModel.resolveCloudPlayback(item, playableFiles.first(), routeCloudPlayback)
                            else -> activeCloudItem = item
                        }
                    }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm)) }
    }

    if (uiState.showManageDialog && uiState.sourceMode == LibrarySourceMode.TRAKT) {
        ManageListsDialog(
            tabs = uiState.listTabs,
            selectedKey = uiState.manageSelectedListKey,
            errorMessage = uiState.errorMessage,
            pending = uiState.pendingOperation,
            onSelect = viewModel::onSelectManageList,
            onCreate = viewModel::onStartCreateList,
            onEdit = viewModel::onStartEditList,
            onMoveUp = viewModel::onMoveSelectedListUp,
            onMoveDown = viewModel::onMoveSelectedListDown,
            onDelete = { showDeleteConfirm = true },
            onDismiss = viewModel::onCloseManageLists
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            pending = uiState.pendingOperation,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.onDeleteSelectedList()
            },
            onCancel = { showDeleteConfirm = false }
        )
    }

    val listEditor = uiState.listEditorState
    if (listEditor != null && uiState.showManageDialog) {
        ListEditorDialog(
            state = listEditor,
            pending = uiState.pendingOperation,
            onNameChanged = viewModel::onUpdateEditorName,
            onDescriptionChanged = viewModel::onUpdateEditorDescription,
            onPrivacyChanged = viewModel::onUpdateEditorPrivacy,
            onSave = viewModel::onSubmitEditor,
            onCancel = viewModel::onCancelEditor
        )
    }

    activeCloudItem?.let { item ->
        CloudFilePickerDialog(
            item = item,
            resolvingFileKey = uiState.resolvingCloudFileKey,
            onPlay = { file ->
                viewModel.resolveCloudPlayback(item, file) { info ->
                    activeCloudItem = null
                    routeCloudPlayback(info)
                }
            },
            onDismiss = { activeCloudItem = null }
        )
    }

    if (showCloudPlayerChoice && pendingCloudPlayback != null) {
        PlayerChoiceDialog(
            onInternalSelected = {
                showCloudPlayerChoice = false
                pendingCloudPlayback?.let(onCloudPlaybackResolved)
                pendingCloudPlayback = null
            },
            onExternalSelected = {
                showCloudPlayerChoice = false
                val info = pendingCloudPlayback
                pendingCloudPlayback = null
                if (info != null) {
                    scope.launch { viewModel.launchCloudPlaybackExternally(info, activityContext) }
                }
            },
            onDismiss = {
                showCloudPlayerChoice = false
                pendingCloudPlayback = null
            }
        )
    }

    val transientMessage = uiState.transientMessage
    if (!transientMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xl)
                    .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryViewModeRow(
    selectedMode: LibraryViewMode,
    primaryFocusRequester: FocusRequester,
    onSelected: (LibraryViewMode) -> Unit,
    /** Optional action pinned to the right of the tabs (used for the cloud refresh button). */
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            LibraryViewMode.entries.forEachIndexed { index, mode ->
                val selected = mode == selectedMode
                Button(
                    onClick = { onSelected(mode) },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(primaryFocusRequester) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = when (mode) {
                            LibraryViewMode.Saved -> stringResource(R.string.library_source_saved)
                            LibraryViewMode.Cloud -> stringResource(R.string.library_source_cloud)
                        }
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudLibrarySelectorsRow(
    providerOptions: List<FilterOption>,
    typeOptions: List<FilterOption>,
    selectedProviderId: String?,
    selectedType: CloudLibraryItemType?,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectProvider: (String?) -> Unit,
    onSelectType: (CloudLibraryItemType?) -> Unit
) {
    val allLabel = stringResource(R.string.cloud_library_provider_all)
    val typeAllLabel = stringResource(R.string.cloud_library_type_all)
    val selectedProviderLabel = providerOptions.firstOrNull { it.key == selectedProviderId }?.label ?: allLabel
    val selectedTypeLabel = selectedType?.localizedLabel() ?: typeAllLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        LibraryDropdownPicker(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.cloud_library_select_provider),
            value = selectedProviderLabel,
            selectedValue = selectedProviderId ?: "__all__",
            expanded = expandedPicker == "cloud_provider",
            options = listOf(LibraryOption(allLabel, "__all__")) + providerOptions.map {
                LibraryOption("${it.label} (${it.count})", it.key)
            },
            onExpandedChange = { onExpandedChange("cloud_provider", it) },
            onSelect = { option ->
                onSelectProvider(if (option.value == "__all__") null else option.value)
            }
        )

        LibraryDropdownPicker(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.cloud_library_select_type),
            value = selectedTypeLabel,
            selectedValue = selectedType?.name ?: "__all__",
            expanded = expandedPicker == "cloud_type",
            options = listOf(LibraryOption(typeAllLabel, "__all__")) + typeOptions.map {
                LibraryOption("${it.label} (${it.count})", it.key)
            },
            onExpandedChange = { onExpandedChange("cloud_type", it) },
            onSelect = { option ->
                onSelectType(CloudLibraryItemType.entries.firstOrNull { it.name == option.value })
            }
        )
    }
}

@Composable
private fun CloudLibraryItemType.localizedLabel(): String =
    when (this) {
        CloudLibraryItemType.Torrent -> stringResource(R.string.cloud_library_type_torrents)
        CloudLibraryItemType.Usenet -> stringResource(R.string.cloud_library_type_usenet)
        CloudLibraryItemType.WebDownload -> stringResource(R.string.cloud_library_type_web)
        CloudLibraryItemType.File -> stringResource(R.string.cloud_library_type_files)
    }

private fun isCloudSearchSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

/**
 * Free-text filter over the already-loaded cloud library. Purely local: it never triggers a
 * provider request, it just narrows [LibraryUiState.visibleCloudItems]. Filtering is applied on
 * every keystroke, so results narrow live as you type.
 *
 * Follows the same D-pad text-entry pattern as the list editor dialog: the field stays read-only
 * until SELECT is pressed, so arrow keys keep navigating the grid instead of moving a caret.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudLibrarySearchRow(
    query: String,
    onQueryChange: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isCloudSearchSelectKey(native.keyCode)) {
                        editing = true
                        keyboardController?.show()
                    }
                    false
                },
            readOnly = !editing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    editing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.cloud_library_search_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedBorderColor = NuvioTheme.colors.FocusRing,
                unfocusedBorderColor = NuvioTheme.colors.Border,
                focusedLabelColor = NuvioTheme.colors.TextSecondary,
                unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                cursorColor = NuvioTheme.colors.FocusRing
            )
        )

        if (query.isNotEmpty()) {
            Button(
                onClick = {
                    onQueryChange("")
                    editing = false
                },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.cloud_library_search_clear))
            }
        }
    }
}

@Composable
private fun CloudLibraryLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LoadingIndicator()
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.library_syncing_library),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudLibraryCard(
    item: CloudLibraryItem,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    val fileLine = cloudLibraryFileLine(item)
    Card(
        onClick = { if (!isResolving) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(10.dp))
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            fileLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = cloudLibraryMetadata(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Text(
                    text = if (isResolving) {
                        stringResource(R.string.cloud_library_opening)
                    } else {
                        cloudLibraryPlayableLabel(item)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.playableFiles.isEmpty()) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.Primary
                )
            }
        }
    }
}

@Composable
private fun cloudLibraryFileLine(item: CloudLibraryItem): String? =
    when (val count = item.playableFiles.size) {
        0 -> stringResource(R.string.cloud_library_no_playable_files)
        1 -> item.playableFiles.first().name.takeIf { it != item.name }
        else -> stringResource(R.string.cloud_library_playable_file_count, count)
    }

@Composable
private fun cloudLibraryPlayableLabel(item: CloudLibraryItem): String =
    when (val count = item.playableFiles.size) {
        0 -> stringResource(R.string.cloud_library_no_playable_files)
        1 -> stringResource(R.string.cloud_library_one_playable_file)
        else -> stringResource(R.string.cloud_library_playable_file_count, count)
    }

@Composable
private fun cloudLibraryMetadata(item: CloudLibraryItem): String {
    val parts = listOfNotNull(
        item.providerName.takeIf { it.isNotBlank() },
        item.type.localizedLabel(),
        item.status?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cloud_library_status_ready),
        formatCloudSize(item.sizeBytes)
    )
    return parts.joinToString(" • ")
}

private fun formatCloudSize(sizeBytes: Long?): String? {
    val bytes = sizeBytes ?: return null
    if (bytes <= 0L) return null
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f GB", gb)
    } else {
        val mb = bytes / 1_000_000.0
        String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudFilePickerDialog(
    item: CloudLibraryItem,
    resolvingFileKey: String?,
    onPlay: (CloudLibraryFile) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.cloud_library_file_picker_title),
        subtitle = item.name,
        width = 860.dp,
        suppressFirstKeyUp = false
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(item.playableFiles, key = { it.stableKey }) { file ->
                val resolving = resolvingFileKey == "${item.stableKey}:${file.stableKey}"
                Card(
                    onClick = { if (!resolving) onPlay(file) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        formatCloudSize(file.sizeBytes)?.let { size ->
                            Text(
                                text = size,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        Text(
                            text = stringResource(R.string.cloud_library_play_file),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.Primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibrarySelectorsRow(
    sourceMode: LibrarySourceMode,
    listTabs: List<LibraryListTab>,
    typeTabs: List<LibraryTypeTab>,
    sortOptions: List<LibrarySortOption>,
    genres: List<FilterOption>,
    years: List<FilterOption>,
    selectedListKey: String?,
    selectedTypeTab: LibraryTypeTab?,
    selectedSortOption: LibrarySortOption,
    selectedGenre: String?,
    selectedYear: String?,
    selectedWatchedFilter: LibraryWatchedFilter,
    primaryFocusRequester: FocusRequester,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectList: (String) -> Unit,
    onSelectType: (LibraryTypeTab) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSelectYear: (String?) -> Unit,
    onSelectWatchedFilter: (LibraryWatchedFilter) -> Unit
) {
    val selectedListLabel = listTabs.firstOrNull { it.key == selectedListKey }?.localizedTitle()
        ?: stringResource(R.string.action_select)
    val selectedTypeLabel = selectedTypeTab?.let {
        if (it.key == LibraryTypeTab.ALL_KEY) stringResource(R.string.library_type_all) else localizedTypeLabel(it.key)
    } ?: stringResource(R.string.library_type_all)
    val selectedSortLabel = stringResource(selectedSortOption.labelResId)
    val allLabel = stringResource(R.string.library_type_all)
    val selectedGenreLabel = selectedGenre?.let { localizedGenreLabel(it) } ?: allLabel
    val selectedYearLabel = selectedYear ?: allLabel

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            if (sourceMode != LibrarySourceMode.LOCAL) {
                LibraryDropdownPicker(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(primaryFocusRequester),
                    title = stringResource(R.string.library_filter_list),
                    value = selectedListLabel,
                    selectedValue = selectedListKey,
                    expanded = expandedPicker == "list",
                    options = listTabs.map { LibraryOption(it.localizedTitle(), it.key) },
                    onExpandedChange = { onExpandedChange("list", it) },
                    onSelect = { onSelectList(it.value) }
                )
            }

            LibraryDropdownPicker(
                modifier = if (sourceMode != LibrarySourceMode.LOCAL) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                        .weight(1f)
                        .focusRequester(primaryFocusRequester)
                },
                title = stringResource(R.string.library_filter_type),
                value = selectedTypeLabel,
                selectedValue = selectedTypeTab?.key,
                expanded = expandedPicker == "type",
                options = typeTabs.map {
                    val label = if (it.key == LibraryTypeTab.ALL_KEY) it.label else {
                        val countPart = it.label.substringAfterLast("(", "").removeSuffix(")")
                        val localizedName = localizedTypeLabel(it.key)
                        if (countPart.isNotBlank()) "$localizedName ($countPart)" else localizedName
                    }
                    LibraryOption(label, it.key)
                },
                onExpandedChange = { onExpandedChange("type", it) },
                onSelect = { option ->
                    typeTabs.firstOrNull { it.key == option.value }?.let(onSelectType)
                }
            )

            if (sortOptions.isNotEmpty()) {
                LibraryDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.library_filter_sort),
                    value = selectedSortLabel,
                    selectedValue = selectedSortOption.key,
                    expanded = expandedPicker == "sort",
                    options = sortOptions.map { LibraryOption(stringResource(it.labelResId), it.key) },
                    onExpandedChange = { onExpandedChange("sort", it) },
                    onSelect = { option ->
                        sortOptions.firstOrNull { it.key == option.value }?.let(onSelectSort)
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            if (genres.isNotEmpty()) {
                val genreAllOption = LibraryOption(allLabel, "__all__")
                LibraryDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.library_filter_genre),
                    value = selectedGenreLabel,
                    selectedValue = selectedGenre ?: "__all__",
                    expanded = expandedPicker == "genre",
                    options = listOf(genreAllOption) + genres.map {
                        LibraryOption("${localizedGenreLabel(it.label)} (${it.count})", it.key)
                    },
                    onExpandedChange = { onExpandedChange("genre", it) },
                    onSelect = { option ->
                        onSelectGenre(if (option.value == "__all__") null else option.value)
                    }
                )
            }

            if (years.isNotEmpty()) {
                val yearAllOption = LibraryOption(allLabel, "__all__")
                LibraryDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.library_filter_year),
                    value = selectedYearLabel,
                    selectedValue = selectedYear ?: "__all__",
                    expanded = expandedPicker == "year",
                    options = listOf(yearAllOption) + years.map {
                        LibraryOption("${it.label} (${it.count})", it.key)
                    },
                    onExpandedChange = { onExpandedChange("year", it) },
                    onSelect = { option ->
                        onSelectYear(if (option.value == "__all__") null else option.value)
                    }
                )
            }

            val watchedFilterLabel = stringResource(selectedWatchedFilter.labelResId)
            LibraryDropdownPicker(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.library_filter_watched),
                value = watchedFilterLabel,
                selectedValue = selectedWatchedFilter.key,
                expanded = expandedPicker == "watched",
                options = LibraryWatchedFilter.entries.map {
                    LibraryOption(stringResource(it.labelResId), it.key)
                },
                onExpandedChange = { onExpandedChange("watched", it) },
                onSelect = { option ->
                    LibraryWatchedFilter.entries.firstOrNull { it.key == option.value }
                        ?.let(onSelectWatchedFilter)
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryDropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<LibraryOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LibraryOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands (fixes #2848 / incomplete #2507 race on TV).
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    // Popup content attaches focus targets a few frames after expand. A fixed 50ms
    // delay was flaky on TV and left focus on the first item for non-top selections.
    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // Material DropdownMenu may still move initial focus to the first item after
        // the popup settles; re-assert once so long lists keep the real selection.
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse, title) else stringResource(R.string.cd_expand, title),
                        tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = when {
                    isOptionFocused -> NuvioTheme.colors.OnSecondary
                    isSelected -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextPrimary
                }
                val itemBackgroundColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NuvioTheme.colors.TextDisabled
                    )
                )
            }
        }
    }
}

private data class LibraryOption(
    val label: String,
    val value: String
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryActionsRow(
    pending: Boolean,
    isSyncing: Boolean,
    showManageLists: Boolean,
    onManageLists: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        if (showManageLists) {
            Button(
                onClick = onManageLists,
                enabled = !pending && !isSyncing,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.library_manage_lists))
            }
        }
        Button(
            onClick = onRefresh,
            enabled = !pending && !isSyncing,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(if (isSyncing) stringResource(R.string.library_syncing_btn) else stringResource(R.string.library_sync_btn))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    selectedKey: String?,
    errorMessage: String?,
    pending: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val personalTabs = remember(tabs) { tabs.filter { it.type == LibraryListTab.Type.PERSONAL } }
    val firstFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personalTabs.size) {
        val target = if (personalTabs.isNotEmpty()) firstFocusRequester else closeFocusRequester
        val focused = runCatching { target.requestFocus() }.isSuccess
        if (!focused) {
            delay(16)
            runCatching { target.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(620.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(NuvioTheme.radii.xl))
                .padding(NuvioTheme.spacing.xl)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.library_manage_trakt_lists),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB6B6)
                    )
                }

                if (personalTabs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_no_lists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(personalTabs, key = { it.key }) { tab ->
                            val selected = tab.key == selectedKey
                            Button(
                                onClick = { onSelect(tab.key) },
                                enabled = !pending,
                                modifier = if (tab.key == personalTabs.firstOrNull()?.key) {
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(firstFocusRequester)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextPrimary
                                )
                            ) {
                                Text(
                                    text = tab.localizedTitle(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCreate,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_create)) }
                    Button(
                        onClick = onEdit,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_edit)) }
                    Button(
                        onClick = onMoveUp,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_move_up)) }
                    Button(
                        onClick = onMoveDown,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_move_down)) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDelete,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_delete)) }
                    Button(
                        onClick = onDismiss,
                        enabled = !pending,
                        modifier = Modifier.focusRequester(closeFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_close)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListEditorDialog(
    state: LibraryListEditorState,
    pending: Boolean,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPrivacyChanged: (TraktListPrivacy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameEditing by remember { mutableStateOf(false) }
    var descriptionEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onCancel,
        title = if (state.mode == LibraryListEditorState.Mode.CREATE) stringResource(R.string.library_list_create_dialog_title) else stringResource(R.string.library_list_edit_dialog_title),
        width = 560.dp
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        nameEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        nameEditing = true
                        descriptionEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !nameEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    nameEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_name_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedBorderColor = NuvioTheme.colors.FocusRing,
                unfocusedBorderColor = NuvioTheme.colors.Border,
                focusedLabelColor = NuvioTheme.colors.TextSecondary,
                unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                cursorColor = NuvioTheme.colors.FocusRing
            )
        )

        androidx.compose.material3.OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(descriptionFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        descriptionEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        descriptionEditing = true
                        nameEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !descriptionEditing,
            minLines = 3,
            maxLines = 5,
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_description_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedBorderColor = NuvioTheme.colors.FocusRing,
                unfocusedBorderColor = NuvioTheme.colors.Border,
                focusedLabelColor = NuvioTheme.colors.TextSecondary,
                unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                cursorColor = NuvioTheme.colors.FocusRing
            )
        )

        Text(
            text = stringResource(R.string.library_list_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TraktListPrivacy.entries.toList(), key = { it.name }) { privacy ->
                val selected = privacy == state.privacy
                Button(
                    onClick = { onPrivacyChanged(privacy) },
                    enabled = !pending,
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(if (pending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteDialog(
    pending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    NuvioDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.library_delete_title),
        subtitle = stringResource(R.string.library_delete_subtitle),
        width = 420.dp
    ) {
        Button(
            onClick = onConfirm,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF4A2323),
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.library_list_delete))
        }
    }
}

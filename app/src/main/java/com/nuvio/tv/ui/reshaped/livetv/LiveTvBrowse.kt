@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvUiState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class LiveTvBrowseState {
    var focusedUrl by mutableStateOf<String?>(null)
    var previewUrl by mutableStateOf<String?>(null)
    var channelsFocused by mutableStateOf(false)
    var categoriesFocused by mutableStateOf(false)
    var lastAreaChannels = false
    var movingUrl by mutableStateOf<String?>(null)
    var picking by mutableStateOf(false)
    val picked = mutableStateMapOf<String, LiveTvChannel>()
    var categorySearch by mutableStateOf("")
    var quickFilter by mutableStateOf<String?>(null)
    var quickFiltersOpen by mutableStateOf(false)
    var typedNumber by mutableStateOf("")
    var shownKey: String? = null
    var categoryKeys: List<String> = emptyList()
    var previousChannels: List<LiveTvChannel> = emptyList()
    val holdCategory = mutableStateOf(false)
    val channelFocus = FocusRequester()
    val categoryFocus = FocusRequester()
    val categorySearchFocus = FocusRequester()
    val categoryListState = LazyListState()
    val channelListState = LazyListState()

    fun startPicking(first: LiveTvChannel) {
        picked.clear()
        picked[first.streamUrl] = first
        picking = true
    }

    fun stopPicking() {
        picking = false
        picked.clear()
    }

    fun togglePicked(channel: LiveTvChannel) {
        if (picked.remove(channel.streamUrl) == null) picked[channel.streamUrl] = channel
    }

    suspend fun focusChannel(channels: List<LiveTvChannel>, url: String?): Boolean {
        if (channels.isEmpty()) return false
        val index = channels.indexOfFirst { it.streamUrl == url }.takeIf { it >= 0 } ?: 0
        focusedUrl = channels[index].streamUrl
        repeat(2) {
            withFrameNanos { }
            if (runCatching { channelFocus.requestFocus() }.isSuccess && channelsFocused) return true
        }
        val visible = channelListState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) channelListState.scrollToItem((index - 2).coerceAtLeast(0))
        repeat(6) {
            withFrameNanos { }
            if (runCatching { channelFocus.requestFocus() }.isSuccess) return true
        }
        return false
    }

    suspend fun focusCategory(keys: List<String>, selectedKey: String): Boolean {
        holdCategory.value = true
        repeat(2) {
            withFrameNanos { }
            if (runCatching { categoryFocus.requestFocus() }.isSuccess && categoriesFocused) return true
        }
        val index = keys.indexOf(selectedKey).coerceAtLeast(0)
        val visible = categoryListState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) categoryListState.scrollToItem((index - 3).coerceAtLeast(0))
        repeat(6) {
            withFrameNanos { }
            if (runCatching { categoryFocus.requestFocus() }.isSuccess) return true
        }
        holdCategory.value = false
        return runCatching { categorySearchFocus.requestFocus() }.isSuccess
    }
}

private val PanelShape = RoundedCornerShape(22.dp)
private val RowShape = RoundedCornerShape(14.dp)
private val CategoryShape = RoundedCornerShape(10.dp)

@Composable
private fun panelColor() = NuvioTheme.colors.TextPrimary.copy(alpha = 0.035f)

@Composable
private fun panelBorder() = NuvioTheme.colors.TextPrimary.copy(alpha = 0.07f)

@Composable
private fun Modifier.liveTvPanel(): Modifier = this
    .clip(PanelShape)
    .background(panelColor())
    .border(1.dp, panelBorder(), PanelShape)

@Composable
internal fun LiveTvBrowseLayout(
    state: LiveTvBrowseState,
    uiState: LiveTvUiState,
    viewModel: LiveTvScreenModel,
    channels: List<LiveTvChannel>,
    filtering: Boolean,
    filterKey: String,
    onSelectCategory: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    clock: State<Long>,
    preview: LiveTvPreviewPlayer?,
    playVideo: Boolean,
    dialogOpen: Boolean,
    status: String?,
    statusIsError: Boolean,
    onPlay: (LiveTvChannel) -> Unit,
    onChannelOptions: (LiveTvChannel) -> Unit,
    onChoosePlaylist: () -> Unit,
    onEditList: (String) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddQuickFilter: () -> Unit,
    onHiddenCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val quickFilters by LiveTvPreferences.quickFilters.collectAsStateWithLifecycle()
    LaunchedEffect(quickFilters) {
        if (state.quickFilter != null && state.quickFilter !in quickFilters) state.quickFilter = null
    }
    val needles = listOfNotNull(state.categorySearch.trim().takeIf(String::isNotEmpty), state.quickFilter)
    val allEntries = rememberLiveTvCategoryEntries(uiState, viewModel, showRecent = true, unfoldAll = needles.isNotEmpty())
    val entries = remember(allEntries, needles) { filterCategoryEntries(allEntries, needles) }
    val entryKeys = remember(entries) { entries.map { it.key } }
    SideEffect { state.categoryKeys = entryKeys }

    val shownKey = filterKey + "\u0000" + query
    LaunchedEffect(shownKey, filtering) {
        if (filtering || state.shownKey == shownKey) return@LaunchedEffect
        val first = state.shownKey == null
        state.shownKey = shownKey
        if (!first) {
            state.channelListState.scrollToItem(0)
            state.focusedUrl = channels.firstOrNull()?.streamUrl
        }
        state.movingUrl = null
    }

    LaunchedEffect(channels, filtering) {
        if (filtering) return@LaunchedEffect
        val before = state.previousChannels
        state.previousChannels = channels
        val url = state.focusedUrl ?: return@LaunchedEffect
        if (channels.any { it.streamUrl == url }) return@LaunchedEffect
        val index = before.indexOfFirst { it.streamUrl == url }
        if (index < 0) return@LaunchedEffect
        val staying = channels.mapTo(HashSet(channels.size * 2)) { it.streamUrl }
        val neighbour = ((index + 1 until before.size).asSequence() + (index - 1 downTo 0).asSequence())
            .map { before[it].streamUrl }
            .firstOrNull(staying::contains)
        state.focusedUrl = neighbour
        if (!state.lastAreaChannels || state.categoriesFocused || dialogOpen) return@LaunchedEffect
        if (neighbour == null || !state.focusChannel(channels, neighbour)) state.focusCategory(entryKeys, filterKey)
    }

    val toChannels: () -> Boolean = {
        if (channels.isEmpty()) {
            false
        } else {
            scope.launch { state.focusChannel(channels, state.focusedUrl) }
            true
        }
    }
    val toCategories: () -> Unit = {
        scope.launch { state.focusCategory(entryKeys, filterKey) }
    }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LiveTvBrowseCategories(
            state = state,
            uiState = uiState,
            entries = entries,
            selectedKey = filterKey,
            quickFilters = quickFilters,
            onSelect = onSelectCategory,
            onPicked = { toChannels() },
            onToggleSource = { id, folded -> viewModel.collapsedSources[id] = !folded },
            onEditList = onEditList,
            onOpenGuide = onOpenGuide,
            onOpenSettings = onOpenSettings,
            onAddQuickFilter = onAddQuickFilter,
            onHiddenCategories = onHiddenCategories,
            onRight = toChannels,
            modifier = Modifier.width(264.dp).fillMaxHeight(),
        )
        LiveTvBrowseChannels(
            state = state,
            uiState = uiState,
            channels = channels,
            filtering = filtering,
            filterKey = filterKey,
            query = query,
            onQueryChange = onQueryChange,
            clock = clock,
            previewsEnabled = preview != null,
            onPlay = onPlay,
            onChannelOptions = onChannelOptions,
            onChoosePlaylist = onChoosePlaylist,
            onLeft = toCategories,
            modifier = Modifier.weight(1.15f).fillMaxHeight(),
        )
        LiveTvBrowsePreview(
            state = state,
            uiState = uiState,
            channels = channels,
            clock = clock,
            preview = preview,
            playVideo = playVideo,
            status = status,
            statusIsError = statusIsError,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

private fun filterCategoryEntries(entries: List<LiveTvCategoryEntry>, needles: List<String>): List<LiveTvCategoryEntry> {
    if (needles.isEmpty()) return entries
    fun matches(entry: LiveTvCategoryEntry) = needles.all { entry.label.contains(it, ignoreCase = true) }
    val result = ArrayList<LiveTvCategoryEntry>()
    var heading: LiveTvCategoryEntry? = null
    entries.forEach { entry ->
        when {
            entry.heading -> heading = entry
            matches(entry) -> {
                if (entry.indent) heading?.let {
                    result += it
                    heading = null
                }
                result += entry
            }
        }
    }
    return result
}

@Composable
private fun LiveTvBrowseCategories(
    state: LiveTvBrowseState,
    uiState: LiveTvUiState,
    entries: List<LiveTvCategoryEntry>,
    selectedKey: String,
    quickFilters: List<String>,
    onSelect: (String) -> Unit,
    onPicked: () -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
    onEditList: (String) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddQuickFilter: () -> Unit,
    onHiddenCategories: () -> Unit,
    onRight: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .liveTvPanel()
            .onFocusChanged {
                state.categoriesFocused = it.hasFocus
                if (it.hasFocus) state.lastAreaChannels = false
            }
            .padding(start = 14.dp, end = 14.dp, top = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.live_tv_categories),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LiveTvPillButton(
                text = "",
                icon = Icons.Outlined.Schedule,
                iconDescription = stringResource(R.string.live_tv_view_guide),
                onClick = onOpenGuide,
            )
            LiveTvPillButton(
                text = "",
                icon = Icons.Filled.Settings,
                iconDescription = stringResource(R.string.live_tv_settings),
                onClick = onOpenSettings,
            )
        }
        LiveTvTextField(
            value = state.categorySearch,
            onValueChange = { state.categorySearch = it },
            placeholder = stringResource(R.string.live_tv_search_categories),
            keyboardType = KeyboardType.Text,
            modifier = Modifier.fillMaxWidth().focusRequester(state.categorySearchFocus),
        )
        Spacer(Modifier.height(10.dp))
        LiveTvQuickFiltersCard(
            state = state,
            quickFilters = quickFilters,
            hiddenCount = uiState.hiddenGroups.size,
            onAdd = onAddQuickFilter,
            onHiddenCategories = onHiddenCategories,
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(if (uiState.isLoading) R.string.live_tv_loading else R.string.live_tv_no_categories_match),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(start = 6.dp, top = 12.dp),
            )
        }
        val selectedIndex = entries.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: entries.indexOfFirst { !it.heading }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec,
        ) {
            LazyColumn(
                state = state.categoryListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && onRight()
                    },
                contentPadding = PaddingValues(top = 2.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(entries, key = { _, it -> it.key }, contentType = { _, it -> if (it.heading) "heading" else "category" }) { index, entry ->
                    LiveTvBrowseCategoryRow(
                        entry = entry,
                        selected = entry.key == selectedKey,
                        anchor = index == selectedIndex,
                        focusRequester = state.categoryFocus,
                        holdSelection = state.holdCategory,
                        onClick = {
                            when {
                                entry.sourceId != null -> onToggleSource(entry.sourceId, entry.folded)
                                else -> {
                                    onSelect(entry.key)
                                    onPicked()
                                }
                            }
                        },
                        onLongClick = entry.listId?.let { id -> { onEditList(id) } },
                        onSelect = { if (entry.sourceId == null) onSelect(entry.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvQuickFiltersCard(
    state: LiveTvBrowseState,
    quickFilters: List<String>,
    hiddenCount: Int,
    onAdd: () -> Unit,
    onHiddenCategories: () -> Unit,
) {
    val context = LocalContext.current
    val search = state.categorySearch.trim()
    val subtitle = when {
        state.quickFilter != null -> stringResource(R.string.live_tv_quick_filters_active, state.quickFilter.orEmpty())
        search.isNotEmpty() -> stringResource(R.string.live_tv_quick_filters_search, search)
        else -> stringResource(R.string.live_tv_quick_filters_showing_all)
    }
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = { state.quickFiltersOpen = !state.quickFiltersOpen },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RowShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.05f),
            focusedContainerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.12f),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.TextPrimary), shape = RowShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.live_tv_quick_filters),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(if (state.quickFiltersOpen) R.string.live_tv_quick_filters_hide else R.string.live_tv_quick_filters_show),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    if (!state.quickFiltersOpen) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        ) {
            item(key = "\u0000all") {
                LiveTvPillButton(
                    text = stringResource(R.string.live_tv_quick_filters_all),
                    selected = state.quickFilter == null,
                    onClick = { state.quickFilter = null },
                )
            }
            items(quickFilters, key = { it }) { filter ->
                LiveTvChipButton(
                    text = filter,
                    selected = state.quickFilter == filter,
                    onClick = { state.quickFilter = if (state.quickFilter == filter) null else filter },
                    onLongClick = { LiveTvPreferences.removeQuickFilter(context, filter) },
                )
            }
        }
        Text(
            text = stringResource(if (quickFilters.isEmpty()) R.string.live_tv_quick_filters_empty else R.string.live_tv_quick_filters_saved),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary,
            modifier = Modifier.padding(start = 4.dp),
        )
        LiveTvPillButton(
            text = stringResource(R.string.live_tv_quick_filters_add),
            icon = Icons.Filled.Add,
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hiddenCount > 0) {
            LiveTvPillButton(
                text = stringResource(R.string.live_tv_hidden_categories_button, hiddenCount),
                onClick = onHiddenCategories,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LiveTvChipButton(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(LiveTvPillShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = if (selected) 0.14f else 0.06f),
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.10f)), shape = LiveTvPillShape),
            focusedBorder = Border.None,
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Color.Black else NuvioTheme.colors.TextPrimary.copy(alpha = 0.9f),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun LiveTvBrowseCategoryRow(
    entry: LiveTvCategoryEntry,
    selected: Boolean,
    anchor: Boolean,
    focusRequester: FocusRequester,
    holdSelection: MutableState<Boolean>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        if (holdSelection.value) {
            holdSelection.value = false
            return@LaunchedEffect
        }
        if (!selected && !entry.heading) {
            delay(250)
            onSelect()
        }
    }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = (if (anchor) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .padding(top = if (entry.heading) 8.dp else 0.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(CategoryShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary.copy(alpha = 0.10f) else Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.14f),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.TextPrimary), shape = CategoryShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        val content = when {
            focused || selected -> NuvioTheme.colors.TextPrimary
            entry.heading -> NuvioTheme.colors.TextTertiary
            else -> NuvioTheme.colors.TextSecondary
        }
        Row(
            modifier = Modifier.padding(start = if (entry.indent) 18.dp else 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.heading) {
                Icon(
                    imageVector = if (entry.folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                )
            }
            Text(
                text = entry.label,
                style = if (entry.heading) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected || entry.heading) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (entry.heading) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            entry.count?.let { count ->
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused || selected) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun liveTvCategoryTitle(filterKey: String, uiState: LiveTvUiState): String = when (val filter = filterFor(filterKey)) {
    LiveTvFilter.All -> stringResource(R.string.live_tv_all_channels)
    LiveTvFilter.Favorites -> stringResource(R.string.live_tv_favorites)
    LiveTvFilter.Recent -> stringResource(R.string.live_tv_recent)
    is LiveTvFilter.Custom -> uiState.customLists.firstOrNull { it.id == filter.id }?.name.orEmpty()
    is LiveTvFilter.Source -> uiState.sources.firstOrNull { it.id == filter.id }?.label.orEmpty()
    is LiveTvFilter.Group -> liveTvGroupLabel(filter.name, uiState.groupNames)
    is LiveTvFilter.SourceGroup -> liveTvGroupLabel(filter.name, uiState.groupNames)
}

@Composable
private fun LiveTvBrowseChannels(
    state: LiveTvBrowseState,
    uiState: LiveTvUiState,
    channels: List<LiveTvChannel>,
    filtering: Boolean,
    filterKey: String,
    query: String,
    onQueryChange: (String) -> Unit,
    clock: State<Long>,
    previewsEnabled: Boolean,
    onPlay: (LiveTvChannel) -> Unit,
    onChannelOptions: (LiveTvChannel) -> Unit,
    onChoosePlaylist: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = filterFor(filterKey)
    val customList = filter as? LiveTvFilter.Custom
    LaunchedEffect(state.typedNumber) {
        if (state.typedNumber.isEmpty()) return@LaunchedEffect
        delay(1_400)
        val number = state.typedNumber.toIntOrNull()
        state.typedNumber = ""
        val target = number?.let { channels.getOrNull(it - 1) } ?: return@LaunchedEffect
        state.focusChannel(channels, target.streamUrl)
        if (previewsEnabled) state.previewUrl = target.streamUrl
    }
    val onChannelKey: (KeyEvent, LiveTvChannel) -> Boolean = handler@{ event, channel ->
        val back = event.key == Key.Back || event.key == Key.Escape
        if (back) {
            if (event.type == KeyEventType.KeyUp) {
                when {
                    state.movingUrl != null -> state.movingUrl = null
                    state.picking -> state.stopPicking()
                    else -> onLeft()
                }
            }
            return@handler true
        }
        if (event.type != KeyEventType.KeyDown) return@handler false
        val moving = state.movingUrl
        if (moving != null && customList != null) {
            when (event.key) {
                Key.DirectionUp, Key.DirectionDown -> {
                    val step = if (event.key == Key.DirectionUp) -1 else 1
                    val shown = channels.mapTo(HashSet(channels.size * 2)) { it.streamUrl }
                    LiveTvRepository.moveInCustomList(customList.id, moving, step, shown::contains)
                    return@handler true
                }
                Key.DirectionLeft, Key.DirectionRight -> return@handler true
                else -> Unit
            }
        }
        val digit = liveTvDigit(event.key)
        if (digit != null) {
            if (state.typedNumber.length < 5) state.typedNumber += digit
            return@handler true
        }
        when (event.key) {
            Key.DirectionLeft -> {
                if (state.picking) onChoosePlaylist() else onLeft()
                true
            }
            Key.DirectionRight -> true
            else -> false
        }
    }
    LaunchedEffect(customList?.id, query) { if (customList == null || query.isNotBlank()) state.movingUrl = null }
    LaunchedEffect(state.movingUrl, channels) {
        val moving = state.movingUrl ?: return@LaunchedEffect
        val index = channels.indexOfFirst { it.streamUrl == moving }
        if (index < 0) return@LaunchedEffect
        val visible = state.channelListState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index } || visible.first().index == index || visible.last().index == index) {
            state.channelListState.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
        withFrameNanos { }
        runCatching { state.channelFocus.requestFocus() }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged {
                    state.channelsFocused = it.hasFocus
                    if (it.hasFocus) state.lastAreaChannels = true
                },
        ) {
            Text(
                text = liveTvCategoryTitle(filterKey, uiState),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.live_tv_channels_in_view, channels.size, channels.size),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            LiveTvTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.live_tv_search_channels),
                keyboardType = KeyboardType.Text,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (channels.isEmpty() && !filtering && uiState.isLoaded && !uiState.isLoading) {
                Text(
                    text = when {
                        filter == LiveTvFilter.Favorites -> stringResource(R.string.live_tv_no_favorites)
                        filter is LiveTvFilter.Custom && query.isBlank() -> stringResource(R.string.live_tv_playlist_empty)
                        else -> stringResource(R.string.live_tv_no_channels_found)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp).widthIn(max = 520.dp),
                )
            } else if (channels.isEmpty()) {
                Text(
                    text = stringResource(R.string.live_tv_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp),
                )
            }
            val anchorIndex = remember(channels, state.focusedUrl) {
                channels.indexOfFirst { it.streamUrl == state.focusedUrl }.takeIf { it >= 0 } ?: 0
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec,
            ) {
                LazyColumn(
                    state = state.channelListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp, start = 2.dp, end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(channels, key = { _, channel -> channel.id }, contentType = { _, _ -> "channel" }) { index, channel ->
                        LiveTvBrowseChannelRow(
                            channel = channel,
                            number = index + 1,
                            logo = uiState.logoFor(channel),
                            programme = uiState.currentProgrammes[channel.guideKey],
                            favorite = channel.streamUrl in uiState.favoriteUrls,
                            previewing = previewsEnabled && state.previewUrl == channel.streamUrl,
                            picked = if (state.picking) channel.streamUrl in state.picked else null,
                            moving = state.movingUrl == channel.streamUrl,
                            clock = clock,
                            modifier = (if (index == anchorIndex) Modifier.focusRequester(state.channelFocus) else Modifier)
                                .onPreviewKeyEvent { onChannelKey(it, channel) },
                            onFocused = { state.focusedUrl = channel.streamUrl },
                            onClick = {
                                when {
                                    state.movingUrl != null -> state.movingUrl = null
                                    state.picking -> state.togglePicked(channel)
                                    previewsEnabled && state.previewUrl != channel.streamUrl -> state.previewUrl = channel.streamUrl
                                    else -> onPlay(channel)
                                }
                            },
                            onLongClick = {
                                when {
                                    state.movingUrl != null -> state.movingUrl = null
                                    state.picking -> state.togglePicked(channel)
                                    else -> onChannelOptions(channel)
                                }
                            },
                        )
                    }
                }
            }
        }
        when {
            state.movingUrl != null -> LiveTvBrowseBar(
                text = stringResource(R.string.live_tv_list_moving_keys),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            state.picking -> LiveTvBrowseBar(
                title = pluralStringResource(R.plurals.live_tv_picked, state.picked.size, state.picked.size),
                text = stringResource(R.string.live_tv_list_picking_keys),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (state.typedNumber.isNotEmpty()) {
            Text(
                text = state.typedNumber,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(LiveTvCardBackground)
                    .border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

private fun liveTvDigit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

@Composable
private fun LiveTvBrowseBar(text: String, modifier: Modifier = Modifier, title: String? = null) {
    Column(
        modifier = modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 10.dp)
            .fillMaxWidth()
            .background(Color(0xFF121214), RoundedCornerShape(16.dp))
            .border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveTvBrowseChannelRow(
    channel: LiveTvChannel,
    number: Int,
    logo: String?,
    programme: LiveTvProgramme?,
    favorite: Boolean,
    previewing: Boolean,
    picked: Boolean?,
    moving: Boolean,
    clock: State<Long>,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val catchup = channel.catchup != null
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(RowShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = if (previewing || moving) 0.09f else 0.045f),
            focusedContainerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.13f),
        ),
        border = CardDefaults.border(
            border = Border(
                BorderStroke(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = if (moving) 0.6f else 0.06f)),
                shape = RowShape,
            ),
            focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.TextPrimary), shape = RowShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (picked) NuvioTheme.colors.TextPrimary else Color.Transparent)
                        .border(1.5.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) {
                        Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                }
            }
            LiveTvLogo(url = logo, name = channel.name, width = 68.dp, height = 52.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = number.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    text = programme?.title ?: stringResource(R.string.live_tv_no_schedule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (programme != null) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (programme != null && programme.startEpochMs <= clock.value && clock.value < programme.stopEpochMs) {
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = NuvioTheme.colors.TextPrimary.copy(alpha = 0.85f),
                        track = NuvioTheme.colors.TextPrimary.copy(alpha = 0.10f),
                        modifier = Modifier.padding(top = 5.dp).fillMaxWidth(),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (previewing) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    if (favorite) {
                        Icon(imageVector = Icons.Filled.Star, contentDescription = null, tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(16.dp))
                    }
                }
                if (catchup) LiveTvBadge(stringResource(R.string.live_tv_catchup))
            }
        }
    }
}

@Composable
private fun LiveTvBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = modifier
            .border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.24f), LiveTvPillShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LiveTvBrowsePreview(
    state: LiveTvBrowseState,
    uiState: LiveTvUiState,
    channels: List<LiveTvChannel>,
    clock: State<Long>,
    preview: LiveTvPreviewPlayer?,
    playVideo: Boolean,
    status: String?,
    statusIsError: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewChannel = remember(state.previewUrl, channels, uiState.channels) {
        val url = state.previewUrl ?: return@remember null
        channels.firstOrNull { it.streamUrl == url } ?: uiState.channels.firstOrNull { it.streamUrl == url }
    }
    val focusedChannel = remember(state.focusedUrl, channels) { channels.firstOrNull { it.streamUrl == state.focusedUrl } }
    val shown = if (preview != null) previewChannel ?: focusedChannel else focusedChannel ?: previewChannel
    val programme = shown?.guideKey?.let(uiState.currentProgrammes::get)
    val next = remember(shown?.guideKey, programme, clock.value / 60_000L, uiState.guideVersion) {
        shown?.guideKey?.let { LiveTvRepository.nextProgramme(it) }
    }
    Column(
        modifier = modifier
            .liveTvPanel()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.live_tv_channel_preview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (preview != null) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                LiveTvPreviewVideo(
                    preview = preview,
                    channel = previewChannel,
                    logo = previewChannel?.let(uiState::logoFor),
                    playVideo = playVideo && previewChannel != null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (previewChannel == null) {
                    Text(
                        text = stringResource(R.string.live_tv_preview_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    )
                } else if (LiveTvRepository.isStalker(previewChannel)) {
                    Text(
                        text = stringResource(R.string.live_tv_preview_unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NuvioTheme.colors.BackgroundElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (shown != null) LiveTvLogo(url = uiState.logoFor(shown), name = shown.name, width = 128.dp, height = 76.dp)
            }
        }
        if (shown != null) {
            Text(
                text = shown.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (programme != null) {
                val now = clock.value
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (programme.startEpochMs < now && LiveTvCatchupLinks.isPlayable(shown.catchup, programme, now)) {
                        LiveTvBadge(stringResource(R.string.live_tv_catchup), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                val timing = listOfNotNull(
                    LiveTvClock.formatSpan(programme),
                    if (programme.startEpochMs <= now && now < programme.stopEpochMs) liveTvTimeLeft(programme, clock) else null,
                ).joinToString("  ·  ")
                Text(
                    text = timing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (programme.startEpochMs <= now && now < programme.stopEpochMs) {
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = NuvioTheme.colors.TextPrimary,
                        track = NuvioTheme.colors.TextPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    )
                }
                programme.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp).weight(1f, fill = false),
                    )
                }
                next?.let {
                    Text(
                        text = stringResource(R.string.live_tv_next_title, it.title) + "  ·  " + LiveTvClock.formatClock(it.startEpochMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.live_tv_no_schedule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val hint = when {
                preview == null -> R.string.live_tv_preview_ok_hint
                previewChannel != null && previewChannel.streamUrl == (focusedChannel?.streamUrl ?: previewChannel.streamUrl) ->
                    R.string.live_tv_preview_open_hint
                else -> R.string.live_tv_preview_select_hint
            }
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

internal fun applyLiveTvPlaylistChoice(key: String, channels: List<LiveTvChannel>, uiState: LiveTvUiState) {
    if (channels.isEmpty()) return
    val custom = filterFor(key) as? LiveTvFilter.Custom
    val inIt: Set<String> = if (custom != null) {
        uiState.customLists.firstOrNull { it.id == custom.id }?.urls?.toHashSet().orEmpty()
    } else {
        uiState.favoriteUrls
    }
    val removing = channels.all { it.streamUrl in inIt }
    when {
        custom != null && removing -> LiveTvRepository.removeFromCustomList(custom.id, channels)
        custom != null -> LiveTvRepository.addToCustomList(custom.id, channels)
        removing -> LiveTvRepository.removeFavorites(channels)
        else -> LiveTvRepository.addFavorites(channels)
    }
}

@Composable
internal fun LiveTvPlaylistChooserDialog(
    channels: List<LiveTvChannel>,
    uiState: LiveTvUiState,
    onNew: () -> Unit,
    onChosen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val picked = remember(channels) { channels.map { it.streamUrl } }
    fun hasAll(urls: Collection<String>) = picked.isNotEmpty() && picked.all(urls::contains)
    val removeFrom = stringResource(R.string.live_tv_remove_from_list)
    val favorites = stringResource(R.string.live_tv_favorites)
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_choose_playlist),
        subtitle = if (channels.size == 1) channels.first().name else pluralStringResource(R.plurals.live_tv_picked, channels.size, channels.size),
        width = 520.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.sm,
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            item(key = "\u0000favorites") {
                val all = hasAll(uiState.favoriteUrls)
                LiveTvOptionRow(
                    title = if (all) removeFrom.format(favorites) else favorites,
                    icon = if (all) Icons.Filled.Remove else Icons.Filled.Star,
                    onClick = {
                        applyLiveTvPlaylistChoice(FILTER_FAVORITES, channels, uiState)
                        onChosen()
                    },
                    modifier = Modifier.focusRequester(first),
                )
            }
            items(uiState.customLists, key = { it.id }) { list ->
                val all = hasAll(list.urls)
                LiveTvOptionRow(
                    title = if (all) removeFrom.format(list.name) else list.name,
                    icon = if (all) Icons.Filled.Remove else Icons.Filled.Add,
                    onClick = {
                        applyLiveTvPlaylistChoice(FILTER_LIST_PREFIX + list.id, channels, uiState)
                        onChosen()
                    },
                )
            }
            item(key = "\u0000new") {
                LiveTvOptionRow(
                    title = stringResource(R.string.live_tv_option_new_playlist),
                    icon = Icons.Filled.Add,
                    onClick = onNew,
                )
            }
        }
    }
}

@Composable
internal fun LiveTvChannelOptionsDialog(
    channel: LiveTvChannel,
    uiState: LiveTvUiState,
    filterKey: String,
    canMove: Boolean,
    onWatch: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPickSeveral: () -> Unit,
    onMove: () -> Unit,
    onShowInGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val filter = filterFor(filterKey)
    val custom = (filter as? LiveTvFilter.Custom)?.let { f -> uiState.customLists.firstOrNull { it.id == f.id } }
    val favorite = channel.streamUrl in uiState.favoriteUrls
    val programme = uiState.currentProgrammes[channel.guideKey]
    NuvioDialog(
        onDismiss = onDismiss,
        title = channel.name,
        subtitle = programme?.title,
        width = 520.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.sm,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            LiveTvOptionRow(
                title = stringResource(R.string.live_tv_option_watch),
                icon = Icons.Filled.PlayArrow,
                onClick = onWatch,
                modifier = Modifier.focusRequester(first),
            )
            LiveTvOptionRow(
                title = stringResource(if (favorite) R.string.live_tv_favorite_remove else R.string.live_tv_favorite_add),
                icon = if (favorite) Icons.Filled.Remove else Icons.Filled.Star,
                onClick = {
                    LiveTvRepository.toggleFavorite(channel)
                    onDismiss()
                },
            )
            LiveTvOptionRow(
                title = stringResource(R.string.live_tv_option_add_playlist),
                icon = Icons.Filled.Add,
                onClick = onAddToPlaylist,
            )
            if (custom != null) {
                LiveTvOptionRow(
                    title = stringResource(R.string.live_tv_remove_from_list, custom.name),
                    icon = Icons.Filled.Remove,
                    onClick = {
                        LiveTvRepository.removeFromCustomList(custom.id, channel.streamUrl)
                        onDismiss()
                    },
                )
                if (canMove) {
                    LiveTvOptionRow(
                        title = stringResource(R.string.live_tv_option_move),
                        onClick = onMove,
                    )
                }
            }
            LiveTvOptionRow(
                title = stringResource(R.string.live_tv_option_pick_several),
                icon = Icons.Filled.Check,
                onClick = onPickSeveral,
            )
            LiveTvOptionRow(
                title = stringResource(R.string.live_tv_option_show_in_guide),
                icon = Icons.Outlined.Schedule,
                onClick = onShowInGuide,
            )
            if (filter == LiveTvFilter.Recent) {
                LiveTvOptionRow(
                    title = stringResource(R.string.live_tv_option_remove_recent),
                    icon = Icons.Filled.Remove,
                    onClick = {
                        LiveTvRepository.removeRecentChannel(channel)
                        onDismiss()
                    },
                )
                LiveTvOptionRow(
                    title = stringResource(R.string.live_tv_option_clear_recent),
                    onClick = {
                        LiveTvRepository.clearRecentChannels()
                        onDismiss()
                    },
                )
            }
            LiveTvOptionRow(
                title = stringResource(R.string.live_tv_option_hide_channel),
                onClick = {
                    LiveTvRepository.setChannelsHidden(listOf(channel), true)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun LiveTvOptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.06f),
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val content = if (focused) Color.Black else NuvioTheme.colors.TextPrimary
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.padding(end = 12.dp).size(18.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LiveTvQuickFilterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var word by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val save: () -> Unit = {
        if (word.isNotBlank()) {
            LiveTvPreferences.addQuickFilter(context, word)
            onDismiss()
        }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_quick_filter_new_title),
        subtitle = stringResource(R.string.live_tv_quick_filter_new_description),
        width = 520.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md,
    ) {
        LiveTvTextField(
            value = word,
            onValueChange = { word = it },
            placeholder = stringResource(R.string.live_tv_quick_filter_hint),
            keyboardType = KeyboardType.Text,
            onDone = save,
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
        ) {
            LiveTvPillButton(text = stringResource(R.string.live_tv_cancel), onClick = onDismiss)
            LiveTvPillButton(text = stringResource(R.string.live_tv_quick_filter_save), onClick = save, enabled = word.isNotBlank())
        }
    }
}

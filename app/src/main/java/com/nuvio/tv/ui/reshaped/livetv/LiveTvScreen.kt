@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.text.format.DateFormat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LIVE_TV_UNGROUPED
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvCustomList
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.rememberLiveTvPreviewSoundEnabled
import com.nuvio.tv.reshaped.livetv.rememberLiveTvPreviewsEnabled
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The filter shown in the category column. */
internal sealed interface LiveTvFilter {
    data object All : LiveTvFilter
    data object Favorites : LiveTvFilter
    data object Recent : LiveTvFilter
    /** One of the viewer's own playlists. */
    data class Custom(val id: String) : LiveTvFilter
    data class Source(val id: String) : LiveTvFilter
    data class Group(val name: String) : LiveTvFilter
    /** One category of one source, picked under that source's heading. */
    data class SourceGroup(val id: String, val name: String) : LiveTvFilter
}

internal const val FILTER_ALL = "\u0000all"
internal const val FILTER_FAVORITES = "\u0000favorites"
internal const val FILTER_RECENT = "\u0000recent"
internal const val FILTER_SOURCE_PREFIX = "\u0000source:"
internal const val FILTER_LIST_PREFIX = "\u0000list:"
internal const val FILTER_SOURCE_GROUP_PREFIX = "\u0000sourcegroup:"

internal fun sourceGroupKey(sourceId: String, group: String): String = "$FILTER_SOURCE_GROUP_PREFIX$sourceId\u0000$group"

/**
 * The channels a filter key shows: hidden categories and hidden channels leave everything but
 * favorites, and a search matches names. Slow for big lists: call off the main thread.
 */
internal fun filterChannels(
    channels: List<LiveTvChannel>,
    favorites: Set<String>,
    hidden: Set<String>,
    hiddenChannels: Set<Long>,
    key: String,
    query: String = "",
    customLists: List<LiveTvCustomList> = emptyList(),
    recentUrls: List<String> = emptyList(),
): List<LiveTvChannel> {
    val filter = filterFor(key)
    val needle = query.trim()
    if (filter == LiveTvFilter.Recent) {
        val wanted = recentUrls.toHashSet()
        val byUrl = HashMap<String, LiveTvChannel>(recentUrls.size * 2)
        channels.forEach { if (it.streamUrl in wanted) byUrl.putIfAbsent(it.streamUrl, it) }
        return recentUrls.mapNotNull(byUrl::get).filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
    }
    if (filter is LiveTvFilter.Custom) {
        // In the viewer's order; a channel its source no longer lists is left out.
        // Once each: a synced list naming a channel twice would repeat a row key.
        val urls = customLists.firstOrNull { it.id == filter.id }?.urls?.distinct() ?: return emptyList()
        val wanted = urls.toHashSet()
        val byUrl = HashMap<String, LiveTvChannel>(urls.size * 2)
        channels.forEach { if (it.streamUrl in wanted) byUrl.putIfAbsent(it.streamUrl, it) }
        return urls.mapNotNull(byUrl::get).filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
    }
    return channels.filter { channel ->
        when (filter) {
            LiveTvFilter.All -> channel.group !in hidden && channel.hideKey !in hiddenChannels
            LiveTvFilter.Favorites -> channel.streamUrl in favorites
            LiveTvFilter.Recent -> false
            is LiveTvFilter.Custom -> false
            is LiveTvFilter.Source -> channel.sourceId == filter.id && channel.group !in hidden && channel.hideKey !in hiddenChannels
            is LiveTvFilter.Group -> channel.group == filter.name && channel.hideKey !in hiddenChannels
            is LiveTvFilter.SourceGroup ->
                channel.sourceId == filter.id && channel.group == filter.name && channel.hideKey !in hiddenChannels
        } && (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true))
    }
}

/** The category Live TV opens on: All channels, else (when that is hidden) the first category the list shows. */
internal fun liveTvHomeFilter(uiState: com.nuvio.tv.reshaped.livetv.LiveTvUiState, showAll: Boolean, showFavorites: Boolean): String = when {
    showAll -> FILTER_ALL
    showFavorites && uiState.favoriteUrls.isNotEmpty() -> FILTER_FAVORITES
    uiState.customLists.isNotEmpty() -> FILTER_LIST_PREFIX + uiState.customLists.first().id
    uiState.sources.size > 1 -> FILTER_SOURCE_PREFIX + uiState.sources.first().id
    else -> uiState.visibleGroups.firstOrNull() ?: FILTER_ALL
}

internal fun filterFor(key: String): LiveTvFilter = when {
    key == FILTER_ALL -> LiveTvFilter.All
    key == FILTER_FAVORITES -> LiveTvFilter.Favorites
    key == FILTER_RECENT -> LiveTvFilter.Recent
    key.startsWith(FILTER_LIST_PREFIX) -> LiveTvFilter.Custom(key.removePrefix(FILTER_LIST_PREFIX))
    key.startsWith(FILTER_SOURCE_PREFIX) -> LiveTvFilter.Source(key.removePrefix(FILTER_SOURCE_PREFIX))
    key.startsWith(FILTER_SOURCE_GROUP_PREFIX) -> key.removePrefix(FILTER_SOURCE_GROUP_PREFIX).let {
        LiveTvFilter.SourceGroup(it.substringBefore('\u0000'), it.substringAfter('\u0000'))
    }
    else -> LiveTvFilter.Group(key)
}


/**
 * Live TV, laid out as TV channel guides are: what is selected on top (picture, channel, programme,
 * time left, description) with the live preview on the right; below, the categories on the left
 * (search, favorites, each source's categories under its own heading) and the programme guide.
 * The guide draws only the rows and programmes in view, so thousands of channels stay light.
 */
@Composable
fun LiveTvScreen(
    onPlay: (String) -> Unit,
    showBuiltInHeader: Boolean = true,
    viewModel: LiveTvScreenModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // Before the state is read, so a list let go while unused shows as loading, never as empty.
    remember(viewModel) { viewModel.ensureLoaded() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        // Back from the background after a long while: the list may have been let go meanwhile.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                LiveTvClock.followDeviceHourFormat(context)
                viewModel.ensureLoaded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    // A release that raced the screen coming back leaves an empty, idle state: load again.
    LaunchedEffect(uiState.isLoaded, uiState.isLoading, uiState.hasSource) {
        if (!uiState.isLoaded && !uiState.isLoading && !uiState.hasSource) viewModel.ensureLoaded()
    }
    val scope = rememberCoroutineScope()
    var filterKey by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    val previewsEnabled = rememberLiveTvPreviewsEnabled()
    val listView by LiveTvPreferences.listView.collectAsStateWithLifecycle()
    val browse = viewModel.browse
    var optionsChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    var chooserChannels by remember { mutableStateOf<List<LiveTvChannel>?>(null) }
    var namingChannels by remember { mutableStateOf<List<LiveTvChannel>?>(null) }
    var addingQuickFilter by remember { mutableStateOf(false) }
    val preview = rememberLiveTvPreviewPlayer()
    var gridFocused by remember { mutableStateOf(false) }
    var settingsFocused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    // Naming a new playlist (from "+ New playlist" in the categories).
    var naming by remember { mutableStateOf(false) }
    // Categories, search and settings slide in beside the channels on ◀ (or Back), and away again
    // once the guide has focus, so the guide has the whole width the rest of the time.
    var categoriesOpen by remember { mutableStateOf(false) }
    // A playlist opened to rename, reorder or delete (hold OK on it in the categories).
    var editingList by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    // Under the pill menu the screen starts below it, as Settings does, so the pill never covers the header.
    val topPadding = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp
    val gridFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    // A category that was hidden, or a source that was removed, falls back to all channels.
    // Categories show under their source's heading only with several sources: a category picked
    // the other way follows (or falls back to all channels when that is not possible).
    // With All channels (or Favorites) hidden in the settings, the first category shown stands in for it.
    val showAll by LiveTvPreferences.showAll.collectAsStateWithLifecycle()
    val showFavorites by LiveTvPreferences.showFavorites.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.isLoaded, uiState.hiddenGroups, uiState.sources, uiState.customLists, showAll, showFavorites, uiState.favoriteUrls.isEmpty()) {
        // Reloading (Live TV let go while unused): sources and playlists are not read back yet,
        // and the category picked before must not fall back to all channels meanwhile.
        if (!uiState.isLoaded) return@LaunchedEffect
        val multiSource = uiState.sources.size > 1
        val home = liveTvHomeFilter(uiState, showAll, showFavorites)
        filterKey = when (val current = filterFor(filterKey)) {
            is LiveTvFilter.Group -> when {
                current.name in uiState.hiddenGroups -> home
                multiSource -> home
                else -> filterKey
            }
            is LiveTvFilter.Source -> if (uiState.sources.none { it.id == current.id }) home else filterKey
            is LiveTvFilter.Custom -> if (uiState.customLists.none { it.id == current.id }) home else filterKey
            is LiveTvFilter.SourceGroup -> when {
                current.name in uiState.hiddenGroups || uiState.sources.none { it.id == current.id } -> home
                !multiSource -> current.name
                else -> filterKey
            }
            LiveTvFilter.All -> if (showAll) filterKey else home
            LiveTvFilter.Favorites -> if (showFavorites) filterKey else home
            else -> filterKey
        }
    }

    val filter = filterFor(filterKey)
    // Filtered off the main thread: lists can hold tens of thousands of channels.
    val filterInput = LiveTvFilterInput(uiState.channels, uiState.favoriteUrls, uiState.hiddenGroups, uiState.hiddenChannelKeys, filterKey, query, uiState.customLists, uiState.recentUrls)
    val visibleChannels = viewModel.visibleChannels
    val filtering = !viewModel.isFilteredFor(filterInput)
    LaunchedEffect(uiState.channels, uiState.favoriteUrls, uiState.hiddenGroups, uiState.hiddenChannelKeys, filterKey, query, uiState.customLists, uiState.recentUrls) {
        if (viewModel.isFilteredFor(filterInput)) return@LaunchedEffect
        if (query.isNotEmpty()) delay(200) // typing
        val filtered = withContext(Dispatchers.Default) {
            filterChannels(
                filterInput.channels, filterInput.favoriteUrls, filterInput.hiddenGroups, filterInput.hiddenChannels,
                filterInput.filterKey, filterInput.query, filterInput.customLists, filterInput.recentUrls,
            )
        }
        viewModel.setVisible(filterInput, filtered)
    }

    val minuteClock = rememberLiveTvMinuteClock()
    // Stays set until the player has taken over the screen, so the preview can't start again
    // beside it during the navigation.
    LaunchedEffect(started) { if (!started) launching = false }

    /** Plays [channel] live, or its past [programme] (catch-up) when one is given. */
    val launchPlay: (LiveTvChannel, LiveTvProgramme?) -> Unit = { channel, programme ->
        if (!launching) {
            launching = true
            // The player needs the decoder and, with one-connection providers, the connection.
            preview.release()
            scope.launch {
                try {
                    val list = visibleChannels.takeIf { list -> list.any { it.streamUrl == channel.streamUrl } }.orEmpty()
                    // The player's categories list each category once: a source's category opens as that category.
                    val folder = (filterFor(filterKey) as? LiveTvFilter.SourceGroup)?.name ?: filterKey
                    LiveTvRepository.setZapList(list, folderKey = folder.takeIf { query.isBlank() && folder != FILTER_RECENT })
                    val route = if (programme != null) {
                        liveTvCatchupRoute(channel, programme, viewModel.profileId)
                    } else {
                        liveTvPlayerRoute(channel, viewModel.profileId)
                    }
                    if (route == null) {
                        launching = false
                        android.widget.Toast.makeText(context, R.string.live_tv_catchup_failed, android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    viewModel.restoreFocusOnReturn = true
                    viewModel.launchedUrl = channel.streamUrl
                    onPlay(route)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    launching = false
                    throw cancel
                } catch (error: Exception) {
                    // Shown as a failed play, never a closed app.
                    launching = false
                    android.util.Log.w("LiveTv", "Could not start playback", error)
                    android.widget.Toast.makeText(context, R.string.live_tv_catchup_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val currentLaunchPlay by rememberUpdatedState(launchPlay)

    val categoryList = rememberLazyListState()
    // Focus put on a category by the guide (Back, ◀) does not pick it: only the viewer's moves do.
    val holdCategory = remember { mutableStateOf(false) }
    /** Opens the categories and focuses [target] there once they are composed. */
    val openCategories: (FocusRequester) -> Unit = { target ->
        categoriesOpen = true
        scope.launch {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { target.requestFocus() }.isSuccess) return@launch
            }
            // The selected category is out of view: brought into view first.
            val index = if (target === categoryFocus) viewModel.categoryKeys.indexOf(filterKey) else 0
            if (index >= 0) categoryList.scrollToItem((index - 3).coerceAtLeast(0))
            repeat(5) {
                withFrameNanos { }
                if (runCatching { target.requestFocus() }.isSuccess) return@launch
            }
            // Still nowhere to land (the category is gone): the settings button, at the top.
            if (target !== settingsFocus && runCatching { settingsFocus.requestFocus() }.isSuccess) return@launch
            // Never leave the panel open with the focus still in the guide.
            categoriesOpen = false
        }
    }
    val toCategories: () -> Unit = {
        holdCategory.value = true
        openCategories(categoryFocus)
    }
    val toGuide: () -> Boolean = {
        visibleChannels.isNotEmpty() && runCatching { gridFocus.requestFocus() }.isSuccess
    }
    val currentToCategories by rememberUpdatedState(toCategories)
    // Unfavouriting a channel in Favorites removes its row: the guide moves to the next one, or
    // to the categories when none is left.
    var keepAfterRefilter by remember { mutableStateOf<String?>(null) }
    // Set once the guide exists (it is made below).
    var onGuideLongPress: (LiveTvChannel) -> Unit = {}
    val guide = remember {
        LiveTvGuideState(
            channels = emptyList(),
            startIndex = 0,
            onPlay = { channel -> currentLaunchPlay(channel, null) },
            onCatchup = { channel, programme -> currentLaunchPlay(channel, programme) },
            // Back, and ◀ from what is on now, go to the categories, as in TV guides: to the one
            // selected, or (scrolled out of view) the nearest one.
            onClose = { currentToCategories() },
            onExitLeft = { currentToCategories() },
            // ▲ from the first channel stays there: the categories open only on ◀ or Back.
            onExitUp = {},
            // Held OK: in a playlist of the viewer's, moves the channel; elsewhere starts picking
            // channels for favorites or a playlist.
            onLongPress = { channel -> onGuideLongPress(channel) },
            leadMs = 0L,
        )
    }
    onGuideLongPress = { channel ->
        val shownList = filterFor(filterKey) as? LiveTvFilter.Custom
        when {
            // Held while picking: ticks, as a press would.
            guide.selecting -> guide.togglePicked(channel)
            shownList != null && query.isBlank() -> guide.startMoving()
            else -> guide.startSelecting(channel)
        }
    }
    guide.onReorder = { channel, step ->
        (filterFor(filterKey) as? LiveTvFilter.Custom)?.let { list ->
            // Steps over the channels shown, not ones no source lists now.
            val shown = visibleChannels.mapTo(HashSet(visibleChannels.size * 2)) { it.streamUrl }
            LiveTvRepository.moveInCustomList(list.id, channel.streamUrl, step, shown::contains)
        }
    }
    LaunchedEffect(filterKey, query) { guide.stopMoving() }
    val keepNeighbour: (LiveTvChannel) -> Unit = { channel ->
        val index = visibleChannels.indexOfFirst { it.streamUrl == channel.streamUrl }
        // Several picked channels going at once: the nearest one staying, after it first.
        val staying = { candidate: LiveTvChannel -> candidate.streamUrl !in guide.picked }
        keepAfterRefilter = (
            (index + 1 until visibleChannels.size).asSequence().map(visibleChannels::get).firstOrNull(staying)
                ?: (index - 1 downTo 0).asSequence().map(visibleChannels::get).firstOrNull(staying)
            )?.streamUrl
    }
    /**
     * A list chosen while picking: the picked channels go in (or, when all of them are in it
     * already, come out), and the guide is as before.
     */
    val choosePlaylist: (String) -> Unit = { key ->
        val picked = guide.picked.values.toList()
        if (key == NEW_LIST_KEY) {
            naming = true
        } else {
            val custom = filterFor(key) as? LiveTvFilter.Custom
            val inIt: Set<String> = if (custom != null) {
                uiState.customLists.firstOrNull { it.id == custom.id }?.urls?.toHashSet().orEmpty()
            } else {
                uiState.favoriteUrls
            }
            val removing = picked.isNotEmpty() && picked.all { it.streamUrl in inIt }
            // Taking the selected channel out of the list on screen: the guide moves to its neighbour.
            if (removing && key == filterKey) guide.channel?.takeIf { it.streamUrl in guide.picked }?.let(keepNeighbour)
            when {
                custom != null && removing -> LiveTvRepository.removeFromCustomList(custom.id, picked)
                custom != null -> LiveTvRepository.addToCustomList(custom.id, picked)
                removing -> LiveTvRepository.removeFavorites(picked)
                else -> LiveTvRepository.addFavorites(picked)
            }
            guide.stopSelecting()
            toGuide()
        }
    }
    // Another category or search brings the guide back to now; the same one filtered again
    // (a favourite, a hidden channel) stays where it was.
    var shownFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visibleChannels) {
        val shownKey = filterKey + "\u0000" + query
        val keep = keepAfterRefilter ?: guide.channel?.streamUrl ?: uiState.recentChannel?.streamUrl
        keepAfterRefilter = null
        guide.showChannels(visibleChannels, keepUrl = keep, toNow = shownFor != shownKey)
        shownFor = shownKey
        if (visibleChannels.isEmpty() && gridFocused) currentToCategories()
    }
    // What is on now stays selected as time passes, and again after the app comes back.
    LaunchedEffect(guide) { snapshotFlow { minuteClock.value }.collect { guide.followNow() } }
    LaunchedEffect(started) { if (started) guide.backToNow() }

    // Back from the player: the guide on the channel last watched, focused.
    LaunchedEffect(visibleChannels, filtering) {
        if (!viewModel.restoreFocusOnReturn || filtering || visibleChannels.isEmpty()) return@LaunchedEffect
        viewModel.restoreFocusOnReturn = false
        if (listView) {
            val url = uiState.recentChannel?.streamUrl?.takeIf { url -> visibleChannels.any { it.streamUrl == url } } ?: viewModel.launchedUrl
            if (previewsEnabled) browse.previewUrl = url
            browse.focusChannel(visibleChannels, url)
            return@LaunchedEffect
        }
        // Zapped into another category meanwhile: the channel it was started from.
        val recent = visibleChannels.indexOfFirst { it.streamUrl == uiState.recentChannel?.streamUrl }
        guide.selectRow(if (recent >= 0) recent else visibleChannels.indexOfFirst { it.streamUrl == viewModel.launchedUrl }.coerceAtLeast(0))
        guide.backToNow()
        repeat(10) {
            withFrameNanos { }
            if (runCatching { gridFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // Focus gone from Live TV altogether (▲ from the categories to the menu bar): the preview stops.
    var screenFocused by remember { mutableStateOf(false) }
    val anyDialogOpen = showSourceDialog || showCategoryDialog || showPlaylists || editingList != null || naming ||
        showMenu || optionsChannel != null || chooserChannels != null || namingChannels != null || addingQuickFilter
    val setListView: (Boolean) -> Unit = { list ->
        LiveTvPreferences.setListView(context, list)
        categoriesOpen = false
        browse.stopPicking()
        browse.movingUrl = null
        guide.stopSelecting()
        scope.launch {
            if (list) {
                if (!browse.focusChannel(visibleChannels, guide.channel?.streamUrl ?: browse.focusedUrl)) {
                    browse.focusCategory(browse.categoryKeys, filterKey)
                }
            } else {
                val index = visibleChannels.indexOfFirst { it.streamUrl == browse.focusedUrl }
                if (index >= 0) guide.selectRow(index)
                guide.backToNow()
                repeat(10) {
                    withFrameNanos { }
                    if (toGuide()) return@launch
                }
                openCategories(categoryFocus)
            }
        }
    }
    val showInGuide: (LiveTvChannel) -> Unit = { channel ->
        browse.focusedUrl = channel.streamUrl
        setListView(false)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onFocusChanged { screenFocused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Guide) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) setListView(!listView)
                true
            },
    ) {
        if (!uiState.isLoaded && !uiState.isLoading && !uiState.hasSource) {
            LiveTvEmptyState(onAddSource = { showSourceDialog = true })
        } else if (listView) {
            val failedSource = uiState.sources.firstOrNull { it.id in uiState.sourceErrors }
            val status = when {
                uiState.isLoading -> stringResource(R.string.live_tv_loading)
                failedSource != null -> stringResource(
                    R.string.live_tv_source_error,
                    failedSource.label,
                    uiState.sourceErrors[failedSource.id]?.message(context).orEmpty(),
                )
                uiState.error != null -> uiState.error?.message(context)
                uiState.isEpgLoading -> stringResource(R.string.live_tv_guide_loading)
                else -> null
            }
            LiveTvBrowseLayout(
                state = browse,
                uiState = uiState,
                viewModel = viewModel,
                channels = visibleChannels,
                filtering = filtering,
                filterKey = filterKey,
                onSelectCategory = { filterKey = it },
                query = query,
                onQueryChange = { query = it },
                clock = minuteClock,
                preview = if (previewsEnabled) preview else null,
                playVideo = screenFocused && started && !launching && !anyDialogOpen,
                dialogOpen = anyDialogOpen,
                status = status,
                statusIsError = (failedSource != null || uiState.error != null) && !uiState.isLoading,
                onPlay = { channel -> launchPlay(channel, null) },
                onChannelOptions = { optionsChannel = it },
                onChoosePlaylist = { if (browse.picked.isNotEmpty()) chooserChannels = browse.picked.values.toList() },
                onEditList = { editingList = it },
                onOpenGuide = { setListView(false) },
                onOpenSettings = { showMenu = true },
                onAddQuickFilter = { addingQuickFilter = true },
                onHiddenCategories = { showCategoryDialog = true },
                modifier = Modifier.padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xl, top = topPadding, bottom = NuvioTheme.spacing.lg),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xl, top = topPadding),
            ) {
                LiveTvHeader(
                    guide = guide,
                    uiState = uiState,
                    gridFocused = gridFocused,
                    clock = minuteClock,
                    showTitle = showBuiltInHeader,
                    preview = if (previewsEnabled) preview else null,
                    // The guide keeps six channels; the header (description, preview) takes the rest.
                    modifier = Modifier.weight(1f),
                    playVideo = (gridFocused || settingsFocused || (categoriesOpen && screenFocused)) && started && !launching &&
                        !anyDialogOpen,
                )
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
                LaunchedEffect(gridFocused) { if (gridFocused) categoriesOpen = false }
                Box(modifier = Modifier.fillMaxWidth().height(GUIDE_RULER + GUIDE_ROW * GUIDE_ROWS)) {
                    // The guide moves aside for the categories: drawn moved, not laid out again.
                    val shift by animateDpAsState(
                        if (categoriesOpen || guide.selecting) CATEGORY_COLUMN + NuvioTheme.spacing.md else 0.dp,
                        tween(PANEL_MS, easing = FastOutSlowInEasing),
                        label = "liveTvCategories",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = shift.toPx() }
                            .focusRequester(gridFocus)
                            .onFocusChanged { gridFocused = it.isFocused }
                            .onPreviewKeyEvent { guide.onKey(it.nativeKeyEvent) }
                            // Focusable even when empty: a list emptied under the focus (the last channel taken out)
                            // keeps it here until it goes on to the categories, instead of dropping it.
                            .focusable(),
                    ) {
                        LiveTvGuideGrid(
                            state = guide,
                            active = gridFocused,
                            clock = minuteClock,
                            // Six channels at once, with room for longer names.
                            channelColumn = 280.dp,
                            rowHeight = GUIDE_ROW,
                            rulerHeight = GUIDE_RULER,
                            modifier = Modifier.fillMaxSize(),
                            corner = { LiveTvGuideDate(guide.viewStartMs, minuteClock) },
                        )
                        if (visibleChannels.isEmpty() && !filtering && uiState.isLoaded && !uiState.isLoading) {
                            Text(
                                text = when {
                                    filter == LiveTvFilter.Favorites -> stringResource(R.string.live_tv_no_favorites)
                                    filter is LiveTvFilter.Custom && query.isBlank() -> stringResource(R.string.live_tv_playlist_empty)
                                    else -> stringResource(R.string.live_tv_no_channels_found)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(top = 40.dp, start = 8.dp).widthIn(max = 520.dp),
                            )
                        }
                        if (guide.selecting) {
                            LiveTvPickingBar(count = guide.picked.size, modifier = Modifier.align(Alignment.BottomCenter))
                        } else if (guide.moving) {
                            LiveTvPickingBar(count = null, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    // Called by its full name: inside the Column the ColumnScope variant would be picked.
                    androidx.compose.animation.AnimatedVisibility(
                        // While picking channels the playlists stay beside them.
                        visible = categoriesOpen || guide.selecting,
                        enter = slideInHorizontally(tween(PANEL_MS, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(PANEL_MS)),
                        exit = slideOutHorizontally(tween(PANEL_MS, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(PANEL_MS)),
                    ) {
                        LiveTvCategoryColumn(
                            uiState = uiState,
                            listState = categoryList,
                            holdSelection = holdCategory,
                            viewModel = viewModel,
                            query = query,
                            onQueryChange = { query = it },
                            selectedKey = filterKey,
                            selectedFocus = categoryFocus,
                            onSelect = { filterKey = it },
                            // OK on a category shows its channels.
                            onPicked = { toGuide() },
                            onNewList = { naming = true },
                            onEditList = { editingList = it },
                            pickMode = guide.selecting,
                            picked = guide.picked.keys,
                            onChoose = choosePlaylist,
                            settingsFocus = settingsFocus,
                            settingsButton = {
                                LiveTvPillButton(
                                    text = "",
                                    icon = Icons.AutoMirrored.Filled.ViewList,
                                    iconDescription = stringResource(R.string.live_tv_view_channels),
                                    onClick = { setListView(true) },
                                )
                                LiveTvPillButton(
                                    text = "",
                                    icon = Icons.Filled.Settings,
                                    iconDescription = stringResource(R.string.live_tv_settings),
                                    onClick = { showMenu = true },
                                    modifier = Modifier
                                        .focusRequester(settingsFocus)
                                        .onFocusChanged { settingsFocused = it.isFocused },
                                )
                            },
                            modifier = Modifier
                                .width(CATEGORY_COLUMN)
                                .fillMaxHeight()
                                // Back while picking stops picking, rather than leaving Live TV.
                                .onPreviewKeyEvent { event ->
                                    val back = event.key == Key.Back || event.key == Key.Escape
                                    if (!back || !guide.selecting) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyUp) {
                                        guide.stopSelecting()
                                        toGuide()
                                    }
                                    true
                                }
                                // ▶ that nothing in the column used goes back to the guide.
                                .onKeyEvent { event ->
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && toGuide()
                                },
                        )
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        LiveTvSourceDialog(onDismiss = { showSourceDialog = false })
    }
    if (showCategoryDialog) {
        LiveTvCategoryDialog(onDismiss = { showCategoryDialog = false })
    }
    if (showPlaylists) {
        LiveTvPlaylistsDialog(onDismiss = { showPlaylists = false })
    }
    editingList?.let { id ->
        LiveTvPlaylistsDialog(onDismiss = { editingList = null }, openOnly = id)
    }
    optionsChannel?.let { channel ->
        val custom = filterFor(filterKey) as? LiveTvFilter.Custom
        LiveTvChannelOptionsDialog(
            channel = channel,
            uiState = uiState,
            filterKey = filterKey,
            canMove = custom != null && query.isBlank(),
            onWatch = {
                optionsChannel = null
                launchPlay(channel, null)
            },
            onAddToPlaylist = {
                optionsChannel = null
                chooserChannels = listOf(channel)
            },
            onPickSeveral = {
                optionsChannel = null
                browse.startPicking(channel)
            },
            onMove = {
                optionsChannel = null
                browse.movingUrl = channel.streamUrl
            },
            onShowInGuide = {
                optionsChannel = null
                showInGuide(channel)
            },
            onDismiss = { optionsChannel = null },
        )
    }
    chooserChannels?.let { channels ->
        LiveTvPlaylistChooserDialog(
            channels = channels,
            uiState = uiState,
            onNew = {
                chooserChannels = null
                namingChannels = channels
            },
            onChosen = {
                chooserChannels = null
                browse.stopPicking()
            },
            onDismiss = { chooserChannels = null },
        )
    }
    namingChannels?.let { channels ->
        LiveTvNewListDialog(
            channels = channels,
            onCreated = {
                namingChannels = null
                browse.stopPicking()
            },
            onDismiss = { namingChannels = null },
        )
    }
    if (addingQuickFilter) {
        LiveTvQuickFilterDialog(onDismiss = { addingQuickFilter = false })
    }
    if (naming) {
        LiveTvNewListDialog(
            // While picking, the picked channels go in it.
            channels = if (guide.selecting) guide.picked.values.toList() else emptyList(),
            onCreated = { id ->
                naming = false
                if (guide.selecting) {
                    // Back to the guide once the dialog is gone (below).
                    guide.stopSelecting()
                    categoriesOpen = false
                } else {
                    // The new, empty playlist is shown, with how to fill it.
                    filterKey = FILTER_LIST_PREFIX + id
                }
            },
            onDismiss = { naming = false },
        )
    }
    // A closed dialog gives the focus back where the viewer was, so ◀▶ never find nothing focused.
    val anyDialog = anyDialogOpen
    var hadDialog by remember { mutableStateOf(false) }
    LaunchedEffect(anyDialog) {
        if (anyDialog) {
            hadDialog = true
            return@LaunchedEffect
        }
        if (!hadDialog) return@LaunchedEffect
        hadDialog = false
        if (listView) {
            if (browse.lastAreaChannels && visibleChannels.isNotEmpty()) {
                browse.focusChannel(visibleChannels, browse.focusedUrl)
            } else {
                browse.focusCategory(browse.categoryKeys, filterKey)
            }
            return@LaunchedEffect
        }
        repeat(5) {
            withFrameNanos { }
            val target = when {
                !categoriesOpen -> gridFocus
                guide.selecting -> categoryFocus
                else -> settingsFocus
            }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    if (showMenu) {
        LiveTvMenuDialog(
            previews = previewsEnabled,
            loading = uiState.isLoading,
            listView = listView,
            onToggleLayout = {
                showMenu = false
                setListView(!listView)
            },
            onEditCategories = {
                showMenu = false
                showCategoryDialog = true
            },
            onSources = {
                showMenu = false
                showSourceDialog = true
            },
            onPlaylists = {
                showMenu = false
                showPlaylists = true
            },
            onDismiss = { showMenu = false },
        )
    }
}

/** While picking channels: how many, and the keys. */
@Composable
private fun LiveTvPickingBar(count: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(bottom = NuvioTheme.spacing.md)
            .background(Color(0xF0121214), RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Without a count: a channel of a playlist is being moved.
        if (count != null) {
            Text(
                text = pluralStringResource(R.plurals.live_tv_picked, count, count),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        Text(
            text = stringResource(if (count != null) R.string.live_tv_picking_keys else R.string.live_tv_moving_keys),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
        )
    }
}

/** How long the categories take to slide in or out. */
private const val PANEL_MS = 220

private const val NEW_LIST_KEY = "\u0000newlist"

/** The categories beside the guide. */
private val CATEGORY_COLUMN = 220.dp

/** Everything that used to sit above the guide: one button opens it. */
@Composable
private fun LiveTvMenuDialog(
    previews: Boolean,
    loading: Boolean,
    listView: Boolean,
    onToggleLayout: () -> Unit,
    onEditCategories: () -> Unit,
    onSources: () -> Unit,
    onPlaylists: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val previewSound = rememberLiveTvPreviewSoundEnabled()
    val showFavorites by LiveTvPreferences.showFavorites.collectAsStateWithLifecycle()
    val showAll by LiveTvPreferences.showAll.collectAsStateWithLifecycle()
    val preferHls by LiveTvPreferences.preferHls.collectAsStateWithLifecycle()
    val guideRefreshHours by LiveTvPreferences.guideRefreshHours.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    com.nuvio.tv.ui.components.NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_settings),
        width = 520.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.sm,
    ) {
        // Scrolls when the rows outgrow the dialog (a 720p TV).
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_sources),
                description = stringResource(R.string.live_tv_menu_sources_description),
                onClick = onSources,
                modifier = Modifier.focusRequester(first),
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_layout),
                description = stringResource(if (listView) R.string.live_tv_menu_layout_list else R.string.live_tv_menu_layout_guide),
                onClick = onToggleLayout,
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_categories),
                description = stringResource(R.string.live_tv_menu_categories_description),
                onClick = onEditCategories,
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_my_playlists),
                description = stringResource(R.string.live_tv_menu_playlists_description),
                onClick = onPlaylists,
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_reload),
                description = stringResource(if (loading) R.string.live_tv_loading else R.string.live_tv_menu_reload_description),
                onClick = {
                    if (!loading) {
                        LiveTvRepository.refresh()
                        onDismiss()
                    }
                },
            )
            if (previews) {
                LiveTvMenuRow(
                    title = stringResource(R.string.live_tv_menu_preview_sound),
                    description = stringResource(if (previewSound) R.string.live_tv_menu_on else R.string.live_tv_menu_off),
                    onClick = { LiveTvPreferences.setPreviewSound(context, !previewSound) },
                )
            }
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_show_favorites),
                description = stringResource(if (showFavorites) R.string.live_tv_menu_on else R.string.live_tv_menu_off),
                onClick = { LiveTvPreferences.setShowFavorites(context, !showFavorites) },
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_show_all),
                description = stringResource(if (showAll) R.string.live_tv_menu_on else R.string.live_tv_menu_off),
                onClick = { LiveTvPreferences.setShowAll(context, !showAll) },
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_prefer_hls),
                description = stringResource(if (preferHls) R.string.live_tv_menu_prefer_hls_on else R.string.live_tv_menu_prefer_hls_off),
                onClick = { LiveTvPreferences.setPreferHls(context, !preferHls) },
            )
            LiveTvMenuRow(
                title = stringResource(R.string.live_tv_menu_guide_refresh),
                description = stringResource(R.string.live_tv_menu_guide_refresh_value, guideRefreshHours),
                onClick = {
                    val options = LiveTvPreferences.guideRefreshOptionsHours
                    LiveTvPreferences.setGuideRefreshHours(context, options[(options.indexOf(guideRefreshHours) + 1) % options.size])
                },
            )
        }
    }
}

/** A settings entry: what it is, and in a smaller line what it does or how it is set. */
@Composable
private fun LiveTvMenuRow(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) Color.Black else NuvioTheme.colors.TextPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) Color.Black.copy(alpha = 0.65f) else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The header above the guide: what is selected, and the live preview on the right. */
@Composable
private fun LiveTvHeader(
    guide: LiveTvGuideState,
    uiState: com.nuvio.tv.reshaped.livetv.LiveTvUiState,
    gridFocused: Boolean,
    clock: State<Long>,
    showTitle: Boolean,
    preview: LiveTvPreviewPlayer?,
    playVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shownChannel = guide.channel
    val shownProgramme = remember(shownChannel, guide.anchorMs, uiState.guideVersion, uiState.currentProgrammes, gridFocused) {
        // The programme selected in the guide, or what is on now on the selected channel.
        (if (gridFocused) guide.selected() else null) ?: shownChannel?.guideKey?.let(uiState.currentProgrammes::get)
    }
    val failedSource = uiState.sources.firstOrNull { it.id in uiState.sourceErrors }
    val status = when {
        uiState.isLoading -> stringResource(R.string.live_tv_loading)
        failedSource != null -> stringResource(
            R.string.live_tv_source_error,
            failedSource.label,
            uiState.sourceErrors[failedSource.id]?.message(context).orEmpty(),
        )
        uiState.error != null -> uiState.error?.message(context)
        uiState.isEpgLoading -> stringResource(R.string.live_tv_guide_loading)
        else -> null
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT)) {
        // Never shorter than before; on a tall screen it grows with what the guide leaves.
        val headerHeight = maxHeight.coerceAtLeast(HEADER_HEIGHT)
        Row(modifier = Modifier.fillMaxWidth().height(headerHeight)) {
            LiveTvGuideInfo(
                channel = shownChannel,
                logo = shownChannel?.let(uiState::logoFor),
                programme = shownProgramme,
                clock = clock,
                status = status,
                statusIsError = (failedSource != null || uiState.error != null) && !uiState.isLoading,
                showTitle = showTitle && shownChannel == null,
                height = headerHeight,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (preview != null) {
                LiveTvPreviewVideo(
                    preview = preview,
                    channel = shownChannel,
                    logo = shownChannel?.let(uiState::logoFor),
                    playVideo = playVideo,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.xl).fillMaxHeight(),
                )
            }
        }
    }
}

/** The least the header takes; it grows into what the guide's six rows leave. */
private val HEADER_HEIGHT = 104.dp
private val GUIDE_ROW = 52.dp
private val GUIDE_RULER = 28.dp
private const val GUIDE_ROWS = 6

private const val POSTER_DELAY_MS = 250L

/** "Wed, Sep 30 · 9:43 AM": the day the guide shows, and the time now. */
@Composable
private fun LiveTvGuideDate(viewStartMs: Long, clock: State<Long>) {
    val pattern = remember { DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd") }
    val day = remember(pattern, viewStartMs / 3_600_000L) {
        java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(maxOf(viewStartMs, LiveTvClock.nowEpochMs())))
    }
    Text(
        text = "$day  ·  ${LiveTvClock.formatClock(clock.value)}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = NuvioTheme.colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * What is selected: the programme's picture when the guide has one, the channel, the title, its
 * times, time left and catch-up, how far it has got and its description. [actions] sit at the bottom.
 */
@Composable
private fun LiveTvGuideInfo(
    channel: LiveTvChannel?,
    logo: String?,
    programme: LiveTvProgramme?,
    clock: State<Long>,
    status: String?,
    statusIsError: Boolean,
    showTitle: Boolean,
    /** The header's height: the programme's 2:3 picture fills it. */
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        // Loaded once the selection rests, so holding ▼ through channels starts no image loads;
        // once a guide has given a picture its place stays, so the text does not jump between rows.
        val image = programme?.image
        val poster by produceState<String?>(null, image) {
            value = null
            if (image == null) return@produceState
            delay(POSTER_DELAY_MS)
            value = image
        }
        var posterSlot by remember { mutableStateOf(false) }
        LaunchedEffect(image != null) { if (image != null) posterSlot = true }
        if (posterSlot) {
            val posterWidth = height * 2 / 3
            Box(modifier = Modifier.padding(end = NuvioTheme.spacing.lg).size(posterWidth, height)) {
                LiveTvPoster(url = poster, width = posterWidth, height = height)
            }
        }
        // A new selection fades in instead of switching hard; the fade is drawn, not recomposed.
        val fade = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(channel?.streamUrl, programme?.startEpochMs) {
            fade.snapTo(0.3f)
            fade.animateTo(1f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = fade.value }) {
            if (channel == null) {
                if (showTitle) {
                    Text(
                        text = stringResource(R.string.live_tv_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveTvLogo(url = logo, name = channel.name, width = 40.dp, height = 24.dp)
                    Text(
                        text = channel.name.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.sp,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    text = programme?.title ?: channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (programme != null) {
                    val now = clock.value
                    val timing = listOfNotNull(
                        LiveTvClock.formatSpan(programme),
                        when {
                            programme.stopEpochMs <= now -> stringResource(R.string.live_tv_guide_ended)
                            programme.startEpochMs <= now -> liveTvTimeLeft(programme, clock)
                            else -> null
                        },
                        stringResource(R.string.live_tv_catchup).takeIf {
                            programme.startEpochMs < now && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)
                        },
                    ).joinToString("  ·  ")
                    Text(
                        text = timing,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (programme.startEpochMs <= now && now < programme.stopEpochMs) {
                        LiveTvProgressBar(
                            programme = programme,
                            clock = clock,
                            fill = NuvioTheme.colors.TextPrimary,
                            track = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.padding(top = 6.dp).widthIn(max = 420.dp).fillMaxWidth(),
                        )
                    }
                    // As many lines as the header leaves room for (up to four); a status (guide
                    // loading, a source failing) takes its place, so it fits.
                    programme.description?.takeIf { status == null }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).widthIn(max = 720.dp).weight(1f, fill = false),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One row of the category column. */
internal class LiveTvCategoryEntry(
    val key: String,
    val label: String,
    val count: Int? = null,
    val heading: Boolean = false,
    val folded: Boolean = false,
    val indent: Boolean = false,
    val sourceId: String? = null,
    /** The id of one of the viewer's playlists: held OK opens it to edit. */
    val listId: String? = null,
    /** "+ New playlist": OK names one, focus picks nothing. */
    val action: Boolean = false,
    /** While picking: the list has every ticked channel, so OK takes them out. */
    val removes: Boolean = false,
)

@Composable
internal fun rememberLiveTvCategoryEntries(
    uiState: com.nuvio.tv.reshaped.livetv.LiveTvUiState,
    viewModel: LiveTvScreenModel,
    showRecent: Boolean = false,
    unfoldAll: Boolean = false,
): List<LiveTvCategoryEntry> {
    val visibleGroups = remember(uiState.groups, uiState.hiddenGroups) { uiState.visibleGroups }
    val sections by produceState(viewModel.sourceSections(uiState, visibleGroups), uiState.channels, visibleGroups, uiState.hiddenChannelKeys, uiState.sources, uiState.sourceGroupOrders) {
        value = viewModel.sourceSections(uiState, visibleGroups)
            ?: withContext(Dispatchers.Default) { viewModel.computeSourceSections(uiState, visibleGroups) }
    }
    val collapsed = viewModel.collapsedSources
    val allLabel = stringResource(R.string.live_tv_all_channels)
    val favoritesLabel = stringResource(R.string.live_tv_favorites)
    val recentLabel = stringResource(R.string.live_tv_recent)
    val uncategorised = liveTvGroupLabel(LIVE_TV_UNGROUPED)
    val showAll by LiveTvPreferences.showAll.collectAsStateWithLifecycle()
    val showFavorites by LiveTvPreferences.showFavorites.collectAsStateWithLifecycle()
    val recentCount = if (showRecent) uiState.recentUrls.size else 0
    return remember(sections, visibleGroups, uiState.groupNames, uiState.customLists, collapsed.toMap(), allLabel, favoritesLabel, recentLabel, recentCount, uncategorised, showAll, showFavorites, unfoldAll, uiState.favoriteUrls.size) {
        fun label(group: String) = liveTvGroupName(group, uiState.groupNames) ?: if (group == LIVE_TV_UNGROUPED) uncategorised else group
        val counts = sections?.groupCounts.orEmpty()
        buildList {
            if (showFavorites) add(LiveTvCategoryEntry(FILTER_FAVORITES, favoritesLabel, count = if (showRecent) uiState.favoriteUrls.size else null))
            if (recentCount > 0) add(LiveTvCategoryEntry(FILTER_RECENT, recentLabel, count = recentCount))
            uiState.customLists.forEach { add(LiveTvCategoryEntry(FILTER_LIST_PREFIX + it.id, it.name, count = it.urls.size, listId = it.id)) }
            if (showAll) add(LiveTvCategoryEntry(FILTER_ALL, allLabel, count = sections?.total))
            val bySource = sections?.sources
            if (bySource != null && bySource.size > 1) {
                bySource.forEach { section ->
                    val folded = !unfoldAll && collapsed[section.source.id] == true
                    add(
                        LiveTvCategoryEntry(
                            key = FILTER_SOURCE_PREFIX + section.source.id,
                            label = section.source.label.uppercase(),
                            count = section.channelCount,
                            heading = true,
                            folded = folded,
                            sourceId = section.source.id,
                        ),
                    )
                    if (!folded) {
                        section.groups.forEach {
                            val key = sourceGroupKey(section.source.id, it)
                            add(LiveTvCategoryEntry(key, label(it), count = counts[key], indent = true))
                        }
                    }
                }
            } else if (uiState.sources.size <= 1) {
                visibleGroups.forEach { add(LiveTvCategoryEntry(it, label(it), count = counts[it])) }
            }
        }
    }
}

/**
 * Search, Favorites, All channels, then the categories; with several sources, each source's
 * categories sit under its own heading (with its channel count), which OK folds away.
 */
@Composable
private fun LiveTvCategoryColumn(
    uiState: com.nuvio.tv.reshaped.livetv.LiveTvUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    holdSelection: androidx.compose.runtime.MutableState<Boolean>,
    viewModel: LiveTvScreenModel,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedKey: String,
    selectedFocus: FocusRequester,
    onSelect: (String) -> Unit,
    onPicked: () -> Unit,
    onNewList: () -> Unit,
    onEditList: (String) -> Unit,
    settingsButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** The settings button beside the search, which ◀ and ▶ on the search reach. */
    settingsFocus: FocusRequester? = null,
    /** Channels are being picked: only Favorites and the playlists, to choose where they go. */
    pickMode: Boolean = false,
    /** The ticked channels' links: a list that has them all offers to take them out instead. */
    picked: Set<String> = emptySet(),
    onChoose: (String) -> Unit = {},
) {
    val selectedModifier = Modifier.focusRequester(selectedFocus)
    val collapsed = viewModel.collapsedSources
    val favoritesLabel = stringResource(R.string.live_tv_favorites)
    // New playlists are made only in the menu's My playlists.
    val removeFrom = stringResource(R.string.live_tv_remove_from_list)
    val pickedNow = if (pickMode) picked.toSet() else emptySet()
    val pickEntries = remember(uiState.customLists, uiState.favoriteUrls, favoritesLabel, pickedNow, removeFrom) {
        // OK takes the ticked channels out of a list that has them all already: it says so.
        fun hasAll(urls: Collection<String>) = pickedNow.isNotEmpty() && pickedNow.all(urls::contains)
        fun entry(key: String, name: String, urls: Collection<String>, count: Int?, listId: String?) =
            if (hasAll(urls)) {
                LiveTvCategoryEntry(key, removeFrom.format(name), count = count, listId = listId, removes = true)
            } else {
                LiveTvCategoryEntry(key, name, count = count, listId = listId)
            }
        buildList {
            add(entry(FILTER_FAVORITES, favoritesLabel, uiState.favoriteUrls, null, null))
            uiState.customLists.forEach { add(entry(FILTER_LIST_PREFIX + it.id, it.name, it.urls, it.urls.size, it.id)) }
        }
    }
    val entries = rememberLiveTvCategoryEntries(uiState, viewModel)
    // Item 0 is the search field: the guide's ◀ and Back find a category by this list.
    SideEffect { viewModel.categoryKeys = if (pickMode) emptyList() else listOf("\u0000search") + entries.map { it.key } }
    // Choosing a playlist starts from the top, where "Choose the playlist" says what this is.
    LaunchedEffect(pickMode) { if (pickMode) listState.scrollToItem(0) }
    // The selected category inside a folded source opens again, so it can take focus.
    LaunchedEffect(selectedKey) {
        (filterFor(selectedKey) as? LiveTvFilter.SourceGroup)?.let { collapsed.remove(it.id) }
    }
    // Glides to the focused category, as Nuvio's own lists do.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.nuvio.tv.ui.components.NuvioScrollDefaults.smoothScrollSpec,
    ) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 2.dp, bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (pickMode) {
            item(key = "\u0000choose") {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 8.dp)) {
                    Text(
                        text = stringResource(R.string.live_tv_choose_playlist),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.live_tv_choose_playlist_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            itemsIndexed(pickEntries, key = { _, it -> "pick" + it.key }) { index, entry ->
                LiveTvCategoryItem(
                    label = entry.label,
                    selected = false,
                    anchor = index == 0,
                    selectedModifier = selectedModifier,
                    // Consumes the guide's ◀ mark, so a later category focus picks as usual.
                    holdSelection = holdSelection,
                    count = entry.count,
                    icon = when {
                        entry.removes -> Icons.Filled.Remove
                        entry.action -> Icons.Filled.Add
                        else -> null
                    },
                    onClick = { onChoose(entry.key) },
                ) {}
            }
            return@LazyColumn
        }
        item(key = "\u0000search") {
            // Search, and the settings for everything else on this screen.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiveTvTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.live_tv_search),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                    sideFocus = settingsFocus,
                )
                settingsButton()
            }
        }
        items(entries, key = { it.key }, contentType = { if (it.heading) "heading" else "category" }) { entry ->
            LiveTvCategoryItem(
                label = entry.label,
                selected = selectedKey == entry.key,
                selectedModifier = selectedModifier,
                holdSelection = holdSelection,
                count = entry.count,
                heading = entry.heading,
                folded = entry.folded,
                indent = entry.indent,
                icon = if (entry.action) Icons.Filled.Add else null,
                onClick = entry.sourceId?.let { id -> { collapsed[id] = !entry.folded } } ?: if (entry.action) onNewList else {
                    {
                        onSelect(entry.key)
                        onPicked()
                    }
                },
                onLongClick = entry.listId?.let { id -> { onEditList(id) } },
            ) { if (!entry.action) onSelect(entry.key) }
        }
    }
    }
}

/**
 * A category: selecting happens on focus, like Netflix's genre rail, so the guide follows the
 * remote. A source heading ([heading]) folds its categories on OK ([onClick]).
 */
@Composable
private fun LiveTvCategoryItem(
    label: String,
    selected: Boolean,
    selectedModifier: Modifier,
    /** Takes [selectedModifier] without being shown as selected. */
    anchor: Boolean = false,
    count: Int? = null,
    heading: Boolean = false,
    folded: Boolean = false,
    indent: Boolean = false,
    holdSelection: androidx.compose.runtime.MutableState<Boolean>? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Focus the guide put here (Back, ◀) only shows where the viewer is.
        if (holdSelection?.value == true) {
            holdSelection.value = false
            return@LaunchedEffect
        }
        if (!selected) {
            delay(250) // passing over a category does not re-filter
            onSelect()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick ?: onSelect,
        onLongClick = onLongClick,
        modifier = (if (selected || anchor) selectedModifier else Modifier)
            .fillMaxWidth()
            .padding(top = if (heading) 8.dp else 0.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f) else Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        val content = if (focused) Color.Black else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        Row(
            modifier = Modifier.padding(start = if (indent) 22.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                )
            }
            if (heading) {
                Icon(
                    imageVector = if (folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                )
            }
            Text(
                text = label,
                style = if (heading) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || heading) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (heading) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color.Black.copy(alpha = 0.6f) else NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveTvEmptyState(onAddSource: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.live_tv_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
        Text(
            text = stringResource(R.string.live_tv_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.widthIn(max = 520.dp).padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.lg),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        LiveTvPillButton(
            text = stringResource(R.string.live_tv_add_source),
            onClick = onAddSource,
            modifier = Modifier.focusRequester(focus),
        )
    }
}

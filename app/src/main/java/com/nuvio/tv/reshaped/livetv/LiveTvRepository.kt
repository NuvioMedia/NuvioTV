package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live TV's channel list, guide, favorites and last channel for the active profile. It lives for
 * the app's process, so coming back from the player shows the list as it was, without a reload.
 * Loads run in its own scope: leaving the screen does not cancel them.
 *
 * Several sources can be saved; their channels show as one list, in the order the sources were
 * added. A source that fails to load keeps the channels it had.
 */
/** A replay link of a channel and the time it plays. */
/** A replay to play; an HLS one carries the TS replay of the same time, for a panel that then fails it ([fallback]). */
class LiveTvReplay(val playback: LiveTvChannel, val window: LiveTvReplayWindow, val fallback: LiveTvReplay? = null)

object LiveTvRepository {
    private const val TAG = "LiveTv"
    private const val EPG_TICK_MS = 60_000L
    /** How long a replay waits for the panel's HLS playlist before playing its TS replay. */
    private const val HLS_CHECK_MS = 6_000L
    /** How long a part of a replay is when the guide has no programme for that time. */
    private const val REPLAY_PART_MS = 30L * 60 * 1000
    /** Playlists: panels that build get.php on request may send nothing for a minute or more. */
    private const val PLAYLIST_READ_TIMEOUT_S = 120L
    private const val TS_PACKET = 188
    private const val TS_SYNC: Byte = 0x47
    /** How long a downloaded guide is used before it is downloaded again (Live TV settings, 12 h by default). */
    private val EPG_DOWNLOAD_MS: Long
        get() {
            LiveTvPreferences.ensureLoaded(appContext)
            return LiveTvPreferences.guideRefreshHours.value * 60L * 60 * 1000
        }
    /** The saved guide is read again when channels run out of kept programmes, at most this often. */
    private const val EPG_MIN_READ_GAP_MS = 60L * 60 * 1000
    /** A guide that could not be read is tried again sooner. */
    private const val EPG_RETRY_MS = 30L * 60 * 1000
    /** A guide that failed or broke off is tried again this soon once, then every [EPG_RETRY_MS]. */
    private const val EPG_FIRST_RETRY_MS = 5L * 60 * 1000
    /** Added to a guide's rank for a channel it matched only by name (see startEpg). */
    private const val NAME_MATCH_RANK = 1_000_000
    /** A held ▲ moving a playlist channel is saved once it pauses this long. */
    private const val LIST_SAVE_DELAY_MS = 600L
    /**
     * How long Live TV may go unseen (no list, no Live TV channel in the player) before its
     * channels and guide are let go. Coming back after that loads them again.
     */
    private const val IDLE_RELEASE_MS = 5L * 60 * 1000
    /** How long a guide download or read carries on once nothing shows Live TV. */
    private const val UNSEEN_GRACE_MS = 10_000L
    /** Sources loaded at once on a refresh: each holds a connection and a parse buffer. */
    private const val PARALLEL_SOURCES = 2
    /** Channels whose older days are kept in memory once looked at; see [requestHistory]. */
    private const val HISTORY_CHANNELS = 24
    private const val HISTORY_CHANNELS_LOW_MEMORY = 8
    /** Older days are asked of the panel only when the guide and archive fall this short of the catch-up. */
    private const val HISTORY_GAP_MS = 3L * 60 * 60 * 1000
    private const val HISTORY_FETCH_MS = 15_000L
    /** Marks a Stalker portal's guide in the guide list; see [stalkerGuideLink]. */
    private const val STALKER_GUIDE_PREFIX = "stalker-guide:"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Live TV's background work failing must never close the app.
            kotlinx.coroutines.CoroutineExceptionHandler { _, error -> Log.e(TAG, "Live TV background work failed", error) },
    )
    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    /**
     * Goes up each time Live TV lets go of its channels while unused, so a screen model kept on a
     * saved back stack entry drops its copy of the list too (it is rebuilt when the screen opens).
     */
    private val _releases = MutableStateFlow(0)
    val releases: StateFlow<Int> = _releases.asStateFlow()

    /** The list the last channel was picked from (a category, favorites, a search): zapping stays in it. */
    @Volatile var zapList: List<LiveTvChannel> = emptyList()
        private set
    /** The category key [zapList] is, or null for a search. */
    @Volatile var zapFolderKey: String? = null
        private set

    fun setZapList(channels: List<LiveTvChannel>, folderKey: String?) {
        zapList = channels
        zapFolderKey = folderKey
    }

    private lateinit var appContext: Context
    @Volatile private var storage: LiveTvStorage? = null
    @Volatile private var loadedProfileId: Int? = null
    /** The profile last loaded, kept after a release so the player can load it again. */
    @Volatile private var lastProfileId: Int? = null
    private var profileJob: Job? = null
    private var idleJob: Job? = null
    private var epgJob: Job? = null
    @Volatile private var epgGeneration = 0
    /** The guide programmes kept for each [LiveTvChannel.guideKey], for "up next" and the guide. */
    @Volatile private var keptSchedule: LiveTvSchedule = emptyMap()
    private var epgKey: Triple<List<String>, Set<String>, Long>? = null
    /** A guide read waiting for the sources still loading; set and started on [serial]. */
    @Volatile private var pendingEpg: Triple<List<String>, List<LiveTvChannel>, Set<String>>? = null
    /** How much guide is kept per channel; less on low-memory TVs. */
    @Volatile internal var guideWindow: LiveTvGuideWindow = LiveTvGuideWindow.Regular
        private set
    /** Set by Refresh: the next guide read downloads every guide again, however recent its saved copy. */
    @Volatile private var forceGuideDownload = false
    /** The viewer's category order; empty for A to Z. */
    @Volatile private var groupOrder: List<String> = emptyList()

    /** Results, state changes and [publish] run here one at a time, so no two of them race. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)
    /** Saves run in order on one IO thread: an older save can never land after a newer one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val loadPermits = Semaphore(PARALLEL_SOURCES)
    /**
     * One load per source. Adding, removing or refreshing a source cancels only that source's
     * load, so a phone upload during start-up no longer drops the sources still loading.
     */
    private val sourceJobs = ConcurrentHashMap<String, Job>()

    /** What each source loaded last. Only touched on [serial]. */
    private class LoadedSource(
        val channels: List<LiveTvChannel>,
        val epgUrls: List<String>,
        /** The source's categories in its own order (the panel's category list, else the playlist's). */
        val groupOrder: List<String>,
    )
    /** Every category in the providers' order: sources in the order added, each in its own. Set by [publish]. */
    @Volatile private var providerGroupOrder: List<String> = emptyList()
    private val loaded = HashMap<String, LoadedSource>()

    /**
     * Makes the state match [profileId]: the first call (or a profile switch, or the first after
     * Live TV was let go while unused) reads the saved sources and loads their channels; later
     * calls for the same profile do nothing. Returns true when it started a load.
     */
    fun ensureLoaded(context: Context, profileId: Int): Boolean {
        if (loadedProfileId == profileId) return false
        appContext = context.applicationContext
        loadedProfileId = profileId
        lastProfileId = profileId
        profileJob?.cancel()
        cancelAllLoads()
        LiveTvStalker.clearSession()
        storage = null
        _uiState.value = LiveTvUiState(isLoading = true)
        profileJob = scope.launch(serial) {
            stopEpg()
            epgKey = null
            pendingEpg = null
            // Read on [writer], so a sync writing this profile's files finishes first or waits (see applySync).
            val store = withContext(writer) {
                LiveTvStorage(appContext, profileId).also {
                    it.favoriteUrls() // first read parses the file
                    groupOrder = it.groupOrder()
                }
            }
            val sources = withContext(writer) { store.sources() }
            val hiddenChannels = withContext(writer) { store.hiddenChannelKeys() }
            storage = store
            loaded.clear()
            _uiState.value = LiveTvUiState(
                sources = sources,
                favoriteUrls = store.favoriteUrls(),
                customLists = store.customLists(),
                hiddenGroups = store.hiddenGroups(),
                groupNames = store.groupNames(),
                sourceGroupOrders = store.sourceGroupOrders(),
                hiddenChannelKeys = hiddenChannels,
                recentChannel = store.recentChannel(),
                isLoading = sources.isNotEmpty(),
            )
            sources.forEach { launchSourceLoad(it, adding = false, useSaved = true) }
            // Lists saved for sources removed meanwhile (here or by sync) go too.
            scope.launch(Dispatchers.IO) { LiveTvListCache.keepOnly(appContext.cacheDir, profileId, sources.map { it.id }) }
        }
        watchIdle()
        return true
    }

    /**
     * Lets go of everything Live TV holds (channels, guide, loads, the zap list) once nothing
     * has shown it for [IDLE_RELEASE_MS]: after going back to Home, only the saved files remain.
     * The list and a Live TV channel in the player both count as showing it.
     */
    private fun watchIdle() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            while (isActive) {
                _uiState.subscriptionCount.first { it == 0 }
                val back = withTimeoutOrNull(IDLE_RELEASE_MS) { _uiState.subscriptionCount.first { it > 0 } }
                if (back != null) continue
                val released = withContext(serial) {
                    if (_uiState.subscriptionCount.value > 0) {
                        false
                    } else {
                        releaseAll()
                        true
                    }
                }
                if (released) return@launch
            }
        }
    }

    /** Loads Live TV again if it was let go while unused (the player coming back to a channel). */
    fun reloadIfReleased() {
        if (loadedProfileId != null || !::appContext.isInitialized) return
        val profileId = lastProfileId ?: return
        ensureLoaded(appContext, profileId)
    }

    /** Runs on [serial]. */
    private fun releaseAll() {
        profileJob?.cancel()
        profileJob = null
        cancelAllLoads()
        stopEpg()
        epgKey = null
        pendingEpg = null
        loaded.clear()
        zapList = emptyList()
        zapFolderKey = null
        storage = null
        LiveTvStalker.clearSession()
        loadedProfileId = null
        _uiState.value = LiveTvUiState()
        _releases.update { it + 1 }
    }

    /** Loads every saved source again (the Refresh button). */
    fun refresh() {
        if (storage == null) return
        _uiState.update { it.copy(error = null) }
        scope.launch(serial) {
            // The guides are downloaded again too, once the channels are back.
            forceGuideDownload = true
            epgKey = null
            _uiState.value.sources.forEach { launchSourceLoad(it, adding = false) }
        }
    }

    fun loadM3uUrl(url: String, epgUrl: String = "", userAgent: String = "") {
        val trimmed = url.trim()
        launchAdd(LiveTvSource("", LiveTvSourceType.M3u, trimmed, epgUrl = epgUrl.trim(), userAgent = userAgent.trim()))
    }

    fun loadXtream(settings: LiveTvXtreamSettings, epgUrl: String = "", userAgent: String = "") {
        val normalized = settings.normalized()
        launchAdd(LiveTvSource("", LiveTvSourceType.Xtream, normalized.serverUrl, xtream = normalized, epgUrl = epgUrl.trim(), userAgent = userAgent.trim()))
    }

    fun loadStalker(settings: LiveTvStalkerSettings, epgUrl: String = "") {
        val normalized = settings.normalized()
        launchAdd(LiveTvSource("", LiveTvSourceType.Stalker, normalized.portalUrl, stalker = normalized, epgUrl = epgUrl.trim()))
    }

    /**
     * Saves a changed source (a new server address, login or guide link) in place of the saved
     * one: it keeps its id, so its hidden channels and categories stay, and its favourites and
     * last channel move to the new links once it loaded. A failed load changes nothing.
     */
    fun updateSource(sourceId: String, edited: LiveTvSource) {
        val existing = _uiState.value.sources.firstOrNull { it.id == sourceId } ?: return
        val source = when (edited.type) {
            LiveTvSourceType.M3u -> edited.copy(url = edited.url.trim())
            LiveTvSourceType.Xtream -> edited.xtream.normalized().let { edited.copy(url = it.serverUrl, xtream = it) }
            LiveTvSourceType.Stalker -> edited.stalker.normalized().let { edited.copy(url = it.portalUrl, stalker = it) }
        }
        val updated = source.copy(
            id = sourceId, epgUrl = edited.epgUrl.trim(), name = edited.name.trim(),
            userAgent = edited.userAgent.trim().takeIf { edited.type != LiveTvSourceType.Stalker }.orEmpty(),
        )
        if (updated.copy(name = "") == existing.copy(name = "")) {
            // Only the name changed: nothing to load again.
            if (updated.name != existing.name) renameSource(updated)
            _uiState.update { it.copy(addedCount = it.addedCount + 1, error = null) }
            return
        }
        launchAdd(updated)
    }

    private fun renameSource(source: LiveTvSource) {
        val store = storage ?: return
        scope.launch(serial) {
            val sources = _uiState.value.sources.map { if (it.id == source.id) source else it }
            _uiState.update { it.copy(sources = sources) }
            scope.launch(writer) { store.saveSources(sources) }
        }
    }

    /**
     * Saves a playlist sent from a phone as a source, then shows it. Blocking (the upload
     * server's thread): [input] is copied to a file first so it is never held in memory as a whole.
     */
    fun importPlaylist(fileName: String, input: InputStream, maxBytes: Long): Boolean {
        val store = storage ?: return false
        val name = fileName.trim().ifBlank { "M3U playlist" }
        val candidate = withExistingId(LiveTvSource("", LiveTvSourceType.M3u, name), store)
        val saved = runCatching {
            store.savePlaylistFile(candidate.id) { temp ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IllegalStateException("too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }.isSuccess
        if (!saved) return false
        launchAdd(candidate)
        return true
    }

    /** Removes one source and its channels; the other sources' loads carry on. */
    fun removeSource(sourceId: String) {
        val store = storage ?: return
        sourceJobs.remove(sourceId)?.cancel()
        scope.launch(serial) {
            val gone = _uiState.value.sources.firstOrNull { it.id == sourceId } ?: return@launch
            val sources = _uiState.value.sources.filterNot { it.id == sourceId }
            LiveTvStalker.clearSession()
            loaded.remove(sourceId)
            _uiState.update { it.copy(sources = sources, sourceErrors = it.sourceErrors - sourceId, error = null) }
            val orders = _uiState.value.sourceGroupOrders
            if (gone.identity in orders) _uiState.update { it.copy(sourceGroupOrders = orders - gone.identity) }
            val savedList = savedListFile(gone)
            scope.launch(writer) {
                savedList?.let(LiveTvListCache::delete)
                // Removed by the viewer: the other devices remove it too on the next sync.
                store.markSyncRemoved(gone.identity)
                if (gone.identity in orders) store.saveSourceGroupOrders(orders - gone.identity)
                store.saveSources(sources)
                store.deletePlaylistFile(sourceId)
            }
            publish()
            updateLoading()
        }
    }

    /**
     * Moves a source [step] places up (negative) or down: its channels and categories follow it in
     * the lists. Order only; the sources themselves are left as they are.
     */
    fun moveSource(sourceId: String, step: Int) {
        val store = storage ?: return
        scope.launch(serial) {
            val sources = _uiState.value.sources
            val from = sources.indexOfFirst { it.id == sourceId }
            val to = from + step
            if (from < 0 || to !in sources.indices) return@launch
            val reordered = ArrayList(sources).apply { add(to, removeAt(from)) }
            _uiState.update { it.copy(sources = reordered) }
            scope.launch(writer) { store.saveSources(reordered) }
            publish()
        }
    }

    /** Shows or hides a category. */
    fun setGroupHidden(group: String, hidden: Boolean) {
        val current = _uiState.value.hiddenGroups
        if ((group in current) == hidden) return
        saveHiddenGroups(if (hidden) current + group else current - group)
    }

    /** Shows every category, or hides every one (to then pick the few that are wanted). */
    fun setAllGroupsHidden(hidden: Boolean) {
        saveHiddenGroups(if (hidden) _uiState.value.groups.toHashSet() else emptySet())
    }

    /** Shows or hides single channels (a whole category's at once for Show all / Hide all). */
    fun setChannelsHidden(channels: Collection<LiveTvChannel>, hidden: Boolean) {
        val keys = channels.map { it.hideKey }
        val current = _uiState.value.hiddenChannelKeys
        val next = if (hidden) current + keys else current - keys.toSet()
        if (next.size == current.size) return
        _uiState.update { it.copy(hiddenChannelKeys = next) }
        refreshShownChannels()
        storage?.let { store -> scope.launch(writer) { store.saveHiddenChannelKeys(next) } }
    }

    /** Moves a category [step] places up (negative) or down; the order is kept for every list. */
    fun moveGroup(group: String, step: Int) {
        val groups = _uiState.value.groups
        val from = groups.indexOf(group)
        val to = from + step
        if (from < 0 || to !in groups.indices) return
        val reordered = ArrayList(groups).apply { add(to, removeAt(from)) }
        groupOrder = reordered
        _uiState.update { it.copy(groups = reordered) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(reordered) } }
    }

    /**
     * Moves [group] one place up or down among [source]'s own categories only: another source
     * with a category of the same name keeps its order.
     */
    fun moveSourceGroup(source: LiveTvSource, own: Set<String>, group: String, step: Int) {
        val state = _uiState.value
        val groups = state.groupsOf(source, own)
        val from = groups.indexOf(group)
        val to = from + step
        if (from < 0 || to !in groups.indices) return
        val neighbour = groups[to]
        val reordered = ArrayList(groups).apply { add(to, removeAt(from)) }
        val orders = state.sourceGroupOrders + (source.identity to reordered)
        // The shared order (the player's categories, one list for all sources) moves it past the
        // same neighbour, so it follows where it can.
        val shared = ArrayList(state.groups).apply {
            if (remove(group)) {
                val at = indexOf(neighbour)
                if (at < 0) add(group) else add(if (step < 0) at else at + 1, group)
            }
        }
        groupOrder = shared
        _uiState.update { it.copy(sourceGroupOrders = orders, groups = shared) }
        storage?.let { store ->
            scope.launch(writer) {
                store.saveSourceGroupOrders(orders)
                store.saveGroupOrder(shared)
            }
        }
    }

    /** Each source's own order goes: every source follows the shared order again. */
    private fun clearSourceGroupOrders() {
        if (_uiState.value.sourceGroupOrders.isEmpty()) return
        _uiState.update { it.copy(sourceGroupOrders = emptyMap()) }
        storage?.let { store -> scope.launch(writer) { store.saveSourceGroupOrders(emptyMap()) } }
    }

    /**
     * Gives a category a name of its own (blank goes back to the playlist's name). It
     * keeps its channels, hiding and place: everything still goes by the playlist's name.
     */
    fun renameGroup(group: String, name: String) {
        val names = _uiState.value.groupNames
        val next = if (name.isBlank()) names - group else names + (group to name)
        if (next == names) return
        _uiState.update { state ->
            state.copy(groupNames = next).let { if (groupOrder.isEmpty()) it.copy(groups = orderedGroups(it.groupCounts.keys, next)) else it }
        }
        storage?.let { store -> scope.launch(writer) { store.saveGroupNames(next) } }
    }

    /** Back to the providers' own order. */
    fun resetGroupOrder() {
        clearSourceGroupOrders()
        groupOrder = emptyList()
        _uiState.update { it.copy(groups = orderedGroups(it.groupCounts.keys, it.groupNames)) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(emptyList()) } }
    }

    /**
     * [names] in the viewer's order, then the ones it does not have yet in the providers' own
     * order (as IPTV players show them), with "Uncategorised" last.
     */
    private fun orderedGroups(names: Set<String>, renamed: Map<String, String> = _uiState.value.groupNames): List<String> {
        // A synced order may name a category twice; each is listed once (rows are keyed by it).
        val ordered = groupOrder.distinct().filterTo(ArrayList()) { it in names }
        val placed = ordered.toHashSet()
        providerGroupOrder.forEach { if (it in names && it != LIVE_TV_UNGROUPED && placed.add(it)) ordered += it }
        names.filterNot(placed::contains)
            .sortedWith(
                compareBy<String> { it == LIVE_TV_UNGROUPED && it !in renamed }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { renamed[it]?.trim() ?: it },
            )
            .forEach(ordered::add)
        return ordered
    }

    /** Sorts the categories A to Z (by the names shown), as the viewer's own order. */
    fun sortGroupsAlphabetically() {
        clearSourceGroupOrders()
        val state = _uiState.value
        val sorted = state.groups.sortedWith(
            compareBy<String> { it == LIVE_TV_UNGROUPED && it !in state.groupNames }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { state.groupNames[it]?.trim() ?: it },
        )
        groupOrder = sorted
        _uiState.update { it.copy(groups = sorted) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(sorted) } }
    }

    private fun saveHiddenGroups(groups: Set<String>) {
        _uiState.update { it.copy(hiddenGroups = groups) }
        refreshShownChannels()
        storage?.let { store -> scope.launch(writer) { store.saveHiddenGroups(groups) } }
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        val store = storage ?: return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.add(channel.streamUrl)) favorites.remove(channel.streamUrl)
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
    }

    // region Own playlists

    /** Makes a playlist named [name], with [channel] in it when given; returns its id. */
    fun createCustomList(name: String, channel: LiveTvChannel? = null): String? =
        createCustomList(name, listOfNotNull(channel))

    /** Makes a playlist named [name] holding [channels]; returns its id. */
    fun createCustomList(name: String, channels: List<LiveTvChannel>): String? {
        val title = name.trim().ifEmpty { return null }
        // Starts with the time it was made, so lists sort oldest first on every device.
        val id = System.currentTimeMillis().toString(36).padStart(9, '0') + java.util.UUID.randomUUID().toString().take(4)
        updateCustomLists { it + LiveTvCustomList(id, title, channels.map { c -> c.streamUrl }.distinct()) }
        return id
    }

    fun renameCustomList(id: String, name: String) {
        val title = name.trim().ifEmpty { return }
        // Typed a letter at a time: saved once the typing pauses.
        updateCustomLists(persistLater = true) { lists -> lists.map { if (it.id == id) it.copy(name = title) else it } }
    }

    fun deleteCustomList(id: String) {
        updateCustomLists { lists -> lists.filterNot { it.id == id } }
    }

    /** Adds [channels] to the end of list [id], skipping ones already in it. */
    fun addToCustomList(id: String, channels: Collection<LiveTvChannel>) {
        if (channels.isEmpty()) return
        updateCustomLists { lists ->
            lists.map { list ->
                if (list.id != id) return@map list
                val have = list.urls.toHashSet()
                list.copy(urls = list.urls + channels.map { it.streamUrl }.filter(have::add))
            }
        }
    }

    /** Makes [channels] favourites (ones already are stay). */
    fun addFavorites(channels: Collection<LiveTvChannel>) {
        val store = storage ?: return
        if (channels.isEmpty()) return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.addAll(channels.map { it.streamUrl })) return
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
    }

    /** Takes [channels] out of favourites, in one change. */
    fun removeFavorites(channels: Collection<LiveTvChannel>) {
        val store = storage ?: return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.removeAll(channels.mapTo(HashSet()) { it.streamUrl })) return
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
    }

    fun removeFromCustomList(id: String, url: String) {
        updateCustomLists { lists -> lists.map { if (it.id == id) it.copy(urls = it.urls - url) else it } }
    }

    /** Takes [channels] out of list [id], in one change. */
    fun removeFromCustomList(id: String, channels: Collection<LiveTvChannel>) {
        val urls = channels.mapTo(HashSet()) { it.streamUrl }
        updateCustomLists { lists -> lists.map { if (it.id == id) it.copy(urls = it.urls.filterNot(urls::contains)) else it } }
    }

    /**
     * Moves the channel [url] of list [id] past [step] of its neighbours; only those [shown]
     * counts (a channel no source lists now is hidden, and stepping over it would look stuck).
     */
    fun moveInCustomList(id: String, url: String, step: Int, shown: (String) -> Boolean = { true }) {
        updateCustomLists(persistLater = true) { lists ->
            lists.map { list ->
                val from = list.urls.indexOf(url)
                if (list.id != id || from < 0 || step == 0) return@map list
                var to = from
                var left = kotlin.math.abs(step)
                val direction = if (step > 0) 1 else -1
                while (left > 0) {
                    var next = to + direction
                    while (next in list.urls.indices && !shown(list.urls[next])) next += direction
                    if (next !in list.urls.indices) break
                    to = next
                    left--
                }
                if (to == from) return@map list
                list.copy(urls = list.urls.toMutableList().apply { add(to, removeAt(from)) })
            }
        }
    }

    private var pendingListSave: Job? = null

    /**
     * [persistLater]: a run of quick changes (a held ▲ moving a channel) is saved and synced
     * once it pauses, not on every step.
     */
    private fun updateCustomLists(persistLater: Boolean = false, change: (List<LiveTvCustomList>) -> List<LiveTvCustomList>) {
        val store = storage ?: return
        var saved: List<LiveTvCustomList> = emptyList()
        _uiState.update { state -> state.copy(customLists = change(state.customLists).also { saved = it }) }
        pendingListSave?.cancel()
        pendingListSave = scope.launch(writer) {
            if (persistLater) delay(LIST_SAVE_DELAY_MS)
            store.saveCustomLists(saved)
        }
    }

    /** The playlists with links that moved ([moved], old link to new) changed over. */
    private fun List<LiveTvCustomList>.movedTo(moved: Map<String, LiveTvChannel>): List<LiveTvCustomList> =
        map { list -> if (list.urls.none(moved::containsKey)) list else list.copy(urls = list.urls.map { moved[it]?.streamUrl ?: it }.distinct()) }

    // endregion

    /** [channel] is the list's own entry (not a resolved Stalker link), so it can be found again. */
    fun recordRecentChannel(channel: LiveTvChannel) {
        val recent = LiveTvRecentChannel(channel.streamUrl, channel.name, channel.logoUrl, channel.group, channel.tvgId)
        if (_uiState.value.recentChannel == recent) return
        _uiState.update { it.copy(recentChannel = recent) }
        storage?.let { store -> scope.launch(writer) { store.saveRecentChannel(recent) } }
    }

    /** The list entry for a remembered channel, or a stand-in when the list no longer has it. */
    fun channelFor(recent: LiveTvRecentChannel): LiveTvChannel =
        _uiState.value.channels.firstOrNull { it.streamUrl == recent.streamUrl }
            ?: LiveTvChannel(
                id = recent.streamUrl,
                name = recent.name,
                streamUrl = recent.streamUrl,
                tvgId = recent.tvgId,
                logoUrl = recent.logoUrl,
                group = recent.group,
                headers = defaultStreamHeaders(recent.streamUrl),
                // Guide keys are scoped to a playlist: borrow the key of a listed channel with the same guide id.
                guideKey = recent.tvgId?.takeIf(String::isNotBlank)?.let { id ->
                    _uiState.value.channels.firstOrNull { it.tvgId.equals(id, ignoreCase = true) }?.guideKey
                } ?: recent.guideKey,
            )

    /** Whether [channel] comes from a Stalker portal, whose links are created per play. */
    fun isStalker(channel: LiveTvChannel): Boolean =
        channel.stalkerCommand != null &&
            _uiState.value.sources.firstOrNull { it.id == channel.sourceId }?.type == LiveTvSourceType.Stalker

    /** The channel with a link that plays now: Stalker links are created per play; others are as listed. */
    suspend fun playableChannel(channel: LiveTvChannel): LiveTvChannel {
        val source = _uiState.value.sources.firstOrNull { it.id == channel.sourceId }
        if (source?.type != LiveTvSourceType.Stalker || channel.stalkerCommand == null) return channel
        return try {
            LiveTvStalker.resolve(source.stalker, channel)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Stalker link failed, trying the listed link", error)
            channel
        }
    }

    /**
     * The channel as the player should open it, registered so the player treats it as Live TV,
     * and remembered as the last channel.
     */
    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel {
        val playback = playableChannel(channel)
        LiveTvPlaybackRegistry.register(playback.streamUrl, listUrl = channel.streamUrl)
        recordRecentChannel(channel)
        return playback
    }

    /**
     * The replay of [channel]'s [programme] (catch-up; also one still on air, to watch it from the
     * start), registered so the player treats it as Live TV that can be sought; null when the
     * provider does not keep it. It runs on past the programme's end (see [replayChannel]).
     */
    suspend fun catchupChannel(channel: LiveTvChannel, programme: LiveTvProgramme): LiveTvReplay? =
        replayChannel(channel, programme.startEpochMs, programme.stopEpochMs)

    /**
     * A replay of [channel] from [startMs] to [programmeEndMs] (by default the end of the guide's
     * programme on at [startMs]), never past now, so the player shows the programme's own length.
     * The player asks for the next part, or goes live, as it gets to the end.
     */
    suspend fun replayChannel(channel: LiveTvChannel, fromMs: Long, programmeEndMs: Long = replayPartEnd(channel, fromMs)): LiveTvReplay? {
        val catchup = channel.catchup ?: return null
        val now = LiveTvClock.nowEpochMs()
        // Only an Xtream panel answers its /timeshift/ form; other "shift" servers take ?utc=.
        val source = _uiState.value.sources.firstOrNull { it.id == channel.sourceId }
        val xtreamPanel = isXtreamPanel(channel, source)
        val panelLink = LiveTvCatchupLinks.needsPanelZone(channel.streamUrl, catchup, xtreamPanel)
        // A panel's /timeshift/ link names whole minutes: the replay starts on the minute, and its
        // window says so, so the position shown (and a rewind from it) matches what plays.
        val startMs = if (panelLink) fromMs - Math.floorMod(fromMs, 60_000L) else fromMs
        if (!LiveTvCatchupLinks.isPlayableFrom(catchup, startMs, now)) return null
        val zone = if (panelLink) {
            LiveTvCatchupLinks.xtreamLogin(channel.streamUrl)?.let { (server, user, pass) -> LiveTvXtream.zone(server, user, pass, source?.userAgent.orEmpty()) }
        } else {
            null
        }
        // Just the programme (a seek bar as long as it is), up to now at most; one that overran
        // goes on in the next part, which the player asks for at the end.
        val stop = minOf(now, if (programmeEndMs > startMs) programmeEndMs else startMs + REPLAY_PART_MS)
        // "Prefer HLS": the panel's HLS replay has a length, so it shows a progress bar and seeks.
        // A panel that gives none (or no playlist in time) plays the TS replay as before.
        val hlsLink = if (LiveTvCatchupLinks.isXtreamReplay(channel.streamUrl, catchup, xtreamPanel) && preferHls()) {
            LiveTvCatchupLinks.link(channel.streamUrl, catchup, startMs, stop, now, zone, xtreamPanel, hls = true)
                ?.takeIf { isHlsPlaylist(it, channel.headers) }
        } else {
            null
        }
        val tsLink = LiveTvCatchupLinks.link(channel.streamUrl, catchup, startMs, stop, now, zone, xtreamPanel)
        val link = hlsLink ?: tsLink ?: return null
        // A link that stays the same for a later end names none, and plays on to live by itself.
        val tsBounded = tsLink != null &&
            tsLink != LiveTvCatchupLinks.link(channel.streamUrl, catchup, startMs, stop + 120_000L, now, zone, xtreamPanel)
        val window = LiveTvReplayWindow(startMs, stop, hlsLink != null || tsBounded)
        LiveTvPlaybackRegistry.register(link, listUrl = channel.streamUrl, catchup = true, window = window)
        recordRecentChannel(channel)
        // The panel's playlist answered, yet its parts can still fail (one being recorded): the TS replay then plays.
        val fallback = if (hlsLink != null && tsLink != null) {
            LiveTvReplay(channel.copy(streamUrl = tsLink), LiveTvReplayWindow(startMs, stop, tsBounded))
        } else {
            null
        }
        return LiveTvReplay(channel.copy(streamUrl = link), window, fallback)
    }

    /**
     * Where the next part of a replay from [startMs] ends: the end of the programme on then (or the
     * start of the next, after a gap in the guide), from the kept guide. Never a part of under a minute.
     */
    private fun replayPartEnd(channel: LiveTvChannel, startMs: Long): Long {
        val minEnd = startMs + 60_000L
        val programmes = schedule(channel.guideKey)
        val programme = programmes.firstOrNull { it.stopEpochMs > minEnd } ?: return startMs + REPLAY_PART_MS
        return if (programme.startEpochMs > minEnd) programme.startEpochMs else programme.stopEpochMs
    }

    private fun preferHls(): Boolean {
        LiveTvPreferences.ensureLoaded(appContext)
        return LiveTvPreferences.preferHls.value
    }

    /** Whether [url] answers with an HLS playlist; asked once, briefly, before a replay starts. */
    private suspend fun isHlsPlaylist(url: String, headers: Map<String, String>): Boolean =
        withTimeoutOrNull(HLS_CHECK_MS) {
            runCatching {
                LiveTvHttp.stream(url, headers) { input ->
                    val head = ByteArray(32)
                    // A slow or chunked answer may hand over the first bytes a few at a time.
                    var read = 0
                    while (read < head.size) {
                        val count = input.read(head, read, head.size - read)
                        if (count < 0) break
                        read += count
                    }
                    read > 0 && String(head, 0, read, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
        } ?: false

    /** The channel next to the one at [listUrl] in [channels], wrapping around. */
    fun neighbour(channels: List<LiveTvChannel>, listUrl: String?, step: Int): LiveTvChannel? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.streamUrl == listUrl }
        val next = if (index < 0) 0 else Math.floorMod(index + step, channels.size)
        return channels[next]
    }

    // region Loads

    private fun cancelAllLoads() {
        sourceJobs.values.forEach(Job::cancel)
        sourceJobs.clear()
    }

    /** The screen shows "Loading" while any source is loading. */
    private fun updateLoading() {
        val busy = sourceJobs.values.any { it.isActive }
        _uiState.update { if (it.isLoading == busy) it else it.copy(isLoading = busy) }
        if (!busy && pendingEpg != null) {
            scope.launch(serial) {
                val pending = pendingEpg ?: return@launch
                if (sourceJobs.values.any { it.isActive }) return@launch
                pendingEpg = null
                startEpg(pending.first, pending.second, pending.third)
            }
        }
    }

    /** A source being added keeps the id of a saved one that is the same source, so it replaces it. */
    private fun withExistingId(candidate: LiveTvSource, store: LiveTvStorage): LiveTvSource {
        val existing = _uiState.value.sources.firstOrNull { it.identity == candidate.identity }
        return candidate.copy(id = existing?.id ?: store.newSourceId())
    }

    /**
     * Loads a new source (or a saved one entered again) and adds it once it listed channels; a
     * failed attempt changes nothing but the error shown.
     */
    private fun launchAdd(input: LiveTvSource) {
        val store = storage ?: return
        val validation = validate(input)
        if (validation != null) {
            _uiState.update { it.copy(error = validation) }
            return
        }
        val candidate = if (input.id.isBlank()) withExistingId(input, store) else input
        _uiState.update { it.copy(error = null) }
        launchSourceLoad(candidate, adding = true)
    }

    /** Loads one source in its own job, replacing only an earlier load of the same source. */
    private fun launchSourceLoad(source: LiveTvSource, adding: Boolean, useSaved: Boolean = false) {
        val store = storage ?: return
        sourceJobs.remove(source.id)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = loadPermits.withPermit {
                try {
                    Result.success(loadSource(source, useSaved))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Live TV source ${source.type} failed", error)
                    Result.failure(error)
                } catch (tooLarge: OutOfMemoryError) {
                    // A list too large for this TV fails as this source's error (it can still be removed), not the app.
                    Log.w(TAG, "Live TV source ${source.type} too large", tooLarge)
                    Result.failure(java.io.IOException("Playlist too large"))
                }
            }
            val self = coroutineContext[Job]
            withContext(serial) {
                // A newer load of this source, or its removal, wins over this result.
                if (sourceJobs[source.id] !== self) return@withContext
                val known = _uiState.value.sources.any { it.id == source.id }
                if (!adding && !known) return@withContext
                outcome.onSuccess { (result, notice) ->
                    val previous = loaded[source.id]
                    loaded[source.id] = result
                    if (adding && known) {
                        if (previous != null) {
                            carryOverFavorites(source, previous, result, store)
                        } else {
                            // The old list never loaded (its server moved): Xtream links still name their channel.
                            _uiState.value.sources.firstOrNull { it.id == source.id }?.let { old -> carryOverXtreamFavorites(old, result, store) }
                        }
                    }
                    if (adding) {
                        // An edit that changed what the source is (another link or login) removes the old one.
                        val replaced = _uiState.value.sources.firstOrNull { it.id == source.id }?.identity?.takeIf { it != source.identity }
                        val sources = _uiState.value.sources.let { current ->
                            if (known) current.map { if (it.id == source.id) source else it } else current + source
                        }
                        _uiState.update { it.copy(sources = sources, addedCount = it.addedCount + 1, error = notice) }
                        // Its own category order follows it to what it is now.
                        val orders = _uiState.value.sourceGroupOrders
                        val movedOrders = replaced?.let { old -> orders[old]?.let { orders - old + (source.identity to it) } }
                        if (movedOrders != null) _uiState.update { it.copy(sourceGroupOrders = movedOrders) }
                        scope.launch(writer) {
                            movedOrders?.let(store::saveSourceGroupOrders)
                            replaced?.let(store::markSyncRemoved)
                            // Added again after a removal: no longer removed.
                            store.clearSyncRemoved(listOf(source.identity))
                            store.saveSources(sources)
                            if (source.type != LiveTvSourceType.M3u || source.url.isHttpUrl()) store.deletePlaylistFile(source.id)
                        }
                    }
                    _uiState.update { state ->
                        val errors = state.sourceErrors - source.id
                        state.copy(sourceErrors = if (notice != null) errors + (source.id to notice) else errors)
                    }
                    publish()
                }
                outcome.onFailure { error ->
                    val reason = (error as? LiveTvException)?.error ?: fallbackError(source.type)
                    if (adding) {
                        if (!known && source.type == LiveTvSourceType.M3u && !source.url.isHttpUrl()) {
                            scope.launch(writer) { store.deletePlaylistFile(source.id) }
                        }
                        _uiState.update { it.copy(error = reason, isLoaded = it.isLoaded || it.sources.isNotEmpty()) }
                    } else {
                        // The source keeps the channels it had.
                        _uiState.update { it.copy(sourceErrors = it.sourceErrors + (source.id to reason), isLoaded = true) }
                        // Reload asked for the guide again too: it still comes, with the channels kept.
                        if (epgKey == null && loaded.isNotEmpty()) publish()
                    }
                }
            }
        }
        sourceJobs[source.id] = job
        job.invokeOnCompletion {
            sourceJobs.remove(source.id, job)
            updateLoading()
        }
        _uiState.update { it.copy(isLoading = true) }
        job.start()
    }

    private fun validate(source: LiveTvSource): LiveTvError? =
        if (source.epgUrl.isNotBlank() && !source.epgUrl.isHttpUrl()) LiveTvError.GuideInvalidUrl else validateSource(source)

    private fun validateSource(source: LiveTvSource): LiveTvError? = when (source.type) {
        LiveTvSourceType.M3u -> if (source.url.isBlank()) LiveTvError.InvalidUrl else null
        LiveTvSourceType.Xtream -> when {
            !source.xtream.isConfigured -> LiveTvError.XtreamRequired
            !source.xtream.serverUrl.isHttpUrl() -> LiveTvError.XtreamInvalidUrl
            else -> null
        }
        LiveTvSourceType.Stalker -> when {
            !source.stalker.isConfigured -> LiveTvError.StalkerRequired
            !source.stalker.portalUrl.isHttpUrl() -> LiveTvError.StalkerInvalidUrl
            else -> null
        }
    }

    private fun fallbackError(type: LiveTvSourceType): LiveTvError = when (type) {
        LiveTvSourceType.M3u -> LiveTvError.LoadFailed
        LiveTvSourceType.Xtream -> LiveTvError.XtreamFailed
        LiveTvSourceType.Stalker -> LiveTvError.StalkerFailed
    }

    /** What a provider gave: its channels (not yet tagged with the source), guide links and category order. */
    private class FetchedSource(
        val channels: List<LiveTvChannel>,
        val epgUrls: List<String>,
        val providerOrder: List<String>,
        val notice: LiveTvError?,
    )

    /** The saved copy of [source]'s list (links and Xtream panels; see [LiveTvListCache]), or null. */
    private fun savedListFile(source: LiveTvSource): File? {
        val profileId = loadedProfileId ?: return null
        val saves = source.type == LiveTvSourceType.Xtream || (source.type == LiveTvSourceType.M3u && source.url.isHttpUrl())
        return if (saves) LiveTvListCache.file(appContext.cacheDir, profileId, source.id) else null
    }

    /**
     * One source's channels (tagged with the source) and guide links, plus a notice for a partial load.
     * With [useSaved] (opening Live TV), a list saved within the guide refresh interval is used
     * without asking the provider; an older one stands in only when the provider fails.
     */
    private suspend fun loadSource(source: LiveTvSource, useSaved: Boolean = false): Pair<LoadedSource, LiveTvError?> {
        val savedFile = savedListFile(source)
        val saved = if (savedFile != null && useSaved) withContext(Dispatchers.IO) { LiveTvListCache.read(savedFile, source) } else null
        val nowMs = System.currentTimeMillis()
        val fetched = if (saved != null && nowMs - saved.savedAtMs in 0 until EPG_DOWNLOAD_MS) {
            FetchedSource(saved.channels, saved.epgUrls, saved.groupOrder, notice = null)
        } else {
            try {
                fetchSource(source).also { fresh ->
                    // Only a complete list is saved; writing it does not hold up showing it.
                    if (savedFile != null && fresh.notice == null) {
                        val entry = LiveTvListCache.Entry(fresh.channels, fresh.epgUrls, fresh.providerOrder, nowMs)
                        scope.launch(Dispatchers.IO) { LiveTvListCache.write(savedFile, source, entry) }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (saved == null) throw error
                // The provider is down or slow: its last list shows, with the error beside the source.
                Log.w(TAG, "Live TV source ${source.type} failed, showing its saved list", error)
                FetchedSource(saved.channels, saved.epgUrls, saved.groupOrder, notice = (error as? LiveTvException)?.error ?: fallbackError(source.type))
            }
        }
        return tagSource(source, fetched)
    }

    /** Asks [source]'s provider (or reads its imported file) for its channels. */
    private suspend fun fetchSource(source: LiveTvSource): FetchedSource {
        var notice: LiveTvError? = null
        var providerOrder: List<String> = emptyList()
        val (channels, epgUrls) = when (source.type) {
            LiveTvSourceType.M3u -> {
                val file = withContext(Dispatchers.IO) { storage?.playlistFile(source.id) }
                val playlist = when {
                    // An imported file; a source edited to a link loads the link.
                    file != null && !source.url.isHttpUrl() -> withContext(Dispatchers.IO) { file.bufferedReader().useLines { parseM3uPlaylist(it) } }
                    source.url.isHttpUrl() -> fetchM3u(source.url, source.userAgent)
                    else -> throw LiveTvException(if (source.url.startsWith("http", ignoreCase = true)) LiveTvError.InvalidUrl else LiveTvError.FileEmpty)
                }
                if (playlist.channels.isEmpty()) {
                    throw LiveTvException(if (file != null) LiveTvError.FileNoChannels else LiveTvError.NoChannels)
                }
                // An Xtream panel's M3U link names no guide more often than not; the panel still has one.
                val guides = playlist.epgUrls.ifEmpty { listOfNotNull(xtreamGuideUrlFor(source.url)) }
                playlist.channels to guides
            }
            LiveTvSourceType.Xtream -> {
                val settings = source.xtream
                val loaded = LiveTvXtream.channels(settings, source.userAgent)
                val channels = loaded.channels
                providerOrder = loaded.groupOrder
                if (channels.isEmpty()) throw LiveTvException(LiveTvError.XtreamNoChannels)
                // Xtream providers publish their guide at xmltv.php.
                channels to listOf("${settings.serverUrl}/xmltv.php?username=${settings.username.urlEncoded()}&password=${settings.password.urlEncoded()}")
            }
            LiveTvSourceType.Stalker -> {
                // As play and the guide use it (a MAC typed without colons, formatted).
                val (channels, incomplete, genres) = LiveTvStalker.channels(source.stalker.normalized())
                providerOrder = genres
                if (channels.isEmpty()) throw LiveTvException(LiveTvError.StalkerNoChannels)
                if (incomplete) notice = LiveTvError.StalkerIncomplete
                // The portal's own guide (see [stalkerGuideLink]).
                channels to listOf(stalkerGuideLink(source))
            }
        }
        return FetchedSource(channels, epgUrls, providerOrder, notice)
    }

    /** [fetched]'s channels tagged with [source], its guide links and category order. */
    private fun tagSource(source: LiveTvSource, fetched: FetchedSource): Pair<LoadedSource, LiveTvError?> {
        val channels = fetched.channels
        val epgUrls = fetched.epgUrls
        val providerOrder = fetched.providerOrder
        val notice = fetched.notice
        // Ids only need to be unique within a source; the list keys on them across all of them.
        val tagged = ArrayList<LiveTvChannel>(channels.size)
        val stalker = source.type == LiveTvSourceType.Stalker
        // The source's own user agent replaces the default one; a channel's own (#EXTVLCOPT) stays.
        val agent = source.userAgent.trim().takeIf { it.isNotEmpty() && !stalker }
        // Channels share their header maps (the parser keeps one per distinct set): so do the
        // maps with the source's agent, instead of one per channel.
        val withAgent = HashMap<Map<String, String>, Map<String, String>>()
        channels.forEach { channel ->
            val group = channel.group.trim()
            // Portal channels without a guide id get one from their portal id, which the portal's guide uses.
            val tvgId = if (stalker && channel.tvgId.isNullOrBlank()) "stalker.${source.id}.${channel.id}".lowercase() else channel.tvgId
            val headers = if (agent != null && channel.streamUrl.isHttpUrl() &&
                // A channel's own agent (any spelling of the name) stays.
                channel.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                    .let { it == null || it == LIVE_TV_STREAM_HEADERS["User-Agent"] }
            ) {
                withAgent.getOrPut(channel.headers) { withLiveTvUserAgent(channel.headers, agent) }
            } else {
                channel.headers
            }
            tagged += channel.copy(
                headers = headers,
                id = "${source.id}/${channel.id}",
                sourceId = source.id,
                group = group,
                tvgId = tvgId,
                hideKey = liveTvHideKey(source.id, group, channel.name),
                guideKey = liveTvGuideKey(tvgId, channel.name, source.id),
            )
        }
        // A guide link the viewer added comes first: it is what they chose over the source's own.
        val guides = (listOfNotNull(source.epgUrl.trim().takeIf(String::isNotEmpty)) + epgUrls).distinct()
        // The provider's category order, keeping only categories with channels, then any it did not list, as they appear.
        val used = LinkedHashSet<String>()
        tagged.forEach { used += it.group }
        val order = ArrayList<String>(used.size)
        providerOrder.forEach { if (it in used && it !in order) order += it }
        if (order.size < used.size) {
            val placed = order.toHashSet()
            used.forEach { if (placed.add(it)) order += it }
        }
        return LoadedSource(tagged, guides, order) to notice
    }

    /**
     * A Stalker portal's guide, which is not a link but the portal's `get_epg_info`: a name for it
     * in the guide list, changing with the portal and MAC so an edited source downloads it again.
     */
    private fun stalkerGuideLink(source: LiveTvSource): String {
        val login = "${source.stalker.portalUrl.lowercase()}|${source.stalker.macAddress}"
        return "$STALKER_GUIDE_PREFIX${source.id}:${Integer.toHexString(login.hashCode())}"
    }

    /**
     * After an edit changed a source's links: favourites and the last channel of that source
     * move to the same channel's new link (same category and name, or for Xtream and Stalker the
     * same provider id). Runs on [serial].
     */
    private fun carryOverFavorites(source: LiveTvSource, before: LoadedSource, after: LoadedSource, store: LiveTvStorage) {
        val state = _uiState.value
        val recent = state.recentChannel
        val listed = state.customLists.flatMapTo(HashSet()) { it.urls }
        val wanted = before.channels.filter { it.streamUrl in state.favoriteUrls || it.streamUrl in listed || it.streamUrl == recent?.streamUrl }
        if (wanted.isEmpty()) return
        val byKey = HashMap<Long, LiveTvChannel>(after.channels.size * 2)
        after.channels.forEach { byKey.putIfAbsent(it.hideKey, it) }
        val byId = if (source.type == LiveTvSourceType.M3u) emptyMap() else after.channels.associateBy { it.id }
        val moved = HashMap<String, LiveTvChannel>()
        wanted.forEach { old ->
            val now = byKey[old.hideKey] ?: byId[old.id] ?: return@forEach
            if (now.streamUrl != old.streamUrl) moved[old.streamUrl] = now
        }
        if (moved.isEmpty()) return
        saveMoved(moved, store)
    }

    /**
     * Favourites, playlists and the last channel moved to their new links. Changed on the
     * current state, so an edit made while the source loaded is not lost.
     */
    private fun saveMoved(moved: Map<String, LiveTvChannel>, store: LiveTvStorage) {
        var favorites: Set<String> = emptySet()
        var lists: List<LiveTvCustomList> = emptyList()
        var newRecent: LiveTvRecentChannel? = null
        _uiState.update { state ->
            favorites = state.favoriteUrls.mapTo(HashSet()) { moved[it]?.streamUrl ?: it }
            lists = state.customLists.movedTo(moved)
            newRecent = state.recentChannel?.let { r -> moved[r.streamUrl]?.let { r.copy(streamUrl = it.streamUrl, logoUrl = it.logoUrl) } ?: r }
            state.copy(favoriteUrls = favorites, customLists = lists, recentChannel = newRecent)
        }
        scope.launch(writer) {
            store.saveFavoriteUrls(favorites)
            store.saveCustomLists(lists)
            newRecent?.let(store::saveRecentChannel)
        }
    }

    /**
     * Favourites and the last channel of [old] (an Xtream login, or its get.php list) follow
     * [after]'s channels with the same stream id, for a source edited before its list loaded.
     */
    private fun carryOverXtreamFavorites(old: LiveTvSource, after: LoadedSource, store: LiveTvStorage) {
        val login = xtreamLoginOf(old) ?: return
        val state = _uiState.value
        val recent = state.recentChannel
        fun matches(url: String): Boolean = LiveTvCatchupLinks.xtreamLogin(url)?.let { (server, user, pass) ->
            server.trimEnd('/').equals(login.first.trimEnd('/'), ignoreCase = true) && user == login.second && pass == login.third
        } == true
        val wanted = (state.favoriteUrls + state.customLists.flatMap { it.urls } + listOfNotNull(recent?.streamUrl)).filter(::matches)
        if (wanted.isEmpty()) return
        val byFile = HashMap<String, LiveTvChannel>(after.channels.size * 2)
        after.channels.forEach { byFile.putIfAbsent(it.streamUrl.substringBefore('?').substringAfterLast('/').substringBefore('.'), it) }
        val moved = HashMap<String, LiveTvChannel>()
        wanted.forEach { url ->
            val now = byFile[url.substringBefore('?').substringAfterLast('/').substringBefore('.')] ?: return@forEach
            if (now.streamUrl != url) moved[url] = now
        }
        if (moved.isEmpty()) return
        saveMoved(moved, store)
    }

    /**
     * Whether [channel] comes from an Xtream panel, which answers its own /timeshift/ replays: an
     * Xtream source, an M3U from its get.php, or any list whose links are a panel's /live/ links.
     */
    private fun isXtreamPanel(channel: LiveTvChannel, source: LiveTvSource?): Boolean =
        source == null || source.type == LiveTvSourceType.Xtream ||
            (source.type == LiveTvSourceType.M3u &&
                (xtreamGuideUrlFor(source.url) != null || LiveTvCatchupLinks.isXtreamLiveLink(channel.streamUrl)))

    /** The server, user and password of an Xtream source, or of an M3U get.php link. */
    private fun xtreamLoginOf(source: LiveTvSource): Triple<String, String, String>? = when (source.type) {
        LiveTvSourceType.Xtream -> source.xtream.takeIf { it.isConfigured }?.let { Triple(it.serverUrl, it.username, it.password) }
        LiveTvSourceType.M3u -> source.url.toHttpUrlOrNull()?.takeIf { it.encodedPath.endsWith("/get.php", ignoreCase = true) }?.let { url ->
            val user = url.queryParameter("username") ?: return@let null
            val pass = url.queryParameter("password") ?: return@let null
            val folder = url.encodedPath.dropLast("/get.php".length)
            Triple(url.newBuilder().encodedPath(folder.ifEmpty { "/" }).query(null).build().toString().trimEnd('/'), user, pass)
        }
        LiveTvSourceType.Stalker -> null
    }

    private suspend fun fetchM3u(url: String, userAgent: String = ""): ParsedM3uPlaylist {
        if (url.looksLikeDirectVideoUrl()) return ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList())
        // Kodi style "list.m3u|User-Agent=…": the options are headers, not part of the link.
        // Only when what follows "|" reads as header options: a "|" inside a password stays in the link.
        val options = url.substringAfterLast('|', "")
        val isOptions = options.isNotEmpty() && options.split('&').all { entry ->
            val key = entry.substringBefore('=', "").trim()
            key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' } && entry.contains('=')
        }
        val link = if (isOptions) url.substringBeforeLast('|').trim() else url.trim()
        val linkHeaders = if (!isOptions) emptyMap() else options.split('&').mapNotNull { entry ->
            val key = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
                .let { if ('%' in it) runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) else it }
            if (key.isBlank() || value.isBlank() || value.any { it !in ' '..'~' }) null else key to value
        }.toMap()
        val headers = withLiveTvUserAgent(LIVE_TV_PLAYLIST_HEADERS + linkHeaders, userAgent)
        // Panels that build get.php on request may wait a long time before the first byte.
        val parsed = LiveTvHttp.stream(link, headers, PLAYLIST_READ_TIMEOUT_S) { input ->
            val buffered = input as? java.io.BufferedInputStream ?: java.io.BufferedInputStream(input, 64 * 1024)
            if (buffered.isMpegTs()) return@stream null
            parseM3uPlaylist(buffered.bufferedReader().lineSequence(), baseUrl = link)
        }
        // A stream link without a video extension (…/live/user/pass/123): one channel, never read as a list.
        return if (parsed == null || parsed.isHlsStream) ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList()) else parsed
    }

    /** MPEG-TS packets (sync byte 0x47 every 188 bytes): a live stream, not a playlist. */
    private fun java.io.BufferedInputStream.isMpegTs(): Boolean {
        mark(TS_PACKET * 2 + 1)
        return try {
            val head = ByteArray(TS_PACKET * 2 + 1)
            var read = 0
            while (read < head.size) {
                val count = read(head, read, head.size - read)
                if (count < 0) break
                read += count
            }
            read == head.size && head[0] == TS_SYNC && head[TS_PACKET] == TS_SYNC && head[TS_PACKET * 2] == TS_SYNC
        } finally {
            reset()
        }
    }

    /**
     * Shows the channels of every saved source as one list, and (re)starts the guide when its
     * inputs changed. Runs on [serial].
     */
    private fun publish() {
        val sources = _uiState.value.sources
        val parts = sources.mapNotNull { loaded[it.id] }
        val channels = ArrayList<LiveTvChannel>(parts.sumOf { it.channels.size })
        parts.forEach { channels.addAll(it.channels) }
        val groupCounts = HashMap<String, Int>()
        val sourceCounts = HashMap<String, Int>()
        channels.forEach { channel ->
            // Channels without a category count under "Uncategorised", so they can be hidden too.
            groupCounts[channel.group] = (groupCounts[channel.group] ?: 0) + 1
            sourceCounts[channel.sourceId] = (sourceCounts[channel.sourceId] ?: 0) + 1
        }
        providerGroupOrder = parts.flatMap { it.groupOrder }.distinct()
        val groups = orderedGroups(groupCounts.keys)
        val epgUrls = parts.flatMap { it.epgUrls }.distinct()
        val guideKeys = channels.mapTo(HashSet(channels.size * 2)) { it.guideKey }
        val hasGuide = epgUrls.isNotEmpty() && guideKeys.isNotEmpty()
        val guideInput = Triple(epgUrls, guideKeys, liveTvGuideMatchingKey(channels))
        val guideChanged = epgKey != guideInput || epgJob?.isActive != true
        _uiState.update {
            it.copy(
                channels = channels,
                shownChannels = shownChannels(channels, it.hiddenGroups, it.hiddenChannelKeys),
                groups = groups,
                groupCounts = groupCounts,
                sourceCounts = sourceCounts,
                // What is on now stays shown until the guide is read again.
                currentProgrammes = if (hasGuide) it.currentProgrammes else emptyMap(),
                guideLogos = if (hasGuide) it.guideLogos else emptyMap(),
                isEpgLoading = hasGuide && (guideChanged || it.isEpgLoading),
                isLoaded = true,
                // A source's guide shows as loading until the next read says how it did.
                sourceGuides = sources.associate { source ->
                    val links = loaded[source.id]?.epgUrls
                    source.id to when {
                        links == null -> it.sourceGuides[source.id] ?: LiveTvSourceGuide.Loading
                        links.isEmpty() -> LiveTvSourceGuide.None
                        guideChanged -> LiveTvSourceGuide.Loading
                        else -> it.sourceGuides[source.id] ?: LiveTvSourceGuide.Loading
                    }
                },
            )
        }
        if (!hasGuide) {
            stopEpg()
            epgKey = null
            pendingEpg = null
        } else if (guideChanged) {
            epgKey = guideInput
            // Each source that finishes changes the channels: read the guide once, after the last,
            // so finished sources don't cancel and restart downloads already under way.
            if (sourceJobs.values.any { it.isActive }) {
                pendingEpg = Triple(epgUrls, channels, guideKeys)
            } else {
                pendingEpg = null
                startEpg(epgUrls, channels, guideKeys)
            }
        }
    }

    /** Recomputes the channels zapping moves through after a category or channel was hidden or shown. */
    private fun refreshShownChannels() {
        scope.launch(serial) {
            val state = _uiState.value
            val shown = shownChannels(state.channels, state.hiddenGroups, state.hiddenChannelKeys)
            _uiState.update { current ->
                if (current.channels === state.channels && current.hiddenGroups === state.hiddenGroups &&
                    current.hiddenChannelKeys === state.hiddenChannelKeys
                ) {
                    current.copy(shownChannels = shown)
                } else {
                    current
                }
            }
        }
    }

    private fun shownChannels(channels: List<LiveTvChannel>, hiddenGroups: Set<String>, hiddenKeys: Set<Long>): List<LiveTvChannel> =
        if (hiddenGroups.isEmpty() && hiddenKeys.isEmpty()) {
            channels
        } else {
            channels.filter { it.group !in hiddenGroups && it.hideKey !in hiddenKeys }
        }

    // endregion

    // region Cloud sync (reshaped/sync)

    /** What [profileId] has now: the shown state when it is loaded, else the saved files. */
    internal suspend fun syncSnapshot(context: Context, profileId: Int): LiveTvSyncData {
        awaitProfileLoad()
        val shown = withContext(serial) {
            if (loadedProfileId != profileId || storage == null) return@withContext null
            val state = _uiState.value
            LiveTvSyncData(
                sources = state.sources.filter { it.isSyncable },
                favorites = state.favoriteUrls,
                customLists = state.customLists.associateBy { it.id },
                hiddenGroups = state.hiddenGroups,
                hiddenChannels = state.hiddenChannelKeys,
                groupNames = state.groupNames,
                groupOrder = groupOrder,
                sourceGroupOrders = state.sourceGroupOrders,
                recent = state.recentChannel,
                sourceOrder = state.sources.filter { it.isSyncable }.map { it.identity },
            )
        }
        return shown ?: withContext(writer) { LiveTvStorage(context.applicationContext, profileId).syncData() }
    }

    /** Lets a profile load that is reading the saved files finish first (it runs on [serial]). */
    private suspend fun awaitProfileLoad() {
        withContext(serial) { profileJob }?.join()
    }

    /** Sources the viewer removed on [profileId] that sync has yet to send (see [LiveTvStorage.syncRemovedSources]). */
    internal suspend fun syncRemovedSources(context: Context, profileId: Int): Set<String> =
        withContext(writer) { LiveTvStorage(context.applicationContext, profileId).syncRemovedSources() }

    internal suspend fun clearSyncRemoved(context: Context, profileId: Int, identities: Collection<String>) {
        if (identities.isEmpty()) return
        withContext(writer) { LiveTvStorage(context.applicationContext, profileId).clearSyncRemoved(identities) }
    }

    /** The id [profileId] gives the source with [identity] (after a sync), or null. */
    internal suspend fun sourceIdFor(context: Context, profileId: Int, identity: String): String? {
        val shown = withContext(serial) {
            if (loadedProfileId != profileId || storage == null) null
            else _uiState.value.sources.firstOrNull { it.identity == identity }?.id
        }
        return shown ?: withContext(writer) { LiveTvStorage(context.applicationContext, profileId).sources().firstOrNull { it.identity == identity }?.id }
    }

    /** Loads [sourceId] of [profileId] again when it is shown (its playlist file was replaced by sync). */
    internal suspend fun reloadSource(profileId: Int, sourceId: String) {
        withContext(serial) {
            if (loadedProfileId != profileId || storage == null) return@withContext
            _uiState.value.sources.firstOrNull { it.id == sourceId }?.let { launchSourceLoad(it, adding = false) }
        }
    }

    /**
     * Makes the change sync brought in ([before] to [after]) on top of what [profileId] has now,
     * saves it, and shows it when the profile is loaded. Only changed sources load again.
     */
    internal suspend fun applySync(context: Context, profileId: Int, before: LiveTvSyncData, after: LiveTvSyncData) {
        if (before == after) return
        val appContext = context.applicationContext
        // A load of this profile starting while the files are written would show (and later save)
        // what it read before: then the change is made on the loaded state instead.
        repeat(2) {
            // Saved files changed while a profile load is reading them would be undone by that load.
            awaitProfileLoad()
            if (applySyncShown(profileId, before, after) || applySyncSaved(appContext, profileId, before, after)) return
        }
    }

    private suspend fun applySyncShown(profileId: Int, before: LiveTvSyncData, after: LiveTvSyncData): Boolean =
        withContext(serial) {
            val store = storage
            if (loadedProfileId != profileId || store == null) return@withContext false
            val oldSources = _uiState.value.sources
            _uiState.update { state ->
                state.copy(
                    sources = state.sources.withSyncChange(before.sources, after.sources, store::newSourceId)
                        .withSyncOrder(before, after),
                    favoriteUrls = state.favoriteUrls.withSyncChange(before.favorites, after.favorites),
                    customLists = state.customLists.withSyncChange(before.customLists, after.customLists),
                    hiddenGroups = state.hiddenGroups.withSyncChange(before.hiddenGroups, after.hiddenGroups),
                    hiddenChannelKeys = state.hiddenChannelKeys.withSyncChange(before.hiddenChannels, after.hiddenChannels),
                    groupNames = state.groupNames.withSyncChange(before.groupNames, after.groupNames),
                    sourceGroupOrders = state.sourceGroupOrders.withSyncChange(before.sourceGroupOrders, after.sourceGroupOrders),
                    recentChannel = if (before.recent != after.recent) after.recent else state.recentChannel,
                )
            }
            if (before.groupOrder != after.groupOrder) groupOrder = after.groupOrder
            _uiState.update { it.copy(groups = orderedGroups(it.groupCounts.keys, it.groupNames)) }
            val state = _uiState.value
            val sources = state.sources
            if (sources != oldSources) {
                val kept = sources.associateBy { it.id }
                val removed = oldSources.filter { it.id !in kept }
                removed.forEach { gone ->
                    sourceJobs.remove(gone.id)?.cancel()
                    loaded.remove(gone.id)
                }
                // A removed imported playlist's file goes with it, as when it is removed here.
                scope.launch(writer) { removed.forEach { store.deletePlaylistFile(it.id) } }
                LiveTvStalker.clearSession()
                val oldById = oldSources.associateBy { it.id }
                sources.filter { oldById[it.id] != it }.forEach { launchSourceLoad(it, adding = false) }
                publish()
                updateLoading()
            } else {
                refreshShownChannels()
            }
            val order = groupOrder
            scope.launch(writer) {
                store.saveSources(sources)
                store.saveFavoriteUrls(state.favoriteUrls)
                store.saveCustomLists(state.customLists)
                store.saveHiddenGroups(state.hiddenGroups)
                store.saveHiddenChannelKeys(state.hiddenChannelKeys)
                store.saveGroupNames(state.groupNames)
                store.saveSourceGroupOrders(state.sourceGroupOrders)
                store.saveGroupOrder(order)
                state.recentChannel?.let(store::saveRecentChannel)
            }
            true
        }

    /** Writes the change to the saved files of [profileId]; false when a load of it has begun meanwhile. */
    private suspend fun applySyncSaved(appContext: Context, profileId: Int, before: LiveTvSyncData, after: LiveTvSyncData): Boolean =
        withContext(writer) {
            // The load reads on [writer] too: begun now means it reads after this check, so it waits.
            if (loadedProfileId == profileId) return@withContext false
            val store = LiveTvStorage(appContext, profileId)
            val sources = store.sources()
            val synced = sources.withSyncChange(before.sources, after.sources, store::newSourceId).withSyncOrder(before, after)
            store.saveSources(synced)
            val kept = synced.mapTo(HashSet()) { it.id }
            sources.filter { it.id !in kept }.forEach { store.deletePlaylistFile(it.id) }
            store.saveFavoriteUrls(store.favoriteUrls().withSyncChange(before.favorites, after.favorites))
            store.saveCustomLists(store.customLists().withSyncChange(before.customLists, after.customLists))
            store.saveHiddenGroups(store.hiddenGroups().withSyncChange(before.hiddenGroups, after.hiddenGroups))
            store.saveHiddenChannelKeys(store.hiddenChannelKeys().withSyncChange(before.hiddenChannels, after.hiddenChannels))
            store.saveGroupNames(store.groupNames().withSyncChange(before.groupNames, after.groupNames))
            store.saveSourceGroupOrders(store.sourceGroupOrders().withSyncChange(before.sourceGroupOrders, after.sourceGroupOrders))
            if (before.groupOrder != after.groupOrder) store.saveGroupOrder(after.groupOrder)
            if (before.recent != after.recent) after.recent?.let(store::saveRecentChannel)
            true
        }

    /** Sync's new source order, when it brought one: sorting only, every source stays. */
    private fun List<LiveTvSource>.withSyncOrder(before: LiveTvSyncData, after: LiveTvSyncData): List<LiveTvSource> =
        if (after.sourceOrder.isNotEmpty() && after.sourceOrder != before.sourceOrder) inSyncOrder(after.sourceOrder) else this

    // endregion

    // region Guide

    /** The next programme after the one on now for [guideKey], from the kept guide; a map lookup. */
    fun nextProgramme(guideKey: String, nowEpochMs: Long = LiveTvClock.nowEpochMs()): LiveTvProgramme? =
        keptSchedule[guideKey]?.firstOrNull { it.startEpochMs > nowEpochMs }

    /**
     * Every kept programme of [guideKey], earliest first: the last few hours and the next ones, and
     * before them the older days of a catch-up channel once they were looked at ([requestHistory]).
     */
    fun schedule(guideKey: String): List<LiveTvProgramme> {
        val kept = keptSchedule[guideKey].orEmpty()
        synchronized(history) {
            val entry = history[guideKey] ?: return kept
            if (entry.kept === kept) return entry.merged
            return HistoryEntry(entry.older, kept).also { history[guideKey] = it }.merged
        }
    }

    /**
     * A channel's archived days ([LiveTvArchive]) and what its Xtream panel lists before them, in
     * front of the kept guide ([kept]): the guide's own programmes win where they meet.
     */
    private class HistoryEntry(val older: List<LiveTvProgramme>, val kept: List<LiveTvProgramme>) {
        val merged: List<LiveTvProgramme> = run {
            val first = kept.firstOrNull()?.startEpochMs ?: Long.MAX_VALUE
            val before = older.filter { it.stopEpochMs <= first }
            if (before.isEmpty()) kept else before + kept
        }
    }

    /** The channels whose older days were looked at last; a few, so memory stays small. */
    private val history = object : LinkedHashMap<String, HistoryEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HistoryEntry>?): Boolean =
            size > if (guideWindow === LiveTvGuideWindow.LowMemory) HISTORY_CHANNELS_LOW_MEMORY else HISTORY_CHANNELS
    }
    private val historyLoading = HashSet<String>()
    /** Panels are asked for a channel's past at most two at a time, however fast the rows go by. */
    private val historyFetches = Semaphore(2)

    /** Whether [channel]'s older days were read ([schedule] has them), or it has none to read. */
    fun hasHistory(channel: LiveTvChannel): Boolean =
        channel.catchup == null || synchronized(history) { channel.guideKey in history }

    /**
     * Reads [channel]'s older days, when it has catch-up, in the background: its archived days, and
     * from its Xtream panel what neither the archive nor the guide reaches. The guide then shows them
     * ([LiveTvUiState.guideVersion] goes up). Cheap to call again: once read, or while read, it does nothing.
     */
    fun requestHistory(channel: LiveTvChannel) {
        val catchup = channel.catchup ?: return
        val key = channel.guideKey
        synchronized(history) {
            if (key in history || !historyLoading.add(key)) return
        }
        scope.launch(Dispatchers.IO) {
            val older = try {
                loadHistory(channel, catchup)
            } catch (cancel: CancellationException) {
                synchronized(history) { historyLoading -= key }
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Older programmes failed", error)
                emptyList()
            }
            synchronized(history) {
                historyLoading -= key
                history[key] = HistoryEntry(older, keptSchedule[key].orEmpty())
            }
            if (older.isNotEmpty()) _uiState.update { it.copy(guideVersion = it.guideVersion + 1) }
        }
    }

    private suspend fun loadHistory(channel: LiveTvChannel, catchup: LiveTvCatchup): List<LiveTvProgramme> {
        val archived = LiveTvArchive.read(archiveFile(), channel.guideKey)
        val nowMs = LiveTvClock.nowEpochMs()
        val reach = nowMs - LiveTvArchive.pastMsFor(catchup.days)
        val earliest = minOf(
            archived.firstOrNull()?.startEpochMs ?: Long.MAX_VALUE,
            keptSchedule[channel.guideKey]?.firstOrNull()?.startEpochMs ?: Long.MAX_VALUE,
        )
        if (earliest - reach < HISTORY_GAP_MS) return archived
        // An Xtream panel lists each channel's whole catch-up, which its guide file often does not.
        val source = _uiState.value.sources.firstOrNull { it.id == channel.sourceId }
        val xtreamPanel = isXtreamPanel(channel, source)
        if (!LiveTvCatchupLinks.needsPanelZone(channel.streamUrl, catchup, xtreamPanel)) return archived
        val (server, user, pass) = LiveTvCatchupLinks.xtreamLogin(channel.streamUrl) ?: return archived
        val streamId = LiveTvCatchupLinks.xtreamStreamId(channel.streamUrl) ?: return archived
        val listed = historyFetches.withPermit {
            withTimeoutOrNull(HISTORY_FETCH_MS) {
                runCatching { LiveTvXtream.pastProgrammes(server, user, pass, streamId, reach, source?.userAgent.orEmpty()) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
            }
        }.orEmpty()
        val before = liveTvWithoutOverlaps(listed).filter { it.stopEpochMs <= earliest }
        return if (before.isEmpty()) archived else before + archived
    }

    /** Each profile keeps its own channels' past days. */
    private fun archiveFile(): File = File(appContext.filesDir, "live_tv/guide_archive_${loadedProfileId ?: 0}.bin")

    private fun spillDir(): File = File(appContext.cacheDir, "live_tv_spill")

    /** After the archive changed: the channels looked at read their older days again. */
    private fun reloadHistory() {
        val channels = synchronized(history) {
            val keys = history.keys.toSet()
            history.clear()
            keys
        }
        if (channels.isEmpty()) return
        _uiState.value.channels.filter { it.guideKey in channels }.distinctBy { it.guideKey }.forEach(::requestHistory)
    }

    private fun stopEpg() {
        synchronized(history) { history.clear() }
        keptSchedule = emptyMap()
        epgGeneration++
        epgJob?.cancel()
        epgJob = null
    }

    /**
     * Reads the guide and moves each channel's "now playing" on every minute. Only runs while
     * something shows Live TV (the list or a channel in the player) and catches up when it is
     * opened again. The guide is saved compressed in the cache, downloaded again every 10 hours
     * (or on Refresh), and re-read from there whenever channels run out of kept programmes. A
     * guide that fails is tried again sooner, without holding back the ones that loaded.
     */
    private fun startEpg(epgUrls: List<String>, channels: List<LiveTvChannel>, guideKeys: Set<String>) {
        val window = if (LiveTvDevice.isLowMemory(appContext)) LiveTvGuideWindow.LowMemory else LiveTvGuideWindow.Regular
        // The last good guide stays visible while a refresh runs, on weak TVs too: their kept
        // window is small, and they read one guide at a time below.
        epgGeneration++
        epgJob?.cancel()
        val generation = epgGeneration
        val guideFiles = epgUrls.map { File(guideDir(), "guide_${Integer.toHexString(it.hashCode())}.xml.gz") }
        val cacheFile = File(guideDir(), LiveTvGuideCache.FILE_NAME)
        val catchupKeys = channels.mapNotNullTo(HashSet()) { channel -> channel.guideKey.takeIf { channel.catchup != null } }
        // A channel two sources list with the same link (a provider's and an edited copy of its
        // playlist) is one channel: the copy whose guide has nothing shows the other's.
        val sameStream = liveTvSameStreamKeys(channels)
        // Names (and missing logos) decide name matches and guide logos: a list that renames
        // channels keeping their ids must not be served the matches kept for the old names.
        val cacheKey = LiveTvGuideCache.key(epgUrls, guideKeys, window, catchupKeys) * 31 + liveTvGuideMatchingKey(channels)
        guideWindow = window
        synchronized(history) { history.clear() }
        // Which sources each guide belongs to, and how a portal's guide is fetched. Read here, on [serial].
        val sourceLinks = _uiState.value.sources.associate { it.id to loaded[it.id]?.epgUrls.orEmpty() }
        val sourcesById = _uiState.value.sources.associateBy { it.id }
        val aheadHours = (window.aheadMs / (60L * 60 * 1000)).toInt()
        // A guide is fetched with the user agent of a source listing it that was given one.
        val guideHeaders = epgUrls.map { url ->
            val agent = _uiState.value.sources.firstOrNull { source ->
                source.userAgent.isNotBlank() && url in sourceLinks[source.id].orEmpty()
            }?.userAgent.orEmpty()
            withLiveTvUserAgent(LIVE_TV_STREAM_HEADERS, agent)
        }
        val downloads: List<suspend (File) -> Unit> = epgUrls.mapIndexed { index, url ->
            val portal = url.takeIf { it.startsWith(STALKER_GUIDE_PREFIX) }
                ?.let { sourcesById[it.removePrefix(STALKER_GUIDE_PREFIX).substringBefore(':')] }
            val fetch: suspend (File) -> Unit = if (portal != null) {
                { file ->
                    // The portal's channel id (after "<source>/") to its raw XMLTV id.
                    val ids = HashMap<String, String>()
                    channels.forEach { channel ->
                        if (channel.sourceId == portal.id) channel.tvgId?.let { ids[channel.id.substringAfter('/')] = it }
                    }
                    LiveTvStalker.downloadGuide(portal.stalker, ids, aheadHours, file)
                }
            } else {
                { file -> LiveTvHttp.download(url, guideHeaders[index], file, LiveTvHttp.GUIDE_READ_TIMEOUT_S, expectXml = true) }
            }
            fetch
        }
        epgJob = scope.launch {
            withContext(Dispatchers.IO) {
                // Guides of an earlier source.
                guideDir().listFiles()?.filter { it !in guideFiles && it != cacheFile }?.forEach(File::delete)
            }
            var schedule: LiveTvSchedule = keptSchedule
            // [schedule] with channels sharing a link filled in from each other: what is shown.
            var shown: LiveTvSchedule = keptSchedule
            var nextReadAtMs = 0L
            var firstRead = true
            // Reads in a row with a guide that failed or broke off: only the first retry comes sooner.
            var retries = 0
            // The guide links whose last read failed or broke off: their sources say so.
            var retryingLinks: Set<String> = emptySet()
            // Until then what is on now stays as it is: the minute tick skips working it out again.
            var changeAtMs = 0L
            var lastTickMs = 0L
            val publishGuide = { kept: LiveTvSchedule, logos: Map<String, String>?, nowMs: Long, failedLinks: Set<String>? ->
                val filled = kept.sharedAcrossStreams(sameStream)
                shown = filled
                if (epgGeneration == generation) keptSchedule = filled
                val current = currentProgrammes(filled, guideKeys, nowMs)
                val guides = failedLinks?.let { guideStates(sourceLinks, it, retryingLinks, filled, channels) }
                _uiState.update { state ->
                    if (epgGeneration != generation) {
                        state
                    } else {
                        state.copy(
                            guideLogos = if (logos == null || state.guideLogos == logos) state.guideLogos else logos,
                            guideVersion = state.guideVersion + 1,
                            currentProgrammes = current,
                            sourceGuides = guides?.let { state.sourceGuides + it } ?: state.sourceGuides,
                        )
                    }
                }
            }
            while (isActive) {
                _uiState.subscriptionCount.first { it > 0 }
                val nowMs = LiveTvClock.nowEpochMs()
                if (nowMs >= nextReadAtMs) {
                    // Nothing waits on a guide nobody is looking at: leaving Live TV for a film
                    // stops the download and read instead of letting them compete with playback.
                    val finished = whileSeen {
                        val force = forceGuideDownload
                        // Opening again: the programmes kept by the last full read, while still good.
                        val saved = if (firstRead && !force) {
                            withContext(Dispatchers.IO) { LiveTvGuideCache.read(cacheFile, cacheKey, guideFiles, nowMs, EPG_DOWNLOAD_MS) }
                        } else {
                            null
                        }
                        firstRead = false
                        if (saved != null) {
                            schedule = saved.schedule
                            nextReadAtMs = saved.nextReadAtMs
                            publishGuide(schedule, saved.logos, nowMs, emptySet())
                        } else {
                            // Every feed is matched against every channel, so a playlist without a guide of its own
                            // still finds its channels in another source's guide; the ranks below make a playlist's
                            // own guides win over another source's for its channels. Built for each read and
                            // let go after it: between reads they would only hold memory.
                            val request = LiveTvGuideRequest.from(channels)
                            val sourceForKey = channels.associate { it.guideKey to it.sourceId }
                            val previous = schedule
                            val previousLogos = _uiState.value.guideLogos.filterKeys { it in guideKeys }
                            val loaded = HashMap<String, List<LiveTvProgramme>>()
                            val logos = HashMap<String, String>()
                            val truncated = HashSet<String>()
                            val failedLinks = HashSet<String>()
                            val partialLinks = HashSet<String>()
                            var partial = false
                            val scheduleRanks = HashMap<String, Int>()
                            val logoRanks = HashMap<String, Int>()
                            // Past days of catch-up channels, written to disk while the guides are
                            // read, and the read each channel's programmes came from: taken into the archive.
                            val spills = if (request.catchupKeys.isEmpty()) null else LiveTvArchive.Spills(spillDir())
                            val chosenSpills = HashMap<String, LiveTvArchiveSpill>()
                            try {
                                // Two independent imports at most (one on weak TVs), and a bounded result
                                // queue. A slow first feed no longer prevents a faster provider's guide from showing.
                                loadLiveTvGuides(epgUrls.size, parallel = if (window === LiveTvGuideWindow.LowMemory) 1 else 2, read = { index ->
                                    val epgUrl = epgUrls[index]
                                    readGuide(
                                        downloads[index], epgUrl.takeUnless { it.startsWith(STALKER_GUIDE_PREFIX) },
                                        guideFiles[index], request, nowMs, window, force, guideHeaders[index], spills,
                                    )
                                }, publish = publish@ { index, guide ->
                                    val epgUrl = epgUrls[index]
                                    if (guide == null) {
                                        failedLinks += epgUrl
                                        return@publish
                                    }
                                    if (!guide.complete) {
                                        partial = true
                                        partialLinks += epgUrl
                                    }
                                    if (guide.refreshFailed) failedLinks += epgUrl
                                    // A guide that has the channel's guide id beats one that only found its name, so
                                    // an id the viewer assigned in the playlist is what shows (as in other players);
                                    // then a channel's own playlist's guides first, in its order; then the others'.
                                    fun rank(key: String): Int {
                                        val order = sourceLinks[sourceForKey[key]]?.indexOf(epgUrl)
                                            ?.takeIf { it >= 0 } ?: (epgUrls.size + index)
                                        return if (key in guide.nameMatched) NAME_MATCH_RANK + order else order
                                    }
                                    guide.schedule.forEach { (key, list) ->
                                        val priority = rank(key)
                                        if (priority < (scheduleRanks[key] ?: Int.MAX_VALUE)) {
                                            scheduleRanks[key] = priority
                                            loaded[key] = list
                                            val spill = guide.spill
                                            if (guide.complete && spill != null) chosenSpills[key] = spill else chosenSpills -= key
                                            if (key in guide.truncated) truncated += key else truncated -= key
                                        }
                                    }
                                    guide.logos.forEach { (key, logo) ->
                                        val priority = rank(key)
                                        if (priority < (logoRanks[key] ?: Int.MAX_VALUE)) {
                                            logoRanks[key] = priority
                                            logos[key] = logo
                                        }
                                    }
                                    publishGuide(
                                        HashMap(previous).apply { putAll(loaded) },
                                        HashMap(previousLogos).apply { putAll(logos) }, nowMs, null,
                                    )
                                })
                                val failed = failedLinks.isNotEmpty()
                                if (force && epgGeneration == generation) forceGuideDownload = false
                                // A guide that failed, or broke off part way, keeps what it showed before.
                                schedule = if ((failed || partial) && previous.isNotEmpty()) HashMap(previous).apply { putAll(loaded) } else loaded
                                // Every guide is read again on a retry: one sooner retry, then the usual wait,
                                // so a link that stays down costs weak TVs no more than one extra read.
                                val retryMs = if (retries == 0) EPG_FIRST_RETRY_MS else EPG_RETRY_MS
                                val regular = if (loaded.isEmpty()) {
                                    nowMs + EPG_RETRY_MS
                                } else {
                                    nextScheduleReadAt(loaded, truncated, nowMs, EPG_MIN_READ_GAP_MS, EPG_DOWNLOAD_MS)
                                }
                                nextReadAtMs = if (failed || partial) minOf(regular, nowMs + retryMs) else regular
                                retries = if (failed || partial) retries + 1 else 0
                                retryingLinks = failedLinks + partialLinks
                                val shownLogos = if (failed || partial) HashMap(previousLogos).apply { putAll(logos) } else logos
                                publishGuide(schedule, shownLogos, nowMs, failedLinks)
                                // A guide that broke off part way is not saved as the kept programmes.
                                if (!failed && !partial && loaded.isNotEmpty() && epgGeneration == generation) {
                                    val entry = LiveTvGuideCache.Entry(loaded, logos, nextReadAtMs)
                                    withContext(Dispatchers.IO) { LiveTvGuideCache.write(cacheFile, cacheKey, guideFiles, entry) }
                                }
                                if (spills != null && loaded.isNotEmpty() && epgGeneration == generation) {
                                    val taken = chosenSpills.filterValues { !it.failed }
                                    val archived = withContext(Dispatchers.IO) {
                                        LiveTvArchive.write(archiveFile(), taken, request.catchupKeys, nowMs)
                                    }
                                    if (archived) reloadHistory()
                                }
                            } finally {
                                if (spills != null) withContext(NonCancellable + Dispatchers.IO) { spills.clear() }
                            }
                        }
                        Unit
                    }
                    // It starts over when Live TV is shown again.
                    if (finished == null) continue
                    changeAtMs = 0L
                }
                // The clock set back also works it out again.
                if (nowMs >= changeAtMs || nowMs < lastTickMs) {
                    val current = currentProgrammes(shown, guideKeys, nowMs)
                    changeAtMs = nextProgrammeChange(shown, guideKeys, nowMs)
                    _uiState.update { state ->
                        when {
                            epgGeneration != generation -> state
                            state.currentProgrammes != current || state.isEpgLoading ->
                                state.copy(currentProgrammes = current, isEpgLoading = false)
                            else -> state
                        }
                    }
                } else {
                    _uiState.update { state -> if (epgGeneration == generation && state.isEpgLoading) state.copy(isEpgLoading = false) else state }
                }
                lastTickMs = nowMs
                if (epgGeneration != generation) return@launch
                delay(EPG_TICK_MS)
            }
        }
    }

    /**
     * Runs [block] while something shows Live TV. Once nothing has for [UNSEEN_GRACE_MS] (the list
     * left for Home, a film started), it is cancelled and null is returned; the short grace keeps
     * it going across the step from the list to a channel in the player.
     */
    private suspend fun <T : Any> whileSeen(block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            while (true) {
                _uiState.subscriptionCount.first { it == 0 }
                val back = withTimeoutOrNull(UNSEEN_GRACE_MS) { _uiState.subscriptionCount.first { it > 0 } }
                if (back == null) {
                    work.cancel()
                    return@launch
                }
            }
        }
        try {
            work.await()
        } catch (cancel: CancellationException) {
            // Only this read was stopped; a cancelled guide job carries on cancelling.
            currentCoroutineContext().ensureActive()
            null
        } finally {
            watcher.cancel()
        }
    }

    /**
     * One guide, downloaded when its saved copy is missing, old or [force]d; an old copy still
     * serves when the download fails. Null when there is no guide to read.
     */
    private suspend fun readGuide(
        download: suspend (File) -> Unit,
        /** The guide's link when it is one to fetch (not a portal's): it is read while it downloads. */
        url: String?,
        file: File,
        request: LiveTvGuideRequest,
        nowMs: Long,
        window: LiveTvGuideWindow,
        force: Boolean,
        headers: Map<String, String> = LIVE_TV_STREAM_HEADERS,
        spills: LiveTvArchive.Spills? = null,
    ): LiveTvGuide? {
        try {
            val saved = withContext(Dispatchers.IO) { file.lastModified() }
            val cached = if (!force && saved != 0L && nowMs - saved in 0 until EPG_DOWNLOAD_MS) {
                readXmlTvGuide(file, request, nowMs, window, spills)
            } else {
                null
            }
            // A saved guide that has run out (a provider's file covering less than the refresh
            // interval) is downloaded again rather than leaving the guide empty until then. A
            // portal's guide is small and covers only the hours asked for: fetched again at once.
            val runOutWait = if (url == null) EPG_MIN_READ_GAP_MS else maxOf(EPG_MIN_READ_GAP_MS, EPG_DOWNLOAD_MS / 2)
            if (cached?.canReplaceSavedGuide == true && (cached.schedule.isEmpty() || cached.hasAhead(nowMs) || nowMs - saved < runOutWait)) return cached
            // Also repair a recently saved, incomplete feed from an earlier app version.
            if (force || saved == 0L || nowMs - saved !in 0 until EPG_DOWNLOAD_MS || cached != null) {
                if (url != null) {
                    try {
                        val read = LiveTvHttp.downloadReading(
                            url, headers, file, LiveTvHttp.GUIDE_READ_TIMEOUT_S,
                            read = { input -> readXmlTvGuide(input, request, nowMs, window, spills = spills) },
                            keep = { guide -> guide.canReplaceSavedGuide },
                        )
                        if (read.canReplaceSavedGuide) return read
                        // A cut or malformed response must never replace a usable saved feed.
                        if (saved != 0L) return (cached ?: readXmlTvGuide(file, request, nowMs, window, spills)).afterFailedRefresh()
                        if (read.elements > 0) return read
                        Log.w(TAG, "Guide link gave no guide: ${url.substringBefore('?')}")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        // The link without its query (which can hold a login), so a log says which guide.
                        Log.w(TAG, "Guide download failed: ${url.substringBefore('?')}", error)
                    }
                    // The guide saved before, if any.
                    if (saved == 0L) return null
                    return (cached ?: readXmlTvGuide(file, request, nowMs, window, spills)).afterFailedRefresh()
                }
                try {
                    download(file)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Guide download failed", error)
                    if (saved == 0L) return null
                    return (cached ?: readXmlTvGuide(file, request, nowMs, window, spills)).afterFailedRefresh()
                }
            }
            return readXmlTvGuide(file, request, nowMs, window, spills)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Guide failed", error)
            return null
        } catch (tooLarge: OutOfMemoryError) {
            // A guide too large for this TV fails as that guide (as a too large list does), and
            // the guide carries on with the others instead of stopping for the session.
            Log.w(TAG, "Guide too large", tooLarge)
            return null
        }
    }

    /** How each source's guide did: read for how many of its channels, or failed when every guide link of it did. */
    private fun guideStates(
        sourceLinks: Map<String, List<String>>,
        failedLinks: Set<String>,
        retryingLinks: Set<String>,
        schedule: LiveTvSchedule,
        channels: List<LiveTvChannel>,
    ): Map<String, LiveTvSourceGuide> {
        val counts = HashMap<String, Int>()
        channels.forEach { if (!schedule[it.guideKey].isNullOrEmpty()) counts[it.sourceId] = (counts[it.sourceId] ?: 0) + 1 }
        return sourceLinks.mapValues { (id, links) ->
            val count = counts[id] ?: 0
            when {
                links.isEmpty() -> LiveTvSourceGuide.None
                count == 0 && links.all(failedLinks::contains) -> LiveTvSourceGuide.Failed
                // Shown, but from an older or incomplete read: it is being tried again.
                else -> LiveTvSourceGuide(LiveTvSourceGuide.State.Loaded, count, retrying = links.any(retryingLinks::contains))
            }
        }
    }

    private fun guideDir(): File = File(appContext.cacheDir, "live_tv")

    // endregion
}

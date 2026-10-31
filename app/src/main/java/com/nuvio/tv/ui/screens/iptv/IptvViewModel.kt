package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvContent
import com.nuvio.tv.data.iptv.IptvDataStore
import com.nuvio.tv.data.iptv.IptvGroup
import com.nuvio.tv.data.iptv.IptvGroupIds
import com.nuvio.tv.data.iptv.IptvLoadStatus
import com.nuvio.tv.data.iptv.IptvPlaylist
import com.nuvio.tv.data.iptv.IptvProgramme
import com.nuvio.tv.data.iptv.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@Immutable
data class IptvUiState(
    val isInitialized: Boolean = false,
    val playlists: List<IptvPlaylist> = emptyList(),
    val status: IptvLoadStatus = IptvLoadStatus.Idle,
    val hasAnyChannels: Boolean = false,
    val groups: List<IptvGroup> = emptyList(),
    val selectedGroupId: String = IptvGroupIds.ALL,
    val channels: List<IptvChannel> = emptyList(),
    val epg: Map<String, List<IptvProgramme>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val previewChannel: IptvChannel? = null,
    val focusChannelKey: String? = null
)

@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val dataStore: IptvDataStore
) : ViewModel() {

    private data class Selection(
        val groupId: String?,
        val previewKey: String?,
        val previewStopped: Boolean
    )

    private data class Stored(
        val favorites: Set<String>,
        val recents: List<String>,
        val lastGroup: String?,
        val lastChannel: String?
    )

    private data class Loaded(
        val initialized: Boolean,
        val playlists: List<IptvPlaylist>,
        val content: IptvContent,
        val status: IptvLoadStatus
    )

    private val playlistsState = MutableStateFlow<List<IptvPlaylist>?>(null)
    private val selection = MutableStateFlow(Selection(groupId = null, previewKey = null, previewStopped = false))

    init {
        viewModelScope.launch {
            dataStore.playlists.distinctUntilChanged().collect { playlists ->
                playlistsState.value = playlists
                repository.ensureLoaded(playlists)
            }
        }
    }

    private val loaded = combine(playlistsState, repository.content, repository.status) { playlists, content, status ->
        Loaded(
            initialized = playlists != null,
            playlists = playlists.orEmpty(),
            content = content,
            status = status
        )
    }

    private val stored = combine(
        dataStore.favorites,
        dataStore.recents,
        dataStore.lastGroupFlow,
        dataStore.lastChannelKeyFlow
    ) { favorites, recents, lastGroup, lastChannel ->
        Stored(favorites, recents, lastGroup, lastChannel)
    }

    val uiState: StateFlow<IptvUiState> = combine(loaded, stored, selection) { loaded, stored, selection ->
        buildState(loaded, stored, selection)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IptvUiState())

    private fun buildState(loaded: Loaded, stored: Stored, selection: Selection): IptvUiState {
        val content = loaded.content
        val all = content.channels
        val byKey = HashMap<String, IptvChannel>(all.size)
        all.forEach { byKey[it.key] = it }

        val favoriteChannels = all.filter { it.key in stored.favorites }
        val recentChannels = stored.recents.mapNotNull { byKey[it] }

        val groups = buildList {
            add(IptvGroup(IptvGroupIds.ALL, "", all.size))
            if (favoriteChannels.isNotEmpty()) add(IptvGroup(IptvGroupIds.FAVORITES, "", favoriteChannels.size))
            if (recentChannels.isNotEmpty()) add(IptvGroup(IptvGroupIds.RECENT, "", recentChannels.size))
            addAll(content.groups)
        }

        val requestedGroup = selection.groupId ?: stored.lastGroup ?: IptvGroupIds.ALL
        val groupId = if (groups.any { it.id == requestedGroup }) requestedGroup else IptvGroupIds.ALL
        val channels = when (groupId) {
            IptvGroupIds.ALL -> all
            IptvGroupIds.FAVORITES -> favoriteChannels
            IptvGroupIds.RECENT -> recentChannels
            else -> all.filter { IptvGroupIds.forGroup(it.playlistId, it.group) == groupId }
        }

        val previewKey = if (selection.previewStopped) null else selection.previewKey ?: stored.lastChannel
        val previewChannel = previewKey?.let { byKey[it] }

        return IptvUiState(
            isInitialized = loaded.initialized,
            playlists = loaded.playlists,
            status = loaded.status,
            hasAnyChannels = all.isNotEmpty(),
            groups = groups,
            selectedGroupId = groupId,
            channels = channels,
            epg = content.epg,
            favorites = stored.favorites,
            previewChannel = previewChannel,
            focusChannelKey = selection.previewKey ?: stored.lastChannel
        )
    }

    fun selectGroup(groupId: String) {
        selection.value = selection.value.copy(groupId = groupId)
        viewModelScope.launch { dataStore.setLastGroup(groupId) }
    }

    fun previewChannel(channel: IptvChannel) {
        selection.value = selection.value.copy(previewKey = channel.key, previewStopped = false)
        viewModelScope.launch { dataStore.setLastChannel(channel.key) }
    }

    fun stopPreview() {
        selection.value = selection.value.copy(previewStopped = true)
    }

    fun onWatchFullscreen(channel: IptvChannel) {
        selection.value = selection.value.copy(previewKey = channel.key, previewStopped = false)
        viewModelScope.launch { dataStore.recordWatched(channel.key) }
    }

    fun toggleFavorite(channel: IptvChannel) {
        viewModelScope.launch { dataStore.toggleFavorite(channel.key) }
    }

    fun refresh() {
        viewModelScope.launch {
            val playlists = playlistsState.value ?: dataStore.playlists.first()
            repository.ensureLoaded(playlists, force = true)
        }
    }

    fun savePlaylist(playlist: IptvPlaylist) {
        val resolved = if (playlist.id.isBlank()) playlist.copy(id = UUID.randomUUID().toString()) else playlist
        viewModelScope.launch {
            val existing = playlistsState.value.orEmpty().firstOrNull { it.id == resolved.id }
            if (existing != null && existing != resolved) {
                repository.deleteCache(resolved.id)
            }
            dataStore.upsertPlaylist(resolved)
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            dataStore.removePlaylist(playlistId)
            repository.deleteCache(playlistId)
        }
    }
}

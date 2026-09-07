package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvRepository
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IptvPlaybackRequest(
    val streamUrl: String,
    val headers: Map<String, String>?,
    val title: String
)

data class IptvUiState(
    val isLoading: Boolean = true,
    val error: IptvError? = null,
    val channels: List<IptvChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val searchQuery: String = "",
    val programmesByChannel: Map<String, List<EpgProgramme>> = emptyMap(),
    val playbackRequest: IptvPlaybackRequest? = null
) {
    val visibleChannels: List<IptvChannel>
        get() {
            var list = channels
            if (!selectedGroup.isNullOrBlank()) {
                list = list.filter { it.groupTitle == selectedGroup }
            }
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim()
                list = list.filter { it.name.contains(q, ignoreCase = true) }
            }
            return list
        }
}

enum class IptvError { NOT_CONFIGURED, LOAD_FAILED }

@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvUiState())
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = repository.getChannels(forceRefresh)
            result.fold(
                onSuccess = { channels ->
                    val groups = channels.map { it.groupTitle }.distinct().sorted()
                    _uiState.update {
                        it.copy(isLoading = false, channels = channels, groups = groups, error = null)
                    }
                    val programmes = repository.getProgrammesByChannel(forceRefresh)
                    _uiState.update { it.copy(programmesByChannel = programmes) }
                },
                onFailure = { throwable ->
                    val error = if (throwable.message == "no_playlist_url") {
                        IptvError.NOT_CONFIGURED
                    } else {
                        IptvError.LOAD_FAILED
                    }
                    _uiState.update { it.copy(isLoading = false, error = error) }
                }
            )
        }
    }

    fun selectGroup(group: String?) {
        _uiState.update { it.copy(selectedGroup = group) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun playChannel(channel: IptvChannel) {
        _uiState.update {
            it.copy(
                playbackRequest = IptvPlaybackRequest(
                    streamUrl = channel.streamUrl,
                    headers = channel.headers,
                    title = channel.name
                )
            )
        }
    }

    fun consumePlaybackRequest() {
        _uiState.update { it.copy(playbackRequest = null) }
    }

    /** Current "on now" programme title for a channel, if EPG data is loaded and matches. */
    fun nowPlayingTitle(channel: IptvChannel, nowMillis: Long = System.currentTimeMillis()): String? {
        val epgId = channel.epgId ?: return null
        val programmes = _uiState.value.programmesByChannel[epgId] ?: return null
        return programmes.firstOrNull { nowMillis in it.startMillis until it.stopMillis }?.title
    }

    /** Upcoming programmes for a channel (including the current one), for a guide view. */
    fun upcomingProgrammes(channel: IptvChannel, nowMillis: Long = System.currentTimeMillis()): List<EpgProgramme> {
        val epgId = channel.epgId ?: return emptyList()
        val programmes = _uiState.value.programmesByChannel[epgId] ?: return emptyList()
        return programmes.filter { it.stopMillis > nowMillis }.sortedBy { it.startMillis }
    }
}

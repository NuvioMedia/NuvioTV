package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvRepository
import com.nuvio.tv.data.local.IptvPreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IptvSettingsUiState(
    val playlistUrl: String = "",
    val epgUrl: String = "",
    val isTesting: Boolean = false,
    val testResultMessage: String? = null
)

@HiltViewModel
class IptvSettingsViewModel @Inject constructor(
    private val preferences: IptvPreferencesDataStore,
    private val repository: IptvRepository
) : ViewModel() {

    private val testState = MutableStateFlow(Pair(false, null as String?))

    val uiState: StateFlow<IptvSettingsUiState> = combine(
        preferences.playlistUrl,
        preferences.epgUrl,
        testState
    ) { playlistUrl, epgUrl, (isTesting, message) ->
        IptvSettingsUiState(playlistUrl, epgUrl, isTesting, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IptvSettingsUiState())

    fun savePlaylistUrl(url: String) {
        viewModelScope.launch { preferences.setPlaylistUrl(url) }
    }

    fun saveEpgUrl(url: String) {
        viewModelScope.launch { preferences.setEpgUrl(url) }
    }

    fun testPlaylist() {
        viewModelScope.launch {
            testState.value = true to null
            val result = repository.getChannels(forceRefresh = true)
            testState.value = false to result.fold(
                onSuccess = { "${it.size} canali trovati" },
                onFailure = { "Errore: impossibile leggere la playlist" }
            )
        }
    }
}

package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnimeSkipSettingsViewModel @Inject constructor(
    private val dataStore: AnimeSkipSettingsDataStore
) : ViewModel() {

    private val _clientId = MutableStateFlow("")
    val clientId: StateFlow<String> = _clientId.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.clientId.collectLatest { _clientId.update { _ -> it } }
        }
        viewModelScope.launch {
            dataStore.enabled.collectLatest { _enabled.update { _ -> it } }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(value) }
    }

    fun setClientId(value: String) {
        viewModelScope.launch { dataStore.setClientId(value) }
    }
}

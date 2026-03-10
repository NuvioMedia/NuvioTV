package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.NetworkSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val networkSettingsDataStore: NetworkSettingsDataStore
) : ViewModel() {

    val dnsProvider: StateFlow<Int> = networkSettingsDataStore.dnsProvider.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0
    )

    fun setDnsProvider(provider: Int) {
        viewModelScope.launch {
            networkSettingsDataStore.setDnsProvider(provider)
        }
    }
}

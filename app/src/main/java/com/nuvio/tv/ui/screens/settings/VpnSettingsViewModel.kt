package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.vpn.VpnManager
import com.nuvio.tv.data.local.VpnPreferencesDataStore
import com.nuvio.tv.domain.model.VpnConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VpnSettingsUiState(
    val config: String = "",
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val isCheckingIp: Boolean = false,
    val baselineIp: String? = null,
    val currentIp: String? = null
)

@HiltViewModel
class VpnSettingsViewModel @Inject constructor(
    private val preferences: VpnPreferencesDataStore,
    private val vpnManager: VpnManager
) : ViewModel() {

    private val _ipState = MutableStateFlow(Triple(false, null as String?, null as String?))
    private var connectJob: Job? = null

    val uiState: StateFlow<VpnSettingsUiState> = combine(
        preferences.config,
        vpnManager.connectionState,
        vpnManager.errorMessage,
        _ipState
    ) { config, connectionState, errorMessage, ipState ->
        VpnSettingsUiState(
            config = config,
            connectionState = connectionState,
            errorMessage = errorMessage,
            isCheckingIp = ipState.first,
            baselineIp = ipState.second,
            currentIp = ipState.third
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnSettingsUiState())

    fun saveConfig(rawConfig: String) {
        viewModelScope.launch { preferences.setConfig(rawConfig) }
    }

    fun connect() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _ipState.update { it.copy(second = null, third = null) }
            val baseline = vpnManager.checkPublicIp()
            _ipState.update { it.copy(second = baseline) }
            // checkPublicIp is a several-second network call; if the user hit disconnect
            // while it was in flight, this job was cancelled above and never reaches here -
            // without that guard the tunnel would silently come back up right after the
            // user turned it off.
            vpnManager.connect()
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        vpnManager.disconnect()
        _ipState.update { Triple(false, null, null) }
    }

    fun checkCurrentIp() {
        viewModelScope.launch {
            _ipState.update { it.copy(first = true) }
            val ip = vpnManager.checkPublicIp()
            _ipState.update { it.copy(first = false, third = ip) }
        }
    }

}

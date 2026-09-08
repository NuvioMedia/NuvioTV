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
    val currentIp: String? = null,
    val ipCheckFailed: Boolean = false
)

private data class IpCheckState(
    val isChecking: Boolean = false,
    val baselineIp: String? = null,
    val currentIp: String? = null,
    val checkFailed: Boolean = false
)

@HiltViewModel
class VpnSettingsViewModel @Inject constructor(
    private val preferences: VpnPreferencesDataStore,
    private val vpnManager: VpnManager
) : ViewModel() {

    private val _ipState = MutableStateFlow(IpCheckState())
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
            isCheckingIp = ipState.isChecking,
            baselineIp = ipState.baselineIp,
            currentIp = ipState.currentIp,
            ipCheckFailed = ipState.checkFailed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnSettingsUiState())

    fun saveConfig(rawConfig: String) {
        viewModelScope.launch {
            preferences.setConfig(rawConfig)
            // If the VPN is already on and the user just edited the config (e.g. switched
            // server), re-apply it immediately - otherwise the old tunnel keeps running
            // silently until the next manual toggle, which looks like the change did nothing.
            if (vpnManager.connectionState.value != VpnConnectionState.DISCONNECTED) {
                connect()
            }
        }
    }

    fun connect() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _ipState.update { it.copy(baselineIp = null, currentIp = null, checkFailed = false) }
            val baseline = vpnManager.checkPublicIp()
            _ipState.update { it.copy(baselineIp = baseline, checkFailed = baseline == null) }
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
        _ipState.update { IpCheckState() }
    }

    fun checkCurrentIp() {
        viewModelScope.launch {
            _ipState.update { it.copy(isChecking = true, checkFailed = false) }
            val ip = vpnManager.checkPublicIp()
            _ipState.update { it.copy(isChecking = false, currentIp = ip, checkFailed = ip == null) }
        }
    }

}

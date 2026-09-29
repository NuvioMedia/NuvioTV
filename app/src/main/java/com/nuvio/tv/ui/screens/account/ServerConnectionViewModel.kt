package com.nuvio.tv.ui.screens.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.data.remote.ServerDiscoveryException
import com.nuvio.tv.data.remote.ServerDiscoveryFailure
import com.nuvio.tv.data.remote.ServerDiscoveryService
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerConnectionUiState(
    val activeServer: ServerConfiguration,
    val isDiscovering: Boolean = false,
    val isSwitching: Boolean = false,
    val discoveredServer: ServerConfiguration? = null,
    val error: String? = null
)

@HiltViewModel
class ServerConnectionViewModel @Inject constructor(
    private val discoveryService: ServerDiscoveryService,
    private val configurationStore: ServerConfigurationStore,
    private val authManager: AuthManager,
    private val appRestarter: AppRestarter,
    @param:ApplicationContext private val context: Context,
    activeServer: ServerConfiguration
) : ViewModel() {
    private val _uiState = MutableStateFlow(ServerConnectionUiState(activeServer = activeServer))
    val uiState: StateFlow<ServerConnectionUiState> = _uiState.asStateFlow()
    private var discoveryJob: Job? = null

    fun discover(url: String) {
        if (_uiState.value.isDiscovering || _uiState.value.isSwitching) return
        discoveryJob = viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, discoveredServer = null, error = null) }
            discoveryService.discover(url).fold(
                onSuccess = { server ->
                    _uiState.update { it.copy(isDiscovering = false, discoveredServer = server) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isDiscovering = false, error = discoveryErrorMessage(error))
                    }
                }
            )
        }
    }

    fun connectDiscoveredServer() {
        val server = _uiState.value.discoveredServer ?: return
        switchServer { configurationStore.saveCustom(server) }
    }

    fun useOfficialServer() {
        if (!_uiState.value.activeServer.isCustom) return
        switchServer(configurationStore::useOfficial)
    }

    fun resetDiscovery() {
        if (_uiState.value.isSwitching) return
        discoveryJob?.cancel()
        discoveryJob = null
        _uiState.update { it.copy(isDiscovering = false, discoveredServer = null, error = null) }
    }

    private fun switchServer(save: () -> Boolean) {
        if (_uiState.value.isSwitching) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSwitching = true, error = null) }
            if (authManager.prepareForServerSwitch().isFailure) {
                _uiState.update {
                    it.copy(isSwitching = false, error = context.getString(R.string.custom_server_session_clear_failed))
                }
                return@launch
            }
            if (!save()) {
                _uiState.update {
                    it.copy(isSwitching = false, error = context.getString(R.string.custom_server_save_failed))
                }
                return@launch
            }
            if (!appRestarter.restart()) {
                _uiState.update {
                    it.copy(isSwitching = false, error = context.getString(R.string.custom_server_restart_failed))
                }
            }
        }
    }

    private fun discoveryErrorMessage(error: Throwable): String {
        val discoveryError = error as? ServerDiscoveryException
            ?: return context.getString(R.string.custom_server_error_connection)
        return when (discoveryError.failure) {
            ServerDiscoveryFailure.INVALID_URL -> context.getString(R.string.custom_server_error_invalid_url)
            ServerDiscoveryFailure.OFFICIAL_SERVER -> context.getString(
                if (_uiState.value.activeServer.isCustom) {
                    R.string.custom_server_error_official_available
                } else {
                    R.string.custom_server_error_official_active
                }
            )
            ServerDiscoveryFailure.CONNECTION_FAILED -> context.getString(R.string.custom_server_error_connection)
            ServerDiscoveryFailure.HTTP_ERROR -> context.getString(
                R.string.custom_server_error_http,
                discoveryError.statusCode ?: 0
            )
            ServerDiscoveryFailure.RESPONSE_TOO_LARGE -> context.getString(R.string.custom_server_error_response_too_large)
            ServerDiscoveryFailure.INVALID_DOCUMENT -> context.getString(R.string.custom_server_error_invalid_document)
            ServerDiscoveryFailure.UNSUPPORTED_VERSION -> context.getString(R.string.custom_server_error_version)
            ServerDiscoveryFailure.WRONG_SERVICE -> context.getString(R.string.custom_server_error_service)
            ServerDiscoveryFailure.NOT_SELF_HOSTED -> context.getString(R.string.custom_server_error_not_self_hosted)
            ServerDiscoveryFailure.MISSING_CONFIGURATION -> context.getString(R.string.custom_server_error_missing_configuration)
            ServerDiscoveryFailure.NO_SUPPORTED_AUTH -> context.getString(R.string.custom_server_error_no_auth)
        }
    }
}

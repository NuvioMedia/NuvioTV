package com.nuvio.tv.core.vpn

import android.content.Context
import android.content.Intent
import com.nuvio.tv.data.local.VpnPreferencesDataStore
import com.nuvio.tv.domain.model.VpnConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** VPN is not available in this build flavor. */
@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: VpnPreferencesDataStore
) {
    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _permissionRequest = MutableStateFlow<Intent?>(null)
    val permissionRequest: StateFlow<Intent?> = _permissionRequest.asStateFlow()

    fun connect() {
        _errorMessage.value = "unsupported"
        _connectionState.value = VpnConnectionState.ERROR
    }

    fun disconnect() = Unit

    fun autoConnectIfNeeded() = Unit

    fun onPermissionResult(granted: Boolean) = Unit

    suspend fun checkPublicIp(): String? = null
}

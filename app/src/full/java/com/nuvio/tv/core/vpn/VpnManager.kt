package com.nuvio.tv.core.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.data.local.VpnPreferencesDataStore
import com.nuvio.tv.domain.model.VpnConnectionState
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VpnManager"
private const val TUNNEL_NAME = "nuvio"
private const val PUBLIC_IP_URL = "https://api.ipify.org?format=text"

/**
 * Wraps WireGuard's GoBackend to run a single, always-scoped-to-this-app tunnel (via
 * Interface.includeApplication), so turning this on never affects the rest of the TV -
 * only NuvioTV's own traffic goes through it. The user supplies their own WireGuard
 * config (from their own VPN provider account); nothing is bundled or hardcoded here.
 */
@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: VpnPreferencesDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backend by lazy { GoBackend(context) }
    private val ipCheckClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            _connectionState.value = when (newState) {
                Tunnel.State.UP -> VpnConnectionState.CONNECTED
                Tunnel.State.DOWN -> VpnConnectionState.DISCONNECTED
                Tunnel.State.TOGGLE -> VpnConnectionState.CONNECTING
            }
        }
    }

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Non-null while a VpnService consent dialog needs to be launched from an Activity. */
    private val _permissionRequest = MutableStateFlow<Intent?>(null)
    val permissionRequest: StateFlow<Intent?> = _permissionRequest.asStateFlow()

    fun connect() {
        scope.launch {
            _errorMessage.value = null
            _connectionState.value = VpnConnectionState.CONNECTING
            // Reflects user intent ("I want the VPN on"), not whether this particular
            // attempt succeeds - a transient failure shouldn't disable auto-connect on
            // the next app launch. Only an explicit disconnect (or a denied permission
            // prompt) clears it.
            preferences.setAutoConnect(true)
            val rawConfig = preferences.config.first()
            if (rawConfig.isBlank()) {
                _connectionState.value = VpnConnectionState.ERROR
                _errorMessage.value = "no_config"
                return@launch
            }
            val scopedConfig = try {
                buildScopedConfig(rawConfig)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse WireGuard config", e)
                _connectionState.value = VpnConnectionState.ERROR
                _errorMessage.value = e.message ?: "invalid_config"
                return@launch
            }
            try {
                backend.setState(tunnel, Tunnel.State.UP, scopedConfig)
            } catch (e: BackendException) {
                if (e.reason == BackendException.Reason.VPN_NOT_AUTHORIZED) {
                    _permissionRequest.value = GoBackend.VpnService.prepare(context)
                    if (_permissionRequest.value == null) {
                        // Permission was already granted between the check inside setState
                        // and here - just retry once instead of surfacing a dead-end error.
                        retryAfterPermission()
                    }
                } else {
                    Log.w(TAG, "VPN backend error: ${e.reason}", e)
                    _connectionState.value = VpnConnectionState.ERROR
                    _errorMessage.value = e.reason.name
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bring tunnel up", e)
                _connectionState.value = VpnConnectionState.ERROR
                _errorMessage.value = e.message ?: "connect_failed"
            }
        }
    }

    fun disconnect() {
        scope.launch {
            preferences.setAutoConnect(false)
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bring tunnel down", e)
            }
            _connectionState.value = VpnConnectionState.DISCONNECTED
        }
    }

    /** Called once at app startup. Reconnects only if the user's last explicit action was
     *  to turn the VPN on and a config is present - never retried again this session beyond
     *  this single attempt (connect()'s own error handling takes over from there). */
    fun autoConnectIfNeeded() {
        scope.launch {
            val shouldAutoConnect = preferences.autoConnect.first()
            if (!shouldAutoConnect) return@launch
            if (preferences.config.first().isBlank()) return@launch
            Log.d(TAG, "Auto-connecting VPN on startup (last session was connected)")
            connect()
        }
    }

    /** Called by the Activity once the VpnService consent dialog result comes back. */
    fun onPermissionResult(granted: Boolean) {
        _permissionRequest.value = null
        if (granted) {
            retryAfterPermission()
        } else {
            _connectionState.value = VpnConnectionState.ERROR
            _errorMessage.value = "permission_denied"
            scope.launch { preferences.setAutoConnect(false) }
        }
    }

    private fun retryAfterPermission() {
        scope.launch {
            val rawConfig = preferences.config.first()
            val scopedConfig = try {
                buildScopedConfig(rawConfig)
            } catch (e: Exception) {
                _connectionState.value = VpnConnectionState.ERROR
                _errorMessage.value = e.message ?: "invalid_config"
                return@launch
            }
            try {
                backend.setState(tunnel, Tunnel.State.UP, scopedConfig)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bring tunnel up after permission grant", e)
                _connectionState.value = VpnConnectionState.ERROR
                _errorMessage.value = e.message ?: "connect_failed"
            }
        }
    }

    /** Fetches the current public-facing IP address, or null on failure. */
    suspend fun checkPublicIp(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(PUBLIC_IP_URL).build()
            ipCheckClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Public IP check failed", e)
            null
        }
    }

    /**
     * Parses the user's raw wg-quick config and rebuilds it with this app's own package
     * added to IncludedApplications, so the resulting tunnel only ever carries NuvioTV's
     * traffic - everything else on the TV keeps using the normal connection.
     */
    private fun buildScopedConfig(rawConfig: String): Config {
        val parsed = Config.parse(BufferedReader(StringReader(rawConfig)))
        val original = parsed.`interface`
        val scopedInterfaceBuilder = com.wireguard.config.Interface.Builder()
            .setKeyPair(original.keyPair)
            .addAddresses(original.addresses)
            .addDnsServers(original.dnsServers)
            .addDnsSearchDomains(original.dnsSearchDomains)
            .includeApplication(context.packageName)
        original.listenPort.let { port -> if (port.isPresent) scopedInterfaceBuilder.setListenPort(port.get()) }
        original.mtu.let { mtu -> if (mtu.isPresent) scopedInterfaceBuilder.setMtu(mtu.get()) }
        val scopedInterface = scopedInterfaceBuilder.build()
        return Config.Builder()
            .setInterface(scopedInterface)
            .addPeers(parsed.peers)
            .build()
    }
}

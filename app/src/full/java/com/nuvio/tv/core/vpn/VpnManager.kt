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
import kotlinx.coroutines.delay
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
// A real WireGuard handshake normally completes within 1-2s of the tunnel coming up (the
// userspace backend sends the initiation packet itself, unprompted, as soon as wgTurnOn
// returns) - 8s is already generous slack for a slow/high-latency path, not something a
// working config+network should ever need.
private const val HANDSHAKE_TIMEOUT_MS = 8_000L
private const val HANDSHAKE_POLL_INTERVAL_MS = 250L
// GoBackend's own setState(UP) on top of an already-UP tunnel tears the old one down via
// VpnService.stopSelf(), which only *schedules* onDestroy on the main thread - it does not
// block until the service is actually gone. Bringing the new config up immediately after
// (as GoBackend does internally, back-to-back on the same thread) races that teardown.
// This delay gives Android's service lifecycle a real chance to finish first.
private const val TEARDOWN_SETTLE_MS = 500L

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
            // UP is deliberately NOT mapped to CONNECTED here - GoBackend fires this the
            // moment the tun fd exists, well before the peer has responded to a handshake,
            // which used to show a green "connected" dot (and let the IP checker/plugin
            // loader run) for several seconds before the tunnel could carry a single packet.
            // connect()/retryAfterPermission() confirm a real handshake and set CONNECTED
            // themselves. DOWN is unambiguous either way.
            when (newState) {
                Tunnel.State.DOWN -> _connectionState.value = VpnConnectionState.DISCONNECTED
                Tunnel.State.TOGGLE -> _connectionState.value = VpnConnectionState.CONNECTING
                Tunnel.State.UP -> Unit
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
            // the next app launch. Only an explicit disconnect clears it; a denied
            // permission prompt does not, so the next startup asks again.
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
                // Switching config (or reconnecting) while a tunnel is already up: bring it
                // down and let the teardown actually settle before establishing the new one.
                // See TEARDOWN_SETTLE_MS for why this can't just call setState(UP) directly.
                if (backend.getState(tunnel) == Tunnel.State.UP) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    delay(TEARDOWN_SETTLE_MS)
                }
                backend.setState(tunnel, Tunnel.State.UP, scopedConfig)
                bringUpConfirmedOrRevert(scopedConfig)
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
            // Any connection pooled while routed through the tunnel is now on a dead route.
            ipCheckClient.connectionPool.evictAll()
        }
    }

    /**
     * setState(UP) only means the tun interface exists, not that the peer has responded -
     * waits for a real WireGuard handshake before reporting CONNECTED, and tears the tunnel
     * back down on timeout instead of leaving a half-up tunnel silently black-holing the
     * app's traffic behind a false green dot.
     */
    private suspend fun bringUpConfirmedOrRevert(config: Config) {
        if (awaitHandshake(config)) {
            // The IP-check client may hold a connection pooled from before this tunnel came
            // up (e.g. the baseline check VpnSettingsViewModel does just before connecting) -
            // that socket is on a now-dead route and would otherwise fail or hang the next call.
            ipCheckClient.connectionPool.evictAll()
            _connectionState.value = VpnConnectionState.CONNECTED
        } else {
            Log.w(TAG, "No WireGuard handshake within ${HANDSHAKE_TIMEOUT_MS}ms - reverting")
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to tear down tunnel after handshake timeout", e)
            }
            _connectionState.value = VpnConnectionState.ERROR
            _errorMessage.value = "handshake_timeout"
        }
    }

    private suspend fun awaitHandshake(config: Config): Boolean {
        val peerKeys = config.peers.map { it.publicKey }
        if (peerKeys.isEmpty()) return true
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val stats = try {
                backend.getStatistics(tunnel)
            } catch (e: Exception) {
                null
            }
            val handshakeSeen = stats != null && peerKeys.any { key ->
                (stats.peer(key)?.latestHandshakeEpochMillis ?: 0L) > 0L
            }
            if (handshakeSeen) return true
            delay(HANDSHAKE_POLL_INTERVAL_MS)
        }
        return false
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
            // Unlike an explicit disconnect, a denied permission prompt does NOT clear
            // the auto-connect intent - the next app startup will ask for the system
            // permission again instead of silently staying off.
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
                bringUpConfirmedOrRevert(scopedConfig)
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

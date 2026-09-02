package com.nuvio.tv.core.boomio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.currentDeviceClientMetadata
import com.nuvio.tv.core.sync.SyncClientIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * The TV's companion receiver for the bsc companion hub.
 *
 * Connects to `{BOOMIO_COMPANION_URL}/ws`, registers the TV, forwards inbound
 * play-control commands to the active player via [CompanionPlaybackBridge], and
 * reports ~1s playback telemetry back to the hub. Inert when
 * `BOOMIO_COMPANION_URL` is blank.
 *
 * Wire the register/command frames to the contract in `bsc/services/device-relay.js`:
 * outbound `register` / `playback_position` / `playback_stopped` / `stealth_playpause`
 * / `party_seek`; inbound `play` / `stealth_playpause` / `party_set_playing`
 * / `party_seek` / `party_ended` / `stop` / `companion_paired` / `companion_unpaired`.
 */
@Singleton
class BoomioCompanionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val syncClientIdentity: SyncClientIdentity,
    private val bridge: CompanionPlaybackBridge
) {
    private val companionUrl: String = BuildConfig.BOOMIO_COMPANION_URL.trim()
    private val wsUrl: String = companionUrl.trimEnd('/') + "/ws"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webSocketClient = okHttpClient.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var telemetryJob: Job? = null
    private var lastTelemetryWasActive = false

    private val _currentPartyId = MutableStateFlow<String?>(null)
    /** Watch-party id when the active playback belongs to a party, else null. */
    val currentPartyId: StateFlow<String?> = _currentPartyId.asStateFlow()

    /**
     * ExoPlayer must only be touched on the main thread. The OkHttp WS callbacks
     * and [scope] (IO) run off-main, so route player interactions through here.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /** Starts the companion connection. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (companionUrl.isBlank()) return
        if (webSocket != null || reconnectJob?.isActive == true) return
        connect()
    }

    /** Reports a local party pause/resume so the hub re-broadcasts to all members. */
    fun reportPartyPlayPause() {
        _currentPartyId.value?.let { partyId ->
            webSocket?.send(JSONObject().apply {
                put("type", "stealth_playpause")
                put("partyId", partyId)
            }.toString())
        }
    }

    /** Reports a local party seek so the hub re-broadcasts to all members. */
    fun reportPartySeek(positionMs: Long) {
        _currentPartyId.value?.let { partyId ->
            webSocket?.send(JSONObject().apply {
                put("type", "party_seek")
                put("partyId", partyId)
                put("positionMs", positionMs)
            }.toString())
        }
    }

    private fun connect() {
        webSocket = webSocketClient.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            listener
        )
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            sendRegister()
            ensureTelemetryLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runOnMain { handleInbound(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@BoomioCompanionManager.webSocket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@BoomioCompanionManager.webSocket = null
            scheduleReconnect()
        }
    }

    private fun sendRegister() {
        val metadata = currentDeviceClientMetadata(context)
        val payload = JSONObject().apply {
            put("type", "register")
            put("deviceId", syncClientIdentity.currentClientId())
            put("name", metadata.deviceName)
            put("platform", "androidtv")
            bestEffortLanIp()?.let { put("ip", it) }
        }
        webSocket?.send(payload.toString())
    }

    private fun handleInbound(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "play" -> handlePlay(msg)
            "stealth_playpause" -> {
                msg.optString("partyId").takeIf { it.isNotBlank() }?.let { _currentPartyId.value = it }
                // A party broadcast (party_command/party_sync) has already reached every
                // member — re-reporting it would echo back through the hub and ping-pong
                // play/pause between devices. Only a fresh controller command (e.g. from
                // the phone, _from="companion") should re-propagate to the rest of the party.
                val isPartyBroadcast = msg.optString("_from") in setOf("party_command", "party_sync")
                bridge.activePlayer.value?.togglePlayPause(reportParty = !isPartyBroadcast)
            }
            // The hub broadcasts the desired state (not a toggle) for party
            // pause/resume commands — apply it directly so a "resume" always
            // resumes, even if the device was already in the target state.
            "party_set_playing" -> {
                msg.optString("partyId").takeIf { it.isNotBlank() }?.let { _currentPartyId.value = it }
                val isPlaying = msg.optBoolean("isPlaying")
                bridge.activePlayer.value?.let { if (isPlaying) it.resume() else it.pause() }
            }
            "party_seek" -> {
                msg.optLong("positionMs", -1L).takeIf { it >= 0L }?.let { positionMs ->
                    bridge.activePlayer.value?.seekTo(positionMs)
                }
            }
            "party_ended" -> {
                _currentPartyId.value = null
                bridge.activePlayer.value?.pause()
                showToast("Watch party ended")
            }
            "stop" -> bridge.activePlayer.value?.stop()
            "companion_paired" -> showToast("Phone connected")
            "companion_unpaired" -> showToast("Phone disconnected")
            // N1 ignores scrub_*, audio_fork_*, inject_keyevent and keyboard_* —
            // those are the phone remote (N2) surface.
            else -> Unit
        }
    }

    private fun handlePlay(msg: JSONObject) {
        val url = msg.optString("url")
        if (url.isBlank()) return
        val partyId = msg.optString("partyId").takeIf { it.isNotBlank() }
        _currentPartyId.value = partyId
        bridge.postPlayRequest(
            CompanionPlayRequest(
                streamUrl = url,
                title = msg.optString("title").takeIf { it.isNotBlank() },
                imdbId = msg.optString("imdbId").takeIf { it.isNotBlank() },
                season = msg.optString("season").toIntOrNull(),
                episode = msg.optString("episode").toIntOrNull(),
                resumeFromMs = msg.optLong("resumeFrom", 0L).coerceAtLeast(0L),
                startPaused = !msg.optBoolean("autoPlay", true),
                partyId = partyId,
                source = msg.optString("source").takeIf { it.isNotBlank() }
            )
        )
    }

    private fun ensureTelemetryLoop() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            while (isActive) {
                val player = bridge.activePlayer.value
                if (player != null) {
                    withContext(Dispatchers.Main) {
                        sendPlaybackPosition(player.playbackSnapshot)
                    }
                    lastTelemetryWasActive = true
                } else if (lastTelemetryWasActive) {
                    sendPlaybackStopped()
                    lastTelemetryWasActive = false
                }
                delay(1_000L)
            }
        }
    }

    private fun sendPlaybackPosition(snapshot: CompanionPlaybackSnapshot) {
        val payload = JSONObject().apply {
            put("type", "playback_position")
            put("deviceId", syncClientIdentity.currentClientId())
            put("positionMs", snapshot.positionMs)
            put("durationMs", snapshot.durationMs)
            put("isPlaying", snapshot.isPlaying)
            put("streamUrl", snapshot.streamUrl)
            snapshot.imdbId?.let { put("imdbId", it) }
            snapshot.title?.let { put("title", it) }
            snapshot.season?.let { put("season", it) }
            snapshot.episode?.let { put("episode", it) }
            snapshot.posterUrl?.let { put("posterUrl", it) }
            snapshot.logoUrl?.let { put("logoUrl", it) }
            put("volumePercent", 0)
        }
        webSocket?.send(payload.toString())
    }

    private fun sendPlaybackStopped() {
        webSocket?.send(JSONObject().apply {
            put("type", "playback_stopped")
            put("deviceId", syncClientIdentity.currentClientId())
        }.toString())
    }

    private fun scheduleReconnect() {
        if (companionUrl.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffMs = (1_000L * 2.0.pow(reconnectAttempts.coerceAtMost(5))).toLong()
                .coerceAtMost(30_000L)
            delay(backoffMs)
            reconnectAttempts++
            webSocket = null
            if (isActive) connect()
        }
    }

    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Best-effort LAN address, used by the hub for party member IP discovery. */
    private fun bestEffortLanIp(): String? = try {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("8.8.8.8"), 10_002)
            socket.localAddress?.hostAddress
        }
    } catch (_: Exception) {
        null
    }
}

package com.nuvio.tv.ui.screens.player.watchtogether

import android.content.Context
import android.os.PowerManager
import com.nuvio.tv.data.local.WatchTogetherSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.util.Log
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

enum class RoomRole {
    HOST,
    GUEST,
    NONE,
}

sealed class WatchTogetherEvent {
    data class Connected(val userId: String) : WatchTogetherEvent()
    data object Disconnected : WatchTogetherEvent()
    data class RoomCreated(val roomCode: String, val userId: String) : WatchTogetherEvent()
    data class JoinApproved(val roomCode: String, val userId: String, val state: WatchTogetherRoomState) : WatchTogetherEvent()
    data class JoinRejected(val reason: String) : WatchTogetherEvent()
    data class UserJoined(val userId: String, val username: String) : WatchTogetherEvent()
    data class UserLeft(val userId: String, val username: String) : WatchTogetherEvent()
    data class JoinRequestReceived(val userId: String, val username: String) : WatchTogetherEvent()
    data class PlaybackSync(val action: PlaybackActionPayload) : WatchTogetherEvent()
    data class BufferWait(val contentId: String, val waitingFor: List<String>) : WatchTogetherEvent()
    data class BufferComplete(val contentId: String, val userId: String? = null) : WatchTogetherEvent()
    data class SyncStateReceived(val state: SyncStatePayload) : WatchTogetherEvent()
    data class Error(val code: String, val message: String) : WatchTogetherEvent()
}

@Singleton
class WatchTogetherClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: WatchTogetherSettingsDataStore
) {
    companion object {
        private const val DEFAULT_SERVER_URL = "wss://metroserverx.meowery.eu/ws"
        private const val PING_INTERVAL_MS = 25000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build()

    private val codec = WatchTogetherCodec(true)
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<WatchTogetherEvent>()
    val events: SharedFlow<WatchTogetherEvent> = _events.asSharedFlow()

    private var sessionToken: String? = null
    private var currentRoomCode: String? = null

    init {
        scope.launch {
            dataStore.sessionToken.collect { sessionToken = it }
        }
        scope.launch {
            dataStore.roomCode.collect { currentRoomCode = it }
        }
    }

    fun connect(url: String = DEFAULT_SERVER_URL) {
        if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) {
            // Already connected or connecting, but maybe we want to execute a pending action
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                
                // Send capabilities negotiation
                sendMessage(MessageTypes.CLIENT_CAPABILITIES, ClientCapabilitiesPayload(
                    supportsProtobuf = true,
                    supportsCompression = true,
                    clientVersion = "1.0.0"
                ))
                
                startPingJob()
                sessionToken?.let { token ->
                    sendMessage(MessageTypes.RECONNECT, ReconnectPayload(token))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.ERROR
                handleDisconnect()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        sessionToken = null
        currentRoomCode = null
        scope.launch { dataStore.clearSession() }
        handleDisconnect()
    }

    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        pingJob?.cancel()
        releaseWakeLock()
        scope.launch { _events.emit(WatchTogetherEvent.Disconnected) }
    }

    private fun handleMessage(data: ByteArray) {
        try {
            val (type, payloadBytes) = codec.decodeEnvelope(data)
            val payload = codec.decodePayload(type, payloadBytes)

            scope.launch {
                when (type) {
                    MessageTypes.ROOM_CREATED -> {
                        val p = payload as RoomCreatedPayload
                        sessionToken = p.sessionToken
                        currentRoomCode = p.roomCode
                        dataStore.saveSession(p.sessionToken, p.roomCode, p.userId)
                        _events.emit(WatchTogetherEvent.RoomCreated(p.roomCode, p.userId))
                    }
                    MessageTypes.JOIN_REQUEST -> {
                        val p = payload as JoinRequestPayload
                        _events.emit(WatchTogetherEvent.JoinRequestReceived(p.userId, p.username))
                    }
                    MessageTypes.JOIN_APPROVED -> {
                        val p = payload as JoinApprovedPayload
                        sessionToken = p.sessionToken
                        currentRoomCode = p.roomCode
                        dataStore.saveSession(p.sessionToken, p.roomCode, p.userId)
                        _events.emit(WatchTogetherEvent.JoinApproved(p.roomCode, p.userId, p.state))
                    }
                    MessageTypes.JOIN_REJECTED -> _events.emit(WatchTogetherEvent.JoinRejected((payload as JoinRejectedPayload).reason))
                    MessageTypes.USER_JOINED -> {
                        val p = payload as UserJoinedPayload
                        _events.emit(WatchTogetherEvent.UserJoined(p.userId, p.username))
                    }
                    MessageTypes.SYNC_PLAYBACK -> _events.emit(WatchTogetherEvent.PlaybackSync(payload as PlaybackActionPayload))
                    MessageTypes.BUFFER_WAIT -> {
                        val p = payload as BufferWaitPayload
                        _events.emit(WatchTogetherEvent.BufferWait(p.contentId, p.waitingFor))
                    }
                    MessageTypes.BUFFER_COMPLETE -> {
                        val p = payload as BufferCompletePayload
                        _events.emit(WatchTogetherEvent.BufferComplete(p.contentId, null)) // userId not in payload
                    }
                    MessageTypes.SYNC_STATE -> _events.emit(WatchTogetherEvent.SyncStateReceived(payload as SyncStatePayload))
                    MessageTypes.ERROR -> {
                        val p = payload as ErrorPayload
                        _events.emit(WatchTogetherEvent.Error(p.code, p.message))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WatchTogether", "Error handling message", e)
        }
    }

    fun sendMessage(type: String, payload: Any? = null) {
        try {
            val data = codec.encode(type, payload)
            webSocket?.send(okio.ByteString.of(*data))
        } catch (e: Exception) {
            Log.e("WatchTogether", "Error sending message $type", e)
        }
    }

    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                acquireWakeLock()
                sendMessage(MessageTypes.PING)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = context.getSystemService<PowerManager>()
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nuvio:WatchTogether")
        }
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    fun createRoom(username: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            connect()
            // In a real implementation, we'd queue this action
        }
        sendMessage(MessageTypes.CREATE_ROOM, CreateRoomPayload(username))
    }

    fun joinRoom(roomCode: String, username: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            connect()
        }
        sendMessage(MessageTypes.JOIN_ROOM, JoinRoomPayload(roomCode, username))
    }

    fun leaveRoom() {
        sendMessage(MessageTypes.LEAVE_ROOM)
        disconnect()
    }

    fun approveJoin(userId: String) {
        sendMessage(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId))
    }

    fun rejectJoin(userId: String, reason: String? = null) {
        sendMessage(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, reason))
    }
}

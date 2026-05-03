package com.nuvio.tv.ui.screens.player.watchtogether

import android.content.Context
import com.nuvio.tv.data.local.WatchTogetherSettingsDataStore
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class WatchTogetherState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val roomCode: String? = null,
    val userId: String? = null,
    val role: RoomRole = RoomRole.NONE,
    val participants: List<WatchTogetherUserInfo> = emptyList(),
    val joinRequests: List<WatchTogetherUserInfo> = emptyList(),
    val currentContent: WatchTogetherContentInfo? = null,
    val isHost: Boolean = false,
    val bufferingUsers: List<String> = emptyList(),
    val isWaitingForOthers: Boolean = false,
    val error: String? = null
)

@Singleton
class WatchTogetherManager @Inject constructor(
    private val client: WatchTogetherClient,
    private val dataStore: WatchTogetherSettingsDataStore,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(WatchTogetherState())
    val state: StateFlow<WatchTogetherState> = _state.asStateFlow()

    val events: SharedFlow<WatchTogetherEvent> = client.events

    init {
        scope.launch {
            dataStore.username.collect { name ->
                // Initial username loading if needed
            }
        }

        scope.launch {
            client.connectionState.collect { connState ->
                _state.update { it.copy(connectionState = connState) }
            }
        }

        scope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: WatchTogetherEvent) {
        when (event) {
            is WatchTogetherEvent.RoomCreated -> {
                _state.update { it.copy(
                    roomCode = event.roomCode,
                    userId = event.userId,
                    role = RoomRole.HOST,
                    isHost = true,
                    error = null
                ) }
            }
            is WatchTogetherEvent.JoinApproved -> {
                _state.update { it.copy(
                    roomCode = event.roomCode,
                    userId = event.userId,
                    role = if (event.state.hostId == event.userId) RoomRole.HOST else RoomRole.GUEST,
                    isHost = event.state.hostId == event.userId,
                    participants = event.state.users,
                    joinRequests = emptyList(),
                    currentContent = event.state.currentContent,
                    error = null
                ) }
            }
            is WatchTogetherEvent.JoinRequestReceived -> {
                _state.update { s ->
                    val req = WatchTogetherUserInfo(event.userId, event.username, false)
                    if (s.joinRequests.any { it.userId == event.userId }) s
                    else s.copy(joinRequests = s.joinRequests + req)
                }
            }
            is WatchTogetherEvent.JoinRejected -> {
                _state.update { it.copy(error = event.reason) }
            }
            is WatchTogetherEvent.UserJoined -> {
                _state.update { s ->
                    val newUser = WatchTogetherUserInfo(event.userId, event.username, false)
                    if (s.participants.any { it.userId == event.userId }) s
                    else s.copy(participants = s.participants + newUser)
                }
            }
            is WatchTogetherEvent.PlaybackSync -> {
                if (event.action.action == PlaybackActions.CHANGE_CONTENT) {
                    _state.update { it.copy(currentContent = event.action.contentInfo) }
                }
            }
            is WatchTogetherEvent.SyncStateReceived -> {
                _state.update { it.copy(currentContent = event.state.currentContent) }
            }
            is WatchTogetherEvent.BufferWait -> {
                _state.update { it.copy(
                    bufferingUsers = event.waitingFor,
                    isWaitingForOthers = true
                ) }
            }
            is WatchTogetherEvent.BufferComplete -> {
                _state.update { it.copy(
                    isWaitingForOthers = false // Simplification: clear waiting state when complete received
                ) }
            }
            is WatchTogetherEvent.Disconnected -> {
                _state.update { WatchTogetherState() }
            }
            is WatchTogetherEvent.Error -> {
                _state.update { it.copy(error = event.message) }
            }
            else -> {}
        }
    }

    fun createRoom(username: String) {
        scope.launch { dataStore.saveUsername(username) }
        client.createRoom(username)
    }

    fun joinRoom(roomCode: String, username: String) {
        scope.launch { dataStore.saveUsername(username) }
        client.joinRoom(roomCode, username)
    }

    fun leaveRoom() {
        client.leaveRoom()
    }

    fun approveJoin(userId: String) {
        client.approveJoin(userId)
        _state.update { s ->
            val user = s.joinRequests.find { it.userId == userId }
            s.copy(
                joinRequests = s.joinRequests.filter { it.userId != userId },
                participants = if (user != null) s.participants + user else s.participants
            )
        }
    }

    fun rejectJoin(userId: String) {
        client.rejectJoin(userId)
        _state.update { it.copy(joinRequests = it.joinRequests.filter { it.userId != userId }) }
    }

    fun reportBuffering(isBuffering: Boolean) {
        if (state.value.role == RoomRole.NONE) return
        val currentContentId = state.value.currentContent?.id ?: ""
        if (isBuffering) {
            client.sendMessage(MessageTypes.BUFFER_WAIT, BufferReadyPayload(currentContentId))
        } else {
            client.sendMessage(MessageTypes.BUFFER_READY, BufferReadyPayload(currentContentId))
        }
    }

    fun broadcastPlaybackAction(action: String, position: Long? = null) {
        if (!state.value.isHost) return
        val payload = PlaybackActionPayload(
            action = action,
            position = position,
            contentId = state.value.currentContent?.id
        )
        client.sendMessage(MessageTypes.PLAYBACK_ACTION, payload)
    }

    fun broadcastChangeContent(stream: Stream, contentId: String, title: String, video: Video? = null) {
        if (state.value.role == RoomRole.NONE) return
        
        val fingerprint = WatchTogetherStreamFingerprint(
            addonName = stream.addonName,
            infoHash = stream.infoHash,
            filename = stream.behaviorHints?.filename,
            name = stream.name,
            title = stream.title,
            description = stream.description,
            contentId = contentId,
            videoId = video?.id,
            season = video?.season,
            episode = video?.episode
        )

        val contentInfo = WatchTogetherContentInfo(
            id = contentId,
            title = title,
            duration = video?.runtime?.toLong() ?: 0L,
            fingerprint = fingerprint
        )

        _state.update { it.copy(currentContent = contentInfo) }

        if (state.value.isHost) {
            val payload = PlaybackActionPayload(
                action = PlaybackActions.CHANGE_CONTENT,
                contentId = contentId,
                contentInfo = contentInfo
            )
            client.sendMessage(MessageTypes.PLAYBACK_ACTION, payload)
        }
    }
}

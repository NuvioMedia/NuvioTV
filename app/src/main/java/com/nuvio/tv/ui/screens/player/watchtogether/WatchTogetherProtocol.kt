package com.nuvio.tv.ui.screens.player.watchtogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message types for Watch Together protocol (MetroServer compatible)
 */
object MessageTypes {
    // Client -> Server
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val LEAVE_ROOM = "leave_room"
    const val APPROVE_JOIN = "approve_join"
    const val REJECT_JOIN = "reject_join"
    const val PLAYBACK_ACTION = "playback_action"
    const val BUFFER_READY = "buffer_ready"
    const val KICK_USER = "kick_user"
    const val TRANSFER_HOST = "transfer_host"
    const val PING = "ping"
    const val REQUEST_SYNC = "request_sync"
    const val RECONNECT = "reconnect"
    const val CLIENT_CAPABILITIES = "client_capabilities"

    // Server -> Client
    const val ROOM_CREATED = "room_created"
    const val JOIN_REQUEST = "join_request"
    const val JOIN_APPROVED = "join_approved"
    const val JOIN_REJECTED = "join_rejected"
    const val USER_JOINED = "user_joined"
    const val USER_LEFT = "user_left"
    const val SYNC_PLAYBACK = "sync_playback"
    const val BUFFER_WAIT = "buffer_wait"
    const val BUFFER_COMPLETE = "buffer_complete"
    const val ERROR = "error"
    const val PONG = "pong"
    const val HOST_CHANGED = "host_changed"
    const val KICKED = "kicked"
    const val SYNC_STATE = "sync_state"
    const val RECONNECTED = "reconnected"
    const val USER_RECONNECTED = "user_reconnected"
    const val USER_DISCONNECTED = "user_disconnected"
}

/**
 * Playback action types
 */
object PlaybackActions {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val CHANGE_CONTENT = "change_content"
}

/**
 * Content identification fingerprint for shared playback
 */
@Serializable
data class WatchTogetherStreamFingerprint(
    val addonName: String,
    val infoHash: String? = null,
    val filename: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val contentId: String? = null,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * Track/Content information (Proto compatible)
 */
@Serializable
data class WatchTogetherContentInfo(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val duration: Long, // milliseconds
    val thumbnail: String? = null,
    @SerialName("suggested_by") val suggestedBy: String? = null,
    @SerialName("fingerprint") val fingerprint: WatchTogetherStreamFingerprint? = null
)

/**
 * User information
 */
@Serializable
data class WatchTogetherUserInfo(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("is_host") val isHost: Boolean,
    @SerialName("is_connected") val isConnected: Boolean = true
)

/**
 * Room state
 */
@Serializable
data class WatchTogetherRoomState(
    @SerialName("room_code") val roomCode: String,
    @SerialName("host_id") val hostId: String,
    val users: List<WatchTogetherUserInfo>,
    @SerialName("current_track") val currentContent: WatchTogetherContentInfo? = null,
    @SerialName("is_playing") val isPlaying: Boolean,
    val position: Long, // milliseconds
    @SerialName("last_update") val lastUpdate: Long, // unix timestamp ms
    val volume: Float = 1f
)

// Payloads
@Serializable
data class CreateRoomPayload(val username: String)

@Serializable
data class JoinRoomPayload(
    @SerialName("room_code") val roomCode: String,
    val username: String
)

@Serializable
data class ApproveJoinPayload(@SerialName("user_id") val userId: String)

@Serializable
data class RejectJoinPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null
)

@Serializable
data class PlaybackActionPayload(
    val action: String,
    @SerialName("track_id") val contentId: String? = null,
    val position: Long? = null,
    @SerialName("track_info") val contentInfo: WatchTogetherContentInfo? = null,
    @SerialName("server_time") val serverTime: Long? = null
)

@Serializable
data class BufferReadyPayload(@SerialName("track_id") val contentId: String)

@Serializable
data class KickUserPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null
)

@Serializable
data class TransferHostPayload(@SerialName("new_host_id") val newHostId: String)

@Serializable
data class ReconnectPayload(@SerialName("session_token") val sessionToken: String)

@Serializable
data class ClientCapabilitiesPayload(
    @SerialName("supports_protobuf") val supportsProtobuf: Boolean,
    @SerialName("supports_compression") val supportsCompression: Boolean,
    @SerialName("client_version") val clientVersion: String
)

// Server -> Client Payloads
@Serializable
data class RoomCreatedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_token") val sessionToken: String
)

@Serializable
data class JoinRequestPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class JoinApprovedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_token") val sessionToken: String,
    val state: WatchTogetherRoomState
)

@Serializable
data class JoinRejectedPayload(val reason: String)

@Serializable
data class UserJoinedPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class UserLeftPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class BufferWaitPayload(
    @SerialName("track_id") val contentId: String,
    @SerialName("waiting_for") val waitingFor: List<String>
)

@Serializable
data class BufferCompletePayload(@SerialName("track_id") val contentId: String)

@Serializable
data class ErrorPayload(val code: String, val message: String)

@Serializable
data class HostChangedPayload(
    @SerialName("new_host_id") val newHostId: String,
    @SerialName("new_host_name") val newHostName: String
)

@Serializable
data class KickedPayload(val reason: String)

@Serializable
data class SyncStatePayload(
    @SerialName("current_track") val currentContent: WatchTogetherContentInfo?,
    @SerialName("is_playing") val isPlaying: Boolean,
    val position: Long,
    @SerialName("last_update") val lastUpdate: Long,
    val volume: Float? = null
)

@Serializable
data class ReconnectedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    val state: WatchTogetherRoomState,
    @SerialName("is_host") val isHost: Boolean
)

@Serializable
data class UserReconnectedPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class UserDisconnectedPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

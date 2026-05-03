package com.nuvio.tv.ui.screens.player.watchtogether

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.metrolist.music.listentogether.proto.Listentogether
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WatchTogetherCodec(var compressionEnabled: Boolean = true) {
    companion object {
        private const val COMPRESSION_THRESHOLD = 100
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    fun encode(type: String, payload: Any?): ByteArray {
        var payloadBytes = byteArrayOf()
        var compressed = false

        if (payload != null) {
            val protoMsg = toProtoMessage(payload)
            payloadBytes = protoMsg.toByteArray()

            if (compressionEnabled && payloadBytes.size > COMPRESSION_THRESHOLD) {
                val compressedBytes = compressData(payloadBytes)
                if (compressedBytes.size < payloadBytes.size) {
                    payloadBytes = compressedBytes
                    compressed = true
                }
            }
        }

        return Listentogether.Envelope.newBuilder()
            .setType(type)
            .setPayload(ByteString.copyFrom(payloadBytes))
            .setCompressed(compressed)
            .build()
            .toByteArray()
    }

    fun decodeEnvelope(data: ByteArray): Pair<String, ByteArray> {
        val envelope = Listentogether.Envelope.parseFrom(data)
        var payloadBytes = envelope.payload.toByteArray()
        if (envelope.compressed) {
            payloadBytes = decompressData(payloadBytes) ?: payloadBytes
        }
        return Pair(envelope.type, payloadBytes)
    }

    private fun compressData(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decompressData(data: ByteArray): ByteArray? {
        return try {
            val inputStream = ByteArrayInputStream(data)
            GZIPInputStream(inputStream).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun toProtoMessage(payload: Any): MessageLite {
        return when (payload) {
            is CreateRoomPayload -> Listentogether.CreateRoomPayload.newBuilder()
                .setUsername(payload.username).build()
            is JoinRoomPayload -> Listentogether.JoinRoomPayload.newBuilder()
                .setRoomCode(payload.roomCode).setUsername(payload.username).build()
            is PlaybackActionPayload -> {
                val builder = Listentogether.PlaybackActionPayload.newBuilder()
                    .setAction(payload.action)
                    .setPosition(payload.position ?: 0)
                    .setServerTime(payload.serverTime ?: 0)
                payload.contentId?.let { builder.setTrackId(it) }
                payload.contentInfo?.let { builder.setTrackInfo(contentInfoToProto(it)) }
                builder.build()
            }
            is BufferReadyPayload -> Listentogether.BufferReadyPayload.newBuilder()
                .setTrackId(payload.contentId).build()
            is ReconnectPayload -> Listentogether.ReconnectPayload.newBuilder()
                .setSessionToken(payload.sessionToken).build()
            is ApproveJoinPayload -> Listentogether.ApproveJoinPayload.newBuilder()
                .setUserId(payload.userId).build()
            is RejectJoinPayload -> Listentogether.RejectJoinPayload.newBuilder()
                .setUserId(payload.userId).setReason(payload.reason ?: "").build()
            is KickUserPayload -> Listentogether.KickUserPayload.newBuilder()
                .setUserId(payload.userId).setReason(payload.reason ?: "").build()
            is TransferHostPayload -> Listentogether.TransferHostPayload.newBuilder()
                .setNewHostId(payload.newHostId).build()
            is ClientCapabilitiesPayload -> Listentogether.ClientCapabilities.newBuilder()
                .setSupportsProtobuf(payload.supportsProtobuf)
                .setSupportsCompression(payload.supportsCompression)
                .setClientVersion(payload.clientVersion)
                .build()
            else -> throw IllegalArgumentException("Unsupported payload type: ${payload::class.simpleName}")
        }
    }

    fun decodePayload(type: String, bytes: ByteArray): Any? {
        if (bytes.isEmpty()) return null
        return when (type) {
            MessageTypes.ROOM_CREATED -> {
                val pb = Listentogether.RoomCreatedPayload.parseFrom(bytes)
                RoomCreatedPayload(pb.roomCode, pb.userId, pb.sessionToken)
            }
            MessageTypes.JOIN_REQUEST -> {
                val pb = Listentogether.JoinRequestPayload.parseFrom(bytes)
                JoinRequestPayload(pb.userId, pb.username)
            }
            MessageTypes.JOIN_APPROVED -> {
                val pb = Listentogether.JoinApprovedPayload.parseFrom(bytes)
                JoinApprovedPayload(pb.roomCode, pb.userId, pb.sessionToken, protoToRoomState(pb.state))
            }
            MessageTypes.JOIN_REJECTED -> {
                val pb = Listentogether.JoinRejectedPayload.parseFrom(bytes)
                JoinRejectedPayload(pb.reason)
            }
            MessageTypes.USER_JOINED -> {
                val pb = Listentogether.UserJoinedPayload.parseFrom(bytes)
                UserJoinedPayload(pb.userId, pb.username)
            }
            MessageTypes.USER_LEFT -> {
                val pb = Listentogether.UserLeftPayload.parseFrom(bytes)
                UserLeftPayload(pb.userId, pb.username)
            }
            MessageTypes.SYNC_PLAYBACK -> {
                val pb = Listentogether.PlaybackActionPayload.parseFrom(bytes)
                PlaybackActionPayload(
                    action = pb.action,
                    contentId = pb.trackId.takeIf { it.isNotEmpty() },
                    position = pb.position.takeIf { it != 0L || pb.action != PlaybackActions.CHANGE_CONTENT },
                    contentInfo = if (pb.hasTrackInfo()) protoToContentInfo(pb.trackInfo) else null,
                    serverTime = pb.serverTime.takeIf { it > 0 }
                )
            }
            MessageTypes.BUFFER_WAIT -> {
                val pb = Listentogether.BufferWaitPayload.parseFrom(bytes)
                BufferWaitPayload(pb.trackId, pb.waitingForList)
            }
            MessageTypes.BUFFER_COMPLETE -> {
                val pb = Listentogether.BufferCompletePayload.parseFrom(bytes)
                BufferCompletePayload(pb.trackId)
            }
            MessageTypes.ERROR -> {
                val pb = Listentogether.ErrorPayload.parseFrom(bytes)
                ErrorPayload(pb.code, pb.message)
            }
            MessageTypes.HOST_CHANGED -> {
                val pb = Listentogether.HostChangedPayload.parseFrom(bytes)
                HostChangedPayload(pb.newHostId, pb.newHostName)
            }
            MessageTypes.KICKED -> {
                val pb = Listentogether.KickedPayload.parseFrom(bytes)
                KickedPayload(pb.reason)
            }
            MessageTypes.SYNC_STATE -> {
                val pb = Listentogether.SyncStatePayload.parseFrom(bytes)
                SyncStatePayload(
                    currentContent = if (pb.hasCurrentTrack()) protoToContentInfo(pb.currentTrack) else null,
                    isPlaying = pb.isPlaying,
                    position = pb.position,
                    lastUpdate = pb.lastUpdate,
                    volume = pb.volume
                )
            }
            MessageTypes.RECONNECTED -> {
                val pb = Listentogether.ReconnectedPayload.parseFrom(bytes)
                ReconnectedPayload(pb.roomCode, pb.userId, protoToRoomState(pb.state), pb.isHost)
            }
            MessageTypes.USER_RECONNECTED -> {
                val pb = Listentogether.UserReconnectedPayload.parseFrom(bytes)
                UserReconnectedPayload(pb.userId, pb.username)
            }
            MessageTypes.USER_DISCONNECTED -> {
                val pb = Listentogether.UserDisconnectedPayload.parseFrom(bytes)
                UserDisconnectedPayload(pb.userId, pb.username)
            }
            else -> null
        }
    }

    private fun contentInfoToProto(info: WatchTogetherContentInfo): Listentogether.TrackInfo {
        val builder = Listentogether.TrackInfo.newBuilder()
            .setId(info.id)
            .setTitle(info.title)
            .setArtist(info.artist)
            .setAlbum(info.album ?: "")
            .setDuration(info.duration)
            .setThumbnail(info.thumbnail ?: "")
        
        info.fingerprint?.let {
            builder.setSuggestedBy(json.encodeToString(it))
        } ?: builder.setSuggestedBy(info.suggestedBy ?: "")
        
        return builder.build()
    }

    private fun protoToContentInfo(proto: Listentogether.TrackInfo): WatchTogetherContentInfo {
        val suggestedBy = proto.suggestedBy
        val fingerprint = try {
            if (suggestedBy.startsWith("{")) json.decodeFromString<WatchTogetherStreamFingerprint>(suggestedBy) else null
        } catch (e: Exception) {
            null
        }

        return WatchTogetherContentInfo(
            id = proto.id,
            title = proto.title,
            artist = proto.artist,
            album = proto.album.takeIf { it.isNotEmpty() },
            duration = proto.duration,
            thumbnail = proto.thumbnail.takeIf { it.isNotEmpty() },
            suggestedBy = if (fingerprint == null) suggestedBy.takeIf { it.isNotEmpty() } else null,
            fingerprint = fingerprint
        )
    }

    private fun protoToUserInfo(proto: Listentogether.UserInfo): WatchTogetherUserInfo {
        return WatchTogetherUserInfo(
            userId = proto.userId,
            username = proto.username,
            isHost = proto.isHost,
            isConnected = proto.isConnected
        )
    }

    private fun protoToRoomState(proto: Listentogether.RoomState): WatchTogetherRoomState {
        return WatchTogetherRoomState(
            roomCode = proto.roomCode,
            hostId = proto.hostId,
            users = proto.usersList.map { protoToUserInfo(it) },
            currentContent = if (proto.hasCurrentTrack()) protoToContentInfo(proto.currentTrack) else null,
            isPlaying = proto.isPlaying,
            position = proto.position,
            lastUpdate = proto.lastUpdate,
            volume = proto.volume
        )
    }
}

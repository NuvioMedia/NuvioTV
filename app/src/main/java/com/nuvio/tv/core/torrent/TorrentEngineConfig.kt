package com.nuvio.tv.core.torrent

import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File

internal fun buildTorrentEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: TorrentProfile,
    diskCacheCapacityBytes: Long
): NuvioEngineConfig = NuvioEngineConfig(
    dataDirectory = stateDirectory,
    cacheDirectory = cacheDirectory,
    diskCacheCapacityBytes = diskCacheCapacityBytes,
    torrentProfile = when (torrentProfile) {
        TorrentProfile.SOFT -> NuvioTorrentProfile.Soft
        TorrentProfile.BALANCED -> NuvioTorrentProfile.Balanced
        TorrentProfile.FAST -> NuvioTorrentProfile.Fast
    },
    uploadMode = if (uploadEnabled) {
        NuvioUploadMode.Unlimited
    } else {
        NuvioUploadMode.Disabled
    },
    streamInactivityTimeoutMilliseconds = 0
)

internal fun unexpectedStreamStopError(
    requestId: Long,
    eventStreamId: String?,
    currentStreamId: String?,
    message: String?,
    fallbackMessage: String
): TorrentState.Error? {
    if (requestId != 0L || currentStreamId == null || eventStreamId != currentStreamId) {
        return null
    }
    return TorrentState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

internal fun unexpectedTorrentError(
    requestId: Long,
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String
): TorrentState.Error? {
    if (requestId != 0L ||
        eventTorrentId == null ||
        currentTorrentId == null ||
        eventTorrentId != currentTorrentId
    ) {
        return null
    }
    return TorrentState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

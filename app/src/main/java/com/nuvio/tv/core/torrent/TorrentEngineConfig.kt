package com.nuvio.tv.core.torrent

import android.system.ErrnoException
import android.system.Os
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioStorageMode
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File

internal fun buildTorrentEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: TorrentProfile,
    cacheSize: TorrentCacheSize,
    memoryStorageCapacityBytes: Long
): NuvioEngineConfig = NuvioEngineConfig(
    dataDirectory = stateDirectory,
    cacheDirectory = cacheDirectory,
    diskCacheCapacityBytes = cacheSize.bytes,
    storageMode = if (cacheSize == TorrentCacheSize.MEMORY_ONLY) {
        NuvioStorageMode.Memory
    } else {
        NuvioStorageMode.Disk
    },
    memoryStorageCapacityBytes = memoryStorageCapacityBytes,
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

private const val MiB = 1024L * 1024L
internal const val MinTorrentMemoryStorageBytes = 128L * MiB
internal const val MaxTorrentMemoryStorageBytes = 512L * MiB

internal fun torrentMemoryStorageCapacityBytes(totalMemoryBytes: Long, lowRamDevice: Boolean): Long =
    if (lowRamDevice) {
        MinTorrentMemoryStorageBytes
    } else {
        (totalMemoryBytes / 8).coerceIn(MinTorrentMemoryStorageBytes, MaxTorrentMemoryStorageBytes)
    }

internal fun measureTorrentPayloadBytes(
    cacheDirectory: File,
    storedBytes: (File) -> Long = ::allocatedFileBytes
): Long = File(cacheDirectory, "payload")
    .walkTopDown()
    .filter { it.isFile }
    .sumOf(storedBytes)

private fun allocatedFileBytes(file: File): Long = try {
    val stat = Os.lstat(file.path)
    minOf(stat.st_size, stat.st_blocks * 512L)
} catch (_: ErrnoException) {
    0L
}

internal fun migrateNestedPayloadDirectory(cacheDirectory: File) {
    val payloadDirectory = File(cacheDirectory, "payload")
    val nestedDirectory = File(payloadDirectory, "payload")
    val entries = nestedDirectory.listFiles() ?: return
    entries.forEach { entry ->
        val target = File(payloadDirectory, entry.name)
        if (target.exists() || !entry.renameTo(target)) {
            entry.deleteRecursively()
        }
    }
    nestedDirectory.deleteRecursively()
}

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
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String
): TorrentState.Error? {
    if (eventTorrentId == null ||
        currentTorrentId == null ||
        eventTorrentId != currentTorrentId
    ) {
        return null
    }
    return TorrentState.Error(message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage)
}

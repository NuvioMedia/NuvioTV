package com.nuvio.tv.core.torrent

import androidx.compose.runtime.Immutable

@Immutable
data class TorrentCacheState(
    val usedBytes: Long = 0L,
    val protectedBytes: Long = 0L,
    val isClearing: Boolean = false,
    val hasMeasurement: Boolean = false
)

data class TorrentCacheClearResult(
    val reclaimedBytes: Long,
    val remainingBytes: Long,
    val protectedBytes: Long
)

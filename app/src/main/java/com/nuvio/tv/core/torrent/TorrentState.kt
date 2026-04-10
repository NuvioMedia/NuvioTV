package com.nuvio.tv.core.torrent

import androidx.compose.runtime.Immutable

@Immutable
sealed class TorrentState {
    data object Idle : TorrentState()
    data object Connecting : TorrentState()

    data class Streaming(
        val localUrl: String,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val bufferProgress: Float,
        val totalProgress: Float,
        val preloadedBytes: Long = 0L
    ) : TorrentState()

    data class Error(val message: String) : TorrentState()
}

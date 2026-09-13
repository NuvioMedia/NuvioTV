package com.nuvio.tv.core.usenet

sealed interface NntpState {
    data object Idle : NntpState
    data object Connecting : NntpState

    data class Streaming(
        val localUrl: String,
        val downloadedBytes: Long = 0,
        val downloadSpeed: Long = 0,
        val connections: Int = 0
    ) : NntpState

    data class Error(val message: String) : NntpState
}

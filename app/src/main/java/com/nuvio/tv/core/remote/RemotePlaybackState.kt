package com.nuvio.tv.core.remote

/** A user pause must remain resumable even if the engine is still buffering. */
internal fun remotePlaybackState(playIntent: Boolean, buffering: Boolean, playing: Boolean): String = when {
    !playIntent -> "paused"
    buffering -> "buffering"
    playing -> "playing"
    else -> "paused"
}

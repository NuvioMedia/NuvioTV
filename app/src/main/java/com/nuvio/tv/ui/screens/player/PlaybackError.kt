package com.nuvio.tv.ui.screens.player

/** Stable categories for recovery and diagnostics; display text is never an error identity. */
enum class PlaybackErrorKind { STARTUP_TIMEOUT, PLAYER, STREAM, TORRENT }

data class PlaybackError(
    val kind: PlaybackErrorKind,
    val message: String,
    val generation: Int,
)

internal fun PlayerRuntimeController.playbackError(
    message: String,
    kind: PlaybackErrorKind = PlaybackErrorKind.PLAYER,
): PlaybackError = PlaybackError(kind, message, playerInitializationGeneration)

/** Only the attempt that timed out can recover its timeout; decoder/network errors survive. */
internal fun errorAfterStartupRecovery(error: PlaybackError?, generation: Int): PlaybackError? =
    if (error?.kind == PlaybackErrorKind.STARTUP_TIMEOUT && error.generation == generation) null else error

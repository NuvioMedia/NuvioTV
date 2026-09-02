package com.nuvio.tv.core.boomio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A `play` command received from the bsc companion hub (phone remote or watch
 * party). The nav layer consumes this and builds the player screen route.
 */
data class CompanionPlayRequest(
    val streamUrl: String,
    val title: String?,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
    val resumeFromMs: Long,
    val startPaused: Boolean,
    val partyId: String?,
    val source: String?
)

/** Snapshot of the active player's state, reported to the hub at ~1s cadence. */
data class CompanionPlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val streamUrl: String,
    val imdbId: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String?,
    val logoUrl: String?
)

/**
 * The pause/seek/telemetry surface the active player exposes to the companion
 * manager. Registered by the player screen while it is alive; the manager reads
 * it for telemetry and forwards inbound play-control commands to it.
 */
interface ActiveCompanionPlayer {
    val playbackSnapshot: CompanionPlaybackSnapshot

    fun togglePlayPause(reportParty: Boolean = true)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()

    /**
     * Set the player's own volume, 0–1. Used by the companion remote when the OS
     * blocks device-stream volume changes — scaling the player is always honored.
     */
    fun setVolume(fraction: Float)
}

/**
 * The text-entry surface the Search screen exposes to the companion manager
 * while it is in front. Registered by the search composable while alive; the
 * manager forwards inbound `keyboard_input`/`keyboard_submit` frames to it.
 */
interface CompanionSearchInput {
    /** Replace the search field's whole text with [text] and run live search. */
    fun onRemoteText(text: String)

    /** Run the search as if Enter was pressed (Enter / IME Done). */
    fun submit()
}

/**
 * Decouples the singleton bsc WebSocket receiver ([BoomioCompanionManager]) from
 * the screen-bound player.
 *
 * - Play requests flow manager → nav layer via [pendingPlayRequest].
 * - Play control flows hub → active player via [activePlayer].
 *
 * Nothing here touches the player internals, so the manager can be a Hilt
 * singleton that outlives any single player screen.
 */
@Singleton
class CompanionPlaybackBridge @Inject constructor() {

    private val _pendingPlayRequest = MutableStateFlow<CompanionPlayRequest?>(null)
    /** A `play` command awaiting navigation. The latest one wins. */
    val pendingPlayRequest: StateFlow<CompanionPlayRequest?> = _pendingPlayRequest.asStateFlow()

    private val _activePlayer = MutableStateFlow<ActiveCompanionPlayer?>(null)
    /** The currently-active player surface, registered while a player screen is alive. */
    val activePlayer: StateFlow<ActiveCompanionPlayer?> = _activePlayer.asStateFlow()

    private val _activeSearchInput = MutableStateFlow<CompanionSearchInput?>(null)
    /** The currently-active Search screen text surface, if Search is in front. */
    val activeSearchInput: StateFlow<CompanionSearchInput?> = _activeSearchInput.asStateFlow()

    private val _searchRequestTick = MutableStateFlow(0)
    /**
     * Monotonic tick that increments each time the companion asks to open the
     * Search screen (`stealth_search`). The nav layer collects it and navigates;
     * a tick count avoids the consume/null races of a nullable one-shot.
     */
    val searchRequestTick: StateFlow<Int> = _searchRequestTick.asStateFlow()

    fun postPlayRequest(request: CompanionPlayRequest) {
        _pendingPlayRequest.value = request
    }

    /** Consume the pending play request after navigating to it. */
    fun consumePlayRequest() {
        _pendingPlayRequest.value = null
    }

    /** Ask the nav layer to open the TV's Search screen. */
    fun requestSearchScreen() {
        _searchRequestTick.value += 1
    }

    fun registerActivePlayer(player: ActiveCompanionPlayer) {
        _activePlayer.value = player
    }

    fun unregisterActivePlayer(player: ActiveCompanionPlayer) {
        _activePlayer.compareAndSet(player, null)
    }

    fun registerSearchInput(input: CompanionSearchInput) {
        _activeSearchInput.value = input
    }

    fun unregisterSearchInput(input: CompanionSearchInput) {
        _activeSearchInput.compareAndSet(input, null)
    }
}

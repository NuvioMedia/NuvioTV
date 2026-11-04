package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

/**
 * Builds a [MediaMetadata] from the current playback information so that
 * external controllers (Google Home, Wear OS, system media controls) can
 * display rich "Now Playing" information such as title, subtitle and artwork.
 */
internal fun PlayerRuntimeController.buildMediaSessionMetadata(): MediaMetadata {
    val state = _uiState.value
    val isSeries = contentType?.lowercase() in listOf("series", "tv")

    // Title: for series use the show name, for movies use the main title.
    val displayTitle = state.contentName?.takeIf { it.isNotBlank() } ?: state.title

    // Subtitle / artist: for series show "S1:E2 – Episode Title", for movies show the year.
    val displaySubtitle = if (isSeries) {
        buildString {
            val s = state.currentSeason
            val e = state.currentEpisode
            if (s != null && e != null) {
                append("S${s}:E${e}")
                state.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let {
                    append(" – $it")
                }
            } else {
                state.currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let { append(it) }
            }
        }.takeIf { it.isNotBlank() }
    } else {
        state.releaseYear
    }

    // Artwork: prefer poster, fall back to backdrop.
    val artworkUri = (poster?.takeIf { it.isNotBlank() }
        ?: backdrop?.takeIf { it.isNotBlank() })
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    return MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(displaySubtitle)
        .setArtworkUri(artworkUri)
        .setMediaType(
            if (isSeries) MediaMetadata.MEDIA_TYPE_TV_SHOW
            else MediaMetadata.MEDIA_TYPE_MOVIE
        )
        .build()
}

/**
 * Pushes the current media metadata into the active [MediaSession] so that
 * Google Home and other system surfaces display up-to-date information.
 *
 * This should be called:
 * - Right after the MediaSession is created
 * - When switching episodes
 * - When TMDB enrichment updates the title / artwork
 */
internal fun PlayerRuntimeController.updateMediaSessionMetadata() {
    if (currentMediaSession == null) return
    val metadata = buildMediaSessionMetadata()
    val mediaId = currentFilename?.takeIf { it.isNotBlank() }
    try {
        // Media3 MediaSession reads metadata from the player's current MediaItem.
        // Setting mediaMetadata on the player propagates to the session automatically.
        val player = _exoPlayer
        if (player != null) {
            val current = player.currentMediaItem
            if (current != null) {
                val updated = current.buildUpon()
                    .apply {
                        mediaId?.let(::setMediaId)
                    }
                    .setMediaMetadata(metadata)
                    .build()
                player.replaceMediaItem(player.currentMediaItemIndex, updated)
            }
            // No current MediaItem yet (e.g. player just built, source not set) means there is
            // nothing to attach metadata to. Setting a placeholder item was tried here and can
            // never work: a MediaItem carrying only metadata has no localConfiguration, and
            // DefaultMediaSourceFactory.createMediaSource requires one, so setMediaItem threw
            // every time. The metadata is applied on the next call, once the real item exists.
        }
        mpvMediaSessionPlayer?.refresh()
        Log.d(
            PlayerRuntimeController.TAG,
            "MediaSession metadata updated: title=${metadata.title}, " +
                "artist=${metadata.artist}, mediaId=$mediaId, artworkUri=${metadata.artworkUri}"
        )
    } catch (e: Exception) {
        Log.w(PlayerRuntimeController.TAG, "Failed to update MediaSession metadata", e)
    }
}

/** Releases the current engine's session and its mpv facade, if any. */
internal fun PlayerRuntimeController.releaseMediaSession() {
    runCatching { currentMediaSession?.release() }
    currentMediaSession = null
    runCatching { mpvMediaSessionPlayer?.release() }
    mpvMediaSessionPlayer = null
}

internal fun PlayerRuntimeController.installExoMediaSession(player: ExoPlayer) {
    releaseMediaSession()
    if (player.canAdvertiseSession()) {
        currentMediaSession = MediaSession.Builder(context, SafeMediaSessionPlayer(player)).build()
        updateMediaSessionMetadata()
    }
}

/** Creates the foreground-only MediaSession used by Bluetooth and other Android media controls. */
internal fun PlayerRuntimeController.installMpvMediaSession() {
    runCatching {
        if (!isUsingMpvEngine() || isInBackground || mpvView == null || currentStreamUrl.isBlank()) return
        if (currentMediaSession != null && mpvMediaSessionPlayer != null) {
            mpvMediaSessionPlayer?.refresh()
            return
        }
        releaseMediaSession()
        val sessionPlayer = MpvMediaSessionPlayer(
            snapshotProvider = ::mpvMediaSessionSnapshot,
            commandTarget = object : MpvMediaSessionCommandTarget {
                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    if (!isUsingMpvEngine() || isInBackground) return
                    if (isPlaybackCurrentlyPlaying() != playWhenReady) {
                        // MediaSession commands should behave like ExoPlayer's session controls:
                        // change playback without treating the button press as an in-player gesture.
                        onEvent(PlayerEvent.OnPlayPause, showPlaybackControls = false)
                    }
                }

                override fun seekTo(positionMs: Long) {
                    if (!isUsingMpvEngine() || isInBackground) return
                    onEvent(PlayerEvent.OnSeekTo(positionMs), showPlaybackControls = false)
                }
            }
        )
        mpvMediaSessionPlayer = sessionPlayer
        currentMediaSession = MediaSession.Builder(context, sessionPlayer).build()
        updateMediaSessionMetadata()
    }.onFailure { error ->
        releaseMediaSession()
        Log.w(PlayerRuntimeController.TAG, "Failed to install MPV MediaSession", error)
    }
}

internal fun PlayerRuntimeController.refreshMpvMediaSession() {
    mpvMediaSessionPlayer?.refresh()
}

private fun PlayerRuntimeController.mpvMediaSessionSnapshot(): MpvMediaSessionSnapshot {
    val view = mpvView
    val positionMs = view?.currentPositionMs()?.coerceAtLeast(0L) ?: 0L
    val durationMs = view?.durationMs()?.coerceAtLeast(0L) ?: 0L
    val bufferedPositionMs = positionMs + ((view?.demuxerCacheDurationSec() ?: 0.0) * 1_000.0).toLong()
    val metadata = buildMediaSessionMetadata()
    // Match ExoPlayer's default ID without publishing potentially sensitive stream URLs.
    val mediaId = currentFilename?.takeIf { it.isNotBlank() } ?: MediaItem.DEFAULT_MEDIA_ID
    return MpvMediaSessionSnapshot(
        mediaId = mediaId,
        mediaItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build(),
        durationMs = durationMs,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        isPlaying = view?.isPlayingNow() == true,
        isBuffering = _uiState.value.isBuffering,
        isEnded = _uiState.value.playbackEnded,
        // External seek controls must also respect the app's live-content policy.
        isSeekable = !_playbackTimeline.value.isLive && view?.isLiveStreamNow() != true,
        speed = _uiState.value.playbackSpeed,
    )
}

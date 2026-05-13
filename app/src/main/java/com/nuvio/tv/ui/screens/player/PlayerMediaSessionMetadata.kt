package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaMetadata

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
    val session = currentMediaSession ?: return
    val metadata = buildMediaSessionMetadata()
    val mediaId = currentFilename?.takeIf { it.isNotBlank() }
    try {
        // Media3 MediaSession reads metadata from the player's current MediaItem.
        // Setting mediaMetadata on the player propagates to the session automatically.
        _exoPlayer?.let { player ->
            val current = player.currentMediaItem ?: return@let
            val updated = current.buildUpon()
                .apply {
                    mediaId?.let(::setMediaId)
                }
                .setMediaMetadata(metadata)
                .build()
            player.replaceMediaItem(player.currentMediaItemIndex, updated)
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "MediaSession metadata updated: title=${metadata.title}, " +
                "artist=${metadata.artist}, mediaId=$mediaId, artworkUri=${metadata.artworkUri}"
        )
    } catch (e: Exception) {
        Log.w(PlayerRuntimeController.TAG, "Failed to update MediaSession metadata", e)
    }
}

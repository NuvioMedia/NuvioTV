package com.nuvio.tv.ui.screens.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A small Media3 facade that lets Android media controls operate libmpv. */
internal data class MpvMediaSessionSnapshot(
    val mediaId: String,
    val mediaItem: MediaItem,
    val durationMs: Long,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isEnded: Boolean,
    val isSeekable: Boolean,
    val speed: Float,
)

internal interface MpvMediaSessionCommandTarget {
    fun setPlayWhenReady(playWhenReady: Boolean)
    fun seekTo(positionMs: Long)
}

/**
 * MediaSession requires a [Player]. mpv does not implement Media3's player contract, so this
 * adapter exposes the single active mpv item and forwards only operations mpv supports.
 */
internal class MpvMediaSessionPlayer(
    private val snapshotProvider: () -> MpvMediaSessionSnapshot,
    private val commandTarget: MpvMediaSessionCommandTarget,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    fun refresh() = invalidateState()

    override fun getState(): State {
        val snapshot = snapshotProvider()
        // Media3 exposes speed and pitch as one command; mpv's speed property cannot apply an
        // independent pitch, so keep that command unavailable for this adapter.
        val commandsBuilder = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_TRACKS,
            )
        if (snapshot.isSeekable) {
            commandsBuilder.addAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
        }
        val commands = commandsBuilder.build()
        val durationUs = if (snapshot.durationMs > 0L) {
            snapshot.durationMs * 1_000L
        } else {
            C.TIME_UNSET
        }
        val mediaItemData = MediaItemData.Builder(snapshot.mediaId)
            .setMediaItem(snapshot.mediaItem)
            .setMediaMetadata(snapshot.mediaItem.mediaMetadata)
            .setDurationUs(durationUs)
            .setIsSeekable(snapshot.isSeekable)
            .build()
        val playbackState = when {
            snapshot.isEnded -> Player.STATE_ENDED
            snapshot.isBuffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(snapshot.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setSeekBackIncrementMs(C.DEFAULT_SEEK_BACK_INCREMENT_MS)
            .setSeekForwardIncrementMs(C.DEFAULT_SEEK_FORWARD_INCREMENT_MS)
            .setPlaybackParameters(PlaybackParameters(snapshot.speed))
            .setContentPositionMs(snapshot.positionMs.coerceAtLeast(0L))
            .setContentBufferedPositionMs(
                SimpleBasePlayer.PositionSupplier.getConstant(
                    snapshot.bufferedPositionMs.coerceAtLeast(snapshot.positionMs)
                )
            )
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<Void> {
        commandTarget.setPlayWhenReady(playWhenReady)
        return immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, command: Int): ListenableFuture<Void> {
        if (mediaItemIndex == 0) commandTarget.seekTo(positionMs.coerceAtLeast(0L))
        return immediateVoidFuture()
    }

    // Only release the Media3 facade; the controller owns mpv's lifetime.
    override fun handleRelease(): ListenableFuture<Void> = immediateVoidFuture()

    /** Media3 declares a non-null Java [Void] generic while Guava exposes it as nullable to Kotlin. */
    @Suppress("UNCHECKED_CAST")
    private fun immediateVoidFuture(): ListenableFuture<Void> =
        Futures.immediateVoidFuture() as ListenableFuture<Void>
}

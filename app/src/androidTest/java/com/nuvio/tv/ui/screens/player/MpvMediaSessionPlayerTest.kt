package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvMediaSessionPlayerTest {

    @Test
    fun exposesSingleItemControlsAndForwardsCommandsToMpvTarget() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val target = RecordingTarget()
            val player = MpvMediaSessionPlayer(
                // A playing snapshot extrapolates position, making the exact assertion time-dependent.
                snapshotProvider = { snapshot().copy(isPlaying = false) },
                commandTarget = target
            )

            assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))
            assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

            player.pause()
            player.play()
            player.seekTo(12_345L)

            assertEquals(listOf(false, true), target.playWhenReadyCalls)
            assertEquals(listOf(12_345L), target.seekCalls)
            assertEquals(12_000L, player.duration)
            assertEquals(2_000L, player.currentPosition)
            player.release()
        }
    }

    @Test
    fun doesNotExposeSeekCommandsForUnseekableItem() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = MpvMediaSessionPlayer(
                snapshotProvider = { snapshot(isSeekable = false) },
                commandTarget = RecordingTarget()
            )

            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))
            assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
            player.release()
        }
    }

    @Test
    fun externalPauseRefreshNotifiesListenersAndAllowsOnePlayRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var currentSnapshot = snapshot()
        val target = RecordingTarget { playWhenReady ->
            currentSnapshot = currentSnapshot.copy(isPlaying = playWhenReady)
        }
        val playWhenReadyChanges = mutableListOf<Boolean>()
        lateinit var player: MpvMediaSessionPlayer

        instrumentation.runOnMainSync {
            player = MpvMediaSessionPlayer(
                snapshotProvider = { currentSnapshot },
                commandTarget = target
            )
            assertTrue(player.isPlaying)
            player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    playWhenReadyChanges += playWhenReady
                }
            })

            // The app pauses mpv directly, without a command through the adapter or a progress tick.
            currentSnapshot = currentSnapshot.copy(isPlaying = false)
            player.refresh()

            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)
            assertEquals(2_000L, player.currentPosition)
            assertEquals(listOf(false), playWhenReadyChanges)
            assertTrue(target.playWhenReadyCalls.isEmpty())
            assertTrue(target.seekCalls.isEmpty())
        }

        // Let elapsed time advance without blocking the player's main thread.
        SystemClock.sleep(50L)
        instrumentation.runOnMainSync {
            assertEquals(2_000L, player.currentPosition)
            player.play()

            assertEquals(listOf(true), target.playWhenReadyCalls)
            assertTrue(target.seekCalls.isEmpty())
            assertTrue(player.isPlaying)
            assertEquals(listOf(false, true), playWhenReadyChanges)
            player.release()
        }
    }

    @Test
    fun externalSeekRefreshUpdatesPositionWhilePausedWithoutSendingCommands() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var currentSnapshot = snapshot().copy(isPlaying = false)
            val target = RecordingTarget()
            val player = MpvMediaSessionPlayer(
                snapshotProvider = { currentSnapshot },
                commandTarget = target
            )
            assertEquals(2_000L, player.currentPosition)

            currentSnapshot = currentSnapshot.copy(positionMs = 6_000L)
            player.refresh()

            assertEquals(6_000L, player.currentPosition)
            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)
            assertTrue(target.playWhenReadyCalls.isEmpty())
            assertTrue(target.seekCalls.isEmpty())
            player.release()
        }
    }

    @Test
    fun releaseStopsForwardingAndRefreshesWithoutChangingMpvPlayback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var snapshotReads = 0
            val target = RecordingTarget()
            val player = MpvMediaSessionPlayer(
                snapshotProvider = {
                    snapshotReads++
                    snapshot()
                },
                commandTarget = target
            )
            assertTrue(player.isPlaying)
            assertTrue(player.isCommandAvailable(Player.COMMAND_RELEASE))

            player.release()
            val readsAfterRelease = snapshotReads
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.isPlaying)

            player.pause()
            player.play()
            player.seekTo(6_000L)
            player.refresh()
            player.release()

            assertTrue(target.playWhenReadyCalls.isEmpty())
            assertTrue(target.seekCalls.isEmpty())
            assertEquals(readsAfterRelease, snapshotReads)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        }
    }

    private fun snapshot(isSeekable: Boolean = true) = MpvMediaSessionSnapshot(
        mediaId = "stream-id",
        mediaItem = MediaItem.Builder()
            .setMediaId("stream-id")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Episode").build())
            .build(),
        durationMs = 12_000L,
        positionMs = 2_000L,
        bufferedPositionMs = 4_000L,
        isPlaying = true,
        isBuffering = false,
        isEnded = false,
        isSeekable = isSeekable,
        speed = 1f,
    )

    private class RecordingTarget(
        private val onSetPlayWhenReady: (Boolean) -> Unit = {},
    ) : MpvMediaSessionCommandTarget {
        val playWhenReadyCalls = mutableListOf<Boolean>()
        val seekCalls = mutableListOf<Long>()

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            playWhenReadyCalls += playWhenReady
            onSetPlayWhenReady(playWhenReady)
        }

        override fun seekTo(positionMs: Long) {
            seekCalls += positionMs
        }
    }
}

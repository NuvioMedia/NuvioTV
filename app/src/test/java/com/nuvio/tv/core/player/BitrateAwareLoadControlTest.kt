package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

@UnstableApi
class BitrateAwareLoadControlTest {

    private class FakeInternalPlayer(@JvmField val backBufferDurationUs: Long)

    @Test
    fun `back buffer starts at configured value and lowers to 0 on override including internalPlayer`() {
        val lc = BitrateAwareLoadControl(
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            prioritizeTimeOverSizeThresholds = true,
            backBufferDurationMs = 15_000,
            retainBackBufferFromKeyframe = true,
            budgetBytes = 100L * 1024L * 1024L
        )

        assertEquals(15_000_000L, lc.getBackBufferDurationUs(PlayerId.UNSET))

        val fakeInternal = FakeInternalPlayer(backBufferDurationUs = 15_000_000L)
        val fakePlayer = object : ExoPlayer by (Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { _, method, _ ->
            if (method.name == "getPlaybackLooper") null else null
        } as ExoPlayer) {
            @Suppress("unused")
            private val internalPlayer = fakeInternal
        }

        lc.setBackBufferDurationOverrideMs(0, fakePlayer)

        assertEquals(0L, lc.getBackBufferDurationUs(PlayerId.UNSET))
        val updatedInternalUs = FakeInternalPlayer::class.java
            .getDeclaredField("backBufferDurationUs")
            .apply { isAccessible = true }
            .getLong(fakeInternal)
        assertEquals(0L, updatedInternalUs)
    }

    @Test
    fun `setBudgetBytesOverride updates active loadingStates after onTracksSelected`() {
        val initialBudget = 100 * 1024 * 1024
        val shrunkBudget = 55 * 1024 * 1024
        val lc = BitrateAwareLoadControl(
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            prioritizeTimeOverSizeThresholds = true,
            backBufferDurationMs = 15_000,
            retainBackBufferFromKeyframe = true,
            budgetBytes = initialBudget.toLong()
        )

        val playerId = PlayerId.UNSET
        lc.onPrepared(playerId)
        val params = LoadControl.Parameters(
            playerId,
            androidx.media3.common.Timeline.EMPTY,
            MediaSource.MediaPeriodId(Any()),
            /* playbackPositionUs= */ 0L,
            /* bufferedDurationUs= */ 0L,
            /* playbackSpeed= */ 1f,
            /* playWhenReady= */ true,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET
        )
        lc.onTracksSelected(params, TrackGroupArray.EMPTY, emptyArray<ExoTrackSelection?>())

        val calcTotalMethod = DefaultLoadControl::class.java
            .getDeclaredMethod("calculateTotalTargetBufferBytes")
            .apply { isAccessible = true }

        assertEquals(initialBudget, calcTotalMethod.invoke(lc) as Int)

        lc.setBudgetBytesOverride(shrunkBudget.toLong())

        assertEquals(shrunkBudget, calcTotalMethod.invoke(lc) as Int)
    }
}

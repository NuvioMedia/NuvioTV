package com.nuvio.tv.core.player

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.domain.model.NavigationMotion

/** Real Media3 and a generated local WAV. Proves pool/audio lifecycle, not video/HDR or remote trailers. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TrailerPlayerPoolTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun anOldOwnerCannotStopTheNewOwnersRealPlayback() {
        val clip = wave()
        lateinit var pool: TrailerPlayerPool
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        var created=false
        val first = Any(); val second = Any(); val ready = AtomicBoolean()
        try {
            instrumentation.runOnMainSync {
                pool = TrailerPlayerPool(context, forceNative=false)
                created=true
                player = pool.acquire(first)!!
                assertEquals(0f, player.volume, 0.0001f)
                assertSame(player, pool.acquire(second))
                assertFalse(pool.isOwner(first)); assertTrue(pool.isOwner(second))
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.set(true) }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)))
                player.prepare(); player.playWhenReady=true
            }
            await(ready)
            instrumentation.runOnMainSync {
                pool.stop(first)
                assertSame(second, pool.activeOwner.value)
                assertEquals(Player.STATE_READY, player.playbackState)
                assertTrue(player.playWhenReady); assertEquals(1, player.mediaItemCount)
                pool.stop(second)
                assertNull(pool.activeOwner.value)
                assertFalse(player.playWhenReady); assertEquals(0, player.mediaItemCount)
            }
        } finally {
            instrumentation.runOnMainSync { if (created) pool.release() }
            clip.delete()
        }
    }

    @Test fun fullPlaybackHandoffCannotBeReclaimedByAStaleCard() {
        lateinit var pool: TrailerPlayerPool
        var created=false
        try {
            instrumentation.runOnMainSync {
                pool=TrailerPlayerPool(context, forceNative=false)
                created=true
                val first=Any(); val second=Any()
                val previous=pool.acquire(first)!!
                val generation=pool.availabilityGeneration.value
                pool.yield()
                assertNull(pool.activeOwner.value); assertNull(pool.acquire(second))
                assertTrue(pool.availabilityGeneration.value > generation)
                val yielded=pool.availabilityGeneration.value
                pool.reclaim()
                assertTrue(pool.availabilityGeneration.value > yielded)
                val current=pool.acquire(second)!!
                assertNotSame(previous,current)
                pool.stop(first)
                assertSame(second,pool.activeOwner.value)
                pool.release()
                assertNull(pool.activeOwner.value); assertNull(pool.acquire(first))
            }
        } finally { instrumentation.runOnMainSync { if (created) pool.release() } }
    }

    @Test fun previewStartsSilentWithConservativeVideoSelection() {
        lateinit var pool: TrailerPlayerPool
        var created=false
        try {
            instrumentation.runOnMainSync {
                pool=TrailerPlayerPool(context, forceNative=false)
                created=true
                val player=pool.acquire(Any())!!
                assertEquals(0f,player.volume,0.0001f)
                val policy=player.trackSelectionParameters
                assertEquals(1280,policy.maxVideoWidth); assertEquals(720,policy.maxVideoHeight)
                assertEquals(4_000_000,policy.maxVideoBitrate)
                assertFalse(player.playWhenReady)
            }
        } finally { instrumentation.runOnMainSync { if (created) pool.release() } }
    }

    @Test fun disposingTheOldRealComposeSurfacePreservesTheNewSurface() {
        val firstClip=wave(); val secondClip=wave()
        lateinit var pool: TrailerPlayerPool
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        var created=false
        val firstMounted=mutableStateOf(true); val secondMounted=mutableStateOf(false)
        try {
            instrumentation.runOnMainSync { pool=TrailerPlayerPool(context,forceNative=false); created=true }
            compose.setContent { NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                Box {
                    if (firstMounted.value) TrailerPlayer(trailerUrl=Uri.fromFile(firstClip).toString(),
                        isPlaying=true,onEnded={},modifier=Modifier.size(200.dp,120.dp),trailerPlayerPool=pool)
                    if (secondMounted.value) TrailerPlayer(trailerUrl=Uri.fromFile(secondClip).toString(),
                        isPlaying=true,onEnded={},modifier=Modifier.size(200.dp,120.dp),trailerPlayerPool=pool)
                }
            } }
            compose.waitUntil(timeoutMillis=8_000) { pool.activeOwner.value != null }
            val firstOwner=pool.activeOwner.value!!
            instrumentation.runOnMainSync { player=pool.acquire(firstOwner)!! }
            compose.runOnIdle { secondMounted.value=true }
            compose.waitUntil(timeoutMillis=8_000) {
                var ready=false
                instrumentation.runOnMainSync {
                    ready=pool.activeOwner.value != null && pool.activeOwner.value !== firstOwner &&
                        player.playbackState == Player.STATE_READY && player.currentMediaItem?.localConfiguration?.uri == Uri.fromFile(secondClip)
                }
                ready
            }
            val secondOwner=pool.activeOwner.value
            compose.runOnIdle { firstMounted.value=false }
            compose.waitForIdle()
            instrumentation.runOnMainSync {
                assertSame(secondOwner,pool.activeOwner.value)
                assertTrue(player.playWhenReady); assertEquals(1,player.mediaItemCount)
                assertEquals(Uri.fromFile(secondClip),player.currentMediaItem?.localConfiguration?.uri)
                assertEquals(0f,player.volume,0.0001f)
            }
            compose.runOnIdle { secondMounted.value=false }
            compose.waitForIdle()
            instrumentation.runOnMainSync { assertNull(pool.activeOwner.value); assertEquals(0,player.mediaItemCount) }
        } finally {
            instrumentation.runOnMainSync { if (created) pool.release() }
            firstClip.delete(); secondClip.delete()
        }
    }

    private fun await(ready: AtomicBoolean) {
        val deadline=System.nanoTime()+10_000_000_000L
        while (!ready.get() && System.nanoTime()<deadline) Thread.sleep(20)
        assertTrue("The actual Media3 renderer must prepare the generated local audio",ready.get())
    }

    private fun wave(): File = originalPreviewWave(context)
}

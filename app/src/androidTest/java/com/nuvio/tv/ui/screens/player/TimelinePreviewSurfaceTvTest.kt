package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.timeline.TelumiaFrameExtractor
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Production surface and progress widget, actual local AVC frames, recording seek callbacks.
 * This is not authenticated MainActivity or all-player-route proof. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimelinePreviewSurfaceTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var activeClip: File? = null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun decoded(durationSeconds: Int = 6, block: (List<TimelineFrameImage>) -> Unit) {
        val clip = originalTimelineVideo(context, durationSeconds)
        activeClip = clip
        var reader: TelumiaFrameExtractor? = null
        try {
            main {
                reader = TelumiaFrameExtractor(context, TelumiaFrameExtractor.Configuration.Builder().build(),
                    PlayerMediaSourceFactory(context).timelineMediaSourceFactory(Uri.fromFile(clip).toString(), emptyMap()))
                reader!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)),
                    listOf(Presentation.createForWidthAndHeight(320,180,Presentation.LAYOUT_SCALE_TO_FIT)))
            }
            val frames = listOf(0L, 1500L, 3000L, 4500L).map { position ->
                lateinit var future: com.google.common.util.concurrent.ListenableFuture<TelumiaFrameExtractor.Frame>
                main { future = reader!!.getFrame(position) }
                val frame = try { future.get(8, TimeUnit.SECONDS) }
                catch (timeout: java.util.concurrent.TimeoutException) {
                    var snapshot = ""
                    main {
                        val owned = TelumiaFrameExtractor::class.java.getDeclaredField("player").apply {
                            isAccessible = true
                        }.get(reader) as ExoPlayer
                        val counters = owned.videoDecoderCounters
                        counters?.ensureUpdated()
                        snapshot = "state=${owned.playbackState}, position=${owned.currentPosition}, " +
                            "buffered=${owned.bufferedPosition}, duration=${owned.duration}, " +
                            "queued=${counters?.queuedInputBufferCount}, rendered=${counters?.renderedOutputBufferCount}, " +
                            "skipped=${counters?.skippedOutputBufferCount}, dropped=${counters?.droppedBufferCount}"
                    }
                    throw AssertionError("Native fixture frame request=$position ms; $snapshot", timeout)
                }
                assertEquals(position, frame.presentationTimeMs)
                TimelineFrameImage(position, frame.presentationTimeMs, frame.bitmap)
            }
            block(frames)
        } finally { main { reader?.release() }; clip.delete(); activeClip = null }
    }

    private fun surface(state: MutableState<TimelineFrameState>, mode: NavigationMotion) {
        compose.setContent { NuvioTheme(navigationMotion = mode) {
            Box(Modifier.fillMaxSize().background(Color(0xFF060C13)).padding(16.dp).testTag("preview-viewport")) {
                PlayerTimelinePreviewSurface(state.value, 4500, 4500,
                    compact = LocalConfiguration.current.screenHeightDp < 440)
            }
        } }
    }

    private fun assertFits(tag: String) {
        val parent = compose.onNodeWithTag("preview-viewport").getUnclippedBoundsInRoot()
        val node = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("$tag must fit above and below", node.top >= parent.top && node.bottom <= parent.bottom)
        assertTrue("$tag must fit left and right", node.left >= parent.left && node.right <= parent.right)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        var rendered: Bitmap? = null
        // Semantic nodes can be ready before the activity's first framebuffer commit.
        // Require the actual decoded blue frame and the dark viewport in the screenshot.
        compose.waitUntil(5000) {
            val candidate = instrumentation.uiAutomation.takeScreenshot()
            val blue = candidate.getPixel(candidate.width / 2, candidate.height / 5)
            val backdrop = candidate.getPixel(candidate.width / 10, candidate.height / 2)
            val visible = android.graphics.Color.blue(blue) > 180 && android.graphics.Color.red(blue) < 60 &&
                android.graphics.Color.red(backdrop) < 40 && android.graphics.Color.green(backdrop) < 40
            if (visible) rendered = candidate else candidate.recycle()
            visible
        }
        File(context.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(requireNotNull(rendered).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        rendered!!.recycle()
    }

    @Test fun fullMotionShowsRealFramesAndOrderedFilmstripWithinTheViewport() = decoded { frames ->
        val state = mutableStateOf<TimelineFrameState>(TimelineFrameState.Ready(frames.last(), frames.dropLast(1)))
        surface(state, NavigationMotion.FULL)
        compose.onNodeWithTag("timeline-preview-position").assertTextEquals(context.getString(R.string.timeline_preview_position, formatTime(4500)))
        compose.onNodeWithTag("timeline-preview-image").assertExists()
        compose.onNodeWithTag("timeline-preview-filmstrip").assertExists()
        frames.forEach { compose.onNodeWithTag("timeline-preview-tile-${it.decodedMs}").assertExists() }
        assertFits("timeline-preview-surface")
        assertFits("timeline-preview-filmstrip")
        capture("telumia-timeline-preview-full.png")
    }

    @Test fun reducedMotionKeepsTheSameActualFrameAndGeometry() = decoded { frames ->
        surface(mutableStateOf<TimelineFrameState>(TimelineFrameState.Ready(frames.last(), frames.dropLast(1))), NavigationMotion.REDUCED)
        compose.onNodeWithTag("timeline-preview-image").assertExists()
        assertFits("timeline-preview-surface")
        assertFits("timeline-preview-filmstrip")
        capture("telumia-timeline-preview-reduced.png")
    }

    @Test fun loadingErrorStaleTimestampAndRecoveryNeverSubstituteAFakeImage() = decoded { frames ->
        val state = mutableStateOf<TimelineFrameState>(TimelineFrameState.Loading(4500))
        surface(state, NavigationMotion.OFF)
        compose.onNodeWithTag("timeline-preview-image").assertDoesNotExist()
        compose.onNodeWithTag("timeline-preview-status").assertTextEquals(context.getString(R.string.timeline_preview_loading))
        compose.runOnIdle { state.value = TimelineFrameState.Unavailable(4500) }
        compose.onNodeWithTag("timeline-preview-status").assertTextEquals(context.getString(R.string.timeline_preview_unavailable))
        compose.runOnIdle { state.value = TimelineFrameState.Ready(frames.first()) }
        compose.onNodeWithTag("timeline-preview-image").assertDoesNotExist()
        compose.runOnIdle { state.value = TimelineFrameState.Ready(frames.last()) }
        compose.onNodeWithTag("timeline-preview-image").assertExists()
        compose.runOnIdle { state.value = TimelineFrameState.Idle }
        compose.onNodeWithTag("timeline-preview-image").assertDoesNotExist()
    }

    @Test fun previewTilesDoNotStealDpadFocusAndSeekStillCommitsOnlyOnKeyUp() = decoded(durationSeconds = 30) { frames ->
        val focus = FocusRequester()
        val up = FocusRequester()
        val pending = mutableStateOf(2000L)
        var duration = 0L
        var delta: Long? = null
        var commits = 0
        var primary: ExoPlayer? = null
        val prepared = java.util.concurrent.atomic.AtomicBoolean()
        main { primary = ExoPlayer.Builder(context).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) prepared.set(true)
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.fromFile(requireNotNull(activeClip))))
            prepare(); pause(); seekTo(2000)
        } }
        try {
            compose.waitUntil(10000) { prepared.get() }
            main { duration = primary!!.duration; assertEquals(30000L, duration) }
            compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                Column(Modifier.fillMaxSize().background(Color(0xFF060C13)).padding(16.dp).testTag("preview-viewport"),
                    verticalArrangement = Arrangement.Bottom) {
                    Button(onClick = {}, modifier = Modifier.focusRequester(up).testTag("preview-up-action")) { Text("Controles") }
                    PlayerTimelinePreviewSurface(TimelineFrameState.Ready(frames.last(), frames.dropLast(1)),4500,4500,
                        compact = LocalConfiguration.current.screenHeightDp < 440)
                        PlayerProgressBar(pending.value,duration,onSeekPreview = { value -> delta = value; pending.value = (pending.value + value).coerceIn(0,duration) },
                            onSeekCommit = { commits++; primary!!.seekTo(pending.value) },focusRequester = focus,upFocusRequester = up,
                            modifier = Modifier.testTag("preview-progress"))
                }
            } }
            compose.runOnIdle { focus.requestFocus() }
            compose.onNodeWithTag("preview-progress").assertIsFocused()
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
            compose.waitForIdle()
            assertTrue(delta != null && delta!! > 0)
            assertEquals(0, commits)
            main { assertEquals(2000L, primary!!.currentPosition) }
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT))
            compose.waitForIdle()
            assertEquals(1, commits)
            assertEquals(12000L, pending.value)
            main { assertEquals(pending.value, primary!!.currentPosition) }
            // End-of-stream is a separate boundary: Media3 clamps to duration - 1 ms.
            compose.runOnIdle { pending.value = duration - 1000 }
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
            compose.waitForIdle()
            assertEquals(duration, pending.value)
            assertEquals(1, commits)
            main { assertEquals(12000L, primary!!.currentPosition) }
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT))
            compose.waitForIdle()
            assertEquals(2, commits)
            main { assertEquals(duration - 1, primary!!.currentPosition) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.waitForIdle()
            assertEquals(3, commits)
            assertEquals(20000L, pending.value)
            main { assertEquals(pending.value, primary!!.currentPosition) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("preview-up-action").assertIsFocused()
            assertFits("timeline-preview-surface")
        } finally { main { primary?.release() } }
    }
}

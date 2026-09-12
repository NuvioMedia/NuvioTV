package com.nuvio.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.content.pm.PackageManager
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.player.ExternalAutoNextOverlay
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the real root overlay and Back paths without launching an external player. */
@RunWith(AndroidJUnit4::class)
class MainActivityOverlayBackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun overlayOwnsRemoteAndDispatcherBackThenReleasesDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Remote navigation scenario requires Android TV", instrumentation.targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        val destinationBacks = AtomicInteger()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var restore: (() -> Unit)? = null
        var overlayState: MutableStateFlow<ExternalAutoNextOverlay?>? = null
        val destinationCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { destinationBacks.incrementAndGet() }
        }
        try {
            scenario.onActivity { activity ->
                val tracker = activity.externalPlaybackTracker
                val overlayField = ExternalPlaybackTracker::class.java.getDeclaredField("_autoNextOverlay").apply { isAccessible = true }
                // Reflection is intentionally test-only: no production test hook or persisted playback fixture.
                val state = overlayField.get(tracker) as MutableStateFlow<ExternalAutoNextOverlay?>
                val pendingJob = ExternalPlaybackTracker::class.java.getDeclaredField("autoNextJob").apply { isAccessible = true }.get(tracker) as Job?
                assumeTrue("Do not interrupt an existing auto-next handoff", state.value == null && pendingJob?.isActive != true)
                val original = state.value
                val flagFields = listOf("autoNextCancelled", "autoNextChainAborted", "autoNextNavigationPending").map {
                    ExternalPlaybackTracker::class.java.getDeclaredField(it).apply { isAccessible = true }
                }
                val originalFlags = flagFields.map { it.getBoolean(tracker) }
                overlayState = state
                restore = {
                    destinationCallback.remove()
                    state.value = original
                    flagFields.zip(originalFlags).forEach { (field, value) -> field.setBoolean(tracker, value) }
                }
            }
            val marker = "Synthetic auto-next Back test"
            // Wait for an actual root composition before registering the simulated destination.
            scenario.onActivity { overlayState!!.value = ExternalAutoNextOverlay(null, null, "Synthetic episode", marker) }
            waitUntil("Initial root composition did not appear") {
                textNodes(marker).isNotEmpty()
            }
            scenario.onActivity { overlayState!!.value = null }
            waitUntil("Initial root overlay did not disappear") {
                textNodes(marker).isEmpty()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.onBackPressedDispatcher.addCallback(it, destinationCallback) }
            for (remote in listOf(true, false)) {
                destinationBacks.set(0)
                scenario.onActivity {
                    overlayState!!.value = ExternalAutoNextOverlay(null, null, "Synthetic episode", marker)
                }
                waitUntil("Root overlay did not become visible") {
                    textNodes(marker).isNotEmpty()
                }
                if (remote) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                } else {
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                }
                waitUntil("Back did not dismiss root overlay") { overlayState!!.value == null }
                assertEquals("Destination must not handle the overlay's Back", 0, destinationBacks.get())
                waitUntil("Overlay Back callback was not disposed") {
                    textNodes(marker).isEmpty()
                }
                instrumentation.waitForIdleSync()
                if (remote) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                } else {
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                }
                waitUntil("Next Back did not reach destination") { destinationBacks.get() == 1 }
            }

            scenario.onActivity { destinationCallback.remove() }
            var settingsLabel = ""
            var accountSubtitle = ""
            scenario.onActivity {
                // Activity resources include the app's selected language, unlike targetContext.
                settingsLabel = it.getString(R.string.nav_settings)
                accountSubtitle = it.getString(R.string.settings_account_section_subtitle)
            }
            fun nodes(text: String) = textNodes(text)
            repeat(4) {
                if (nodes(settingsLabel).isEmpty()) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                    instrumentation.waitForIdleSync()
                }
            }
            waitUntil("Home sidebar did not expose Settings") { nodes(settingsLabel).isNotEmpty() }
            scenario.onActivity { overlayState!!.value = ExternalAutoNextOverlay(null, null, "Synthetic episode", marker) }
            waitUntil("Overlay was not visible before navigation") { nodes(marker).isNotEmpty() }
            compose.onNodeWithText(settingsLabel).performClick()
            waitUntil("Real navigation did not compose Settings") { nodes(accountSubtitle).isNotEmpty() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            fun focusedLeft(): Int? {
                return compose.onAllNodes(isFocused(), useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()?.boundsInWindow?.left?.toInt()
            }
            val detailBoundary = instrumentation.targetContext.resources.displayMetrics.widthPixels / 3
            waitUntil("Settings detail pane did not take focus") { (focusedLeft() ?: -1) > detailBoundary }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitUntil("New destination stole Back from the already-visible overlay") { overlayState!!.value == null }
            waitUntil("Overlay callback did not leave composition") { nodes(marker).isEmpty() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitUntil("Next Back did not return Settings focus to its category rail") {
                focusedLeft()?.let { it < detailBoundary } == true
            }
        } finally {
            scenario.onActivity { restore?.invoke() }
            scenario.close()
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        assertTrue(message, condition())
    }

    // Query the real Compose semantics tree: platform accessibility omits some drawer nodes.
    private fun textNodes(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
}

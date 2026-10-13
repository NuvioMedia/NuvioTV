package com.nuvio.tv.ui.screens.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exports original test media for the real MainActivity flow, never bundled product content. */
@RunWith(AndroidJUnit4::class)
class PlayerFlowFixtureTvTest {
    @Test fun exportOriginalVideoForTheRealPlayerFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(context.getExternalFilesDir(null), "telumia-player-flow-original.mp4")
        assertFalse("Run this fixture only in the fresh owned player-flow AVD", target.exists())
        val clip = originalTimelineVideo(context, durationSeconds = 60)
        try {
            clip.copyTo(target)
            assertEquals(clip.length(), target.length())
            assertTrue(target.length() in 1..1048576)
        } finally { clip.delete() }
    }
}

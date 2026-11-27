package com.nuvio.tv

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentReplayTest {

    @Test
    fun `fresh launch from the Continue Watching row is handled`() {
        assertTrue(isFreshLaunchIntent(hasSavedInstanceState = false, intentFlags = 0))
    }

    @Test
    fun `activity recreated after process death does not replay the launch`() {
        assertFalse(isFreshLaunchIntent(hasSavedInstanceState = true, intentFlags = 0))
    }

    @Test
    fun `relaunch from Recents does not replay the launch`() {
        assertFalse(
            isFreshLaunchIntent(
                hasSavedInstanceState = false,
                intentFlags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK
            )
        )
    }
}

package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// These wrappers exist specifically to bridge real touch input into androidx.tv.material3's
// D-pad-only click path (see TvTouchable.kt). performClick() invokes the semantics onClick
// action directly - the same path D-pad/TalkBack always used - so it would pass even without
// the fix. performTouchInput { click() } drives an actual synthetic pointer gesture through the
// real hit-test/pointerInput pipeline, which is the thing that was broken and is what these
// tests must exercise to mean anything.
@RunWith(AndroidJUnit4::class)
class TvTouchableTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchTapOnCardFiresOnClickExactlyOnce() {
        var clickCount = 0
        composeRule.setContent {
            NuvioTheme {
                Card(
                    onClick = { clickCount++ },
                    modifier = Modifier.testTag("card").size(100.dp)
                ) {}
            }
        }

        composeRule.onNodeWithTag("card").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(1, clickCount)
    }

    @Test
    fun touchTapOnDisabledButtonDoesNotFireOnClick() {
        var clickCount = 0
        composeRule.setContent {
            NuvioTheme {
                Button(
                    onClick = { clickCount++ },
                    enabled = false,
                    modifier = Modifier.testTag("button").size(100.dp)
                ) {}
            }
        }

        composeRule.onNodeWithTag("button").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(0, clickCount)
    }

    @Test
    fun touchLongPressFiresOnLongClickNotOnClick() {
        var clickCount = 0
        var longClickCount = 0
        composeRule.setContent {
            NuvioTheme {
                Card(
                    onClick = { clickCount++ },
                    onLongClick = { longClickCount++ },
                    modifier = Modifier.testTag("card").size(100.dp)
                ) {}
            }
        }

        composeRule.onNodeWithTag("card").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(0, clickCount)
        assertEquals(1, longClickCount)
    }

    @Test
    fun touchTapStillFiresOnClickAfterUnrelatedRecomposition() {
        // Regression guard for the rememberUpdatedState fix: tvTouchToClick's pointerInput is
        // keyed on structural signals (enabled, whether onLongClick is present), not on the
        // onClick lambda's identity, so it must keep calling the LATEST onClick even though a
        // fresh lambda is captured on every recomposition (the common call-site pattern
        // throughout this app: onClick = { onItemClick(item) }).
        var observedCounterAtClick = -1
        composeRule.setContent {
            var counter by remember { mutableIntStateOf(0) }
            NuvioTheme {
                Card(
                    onClick = { observedCounterAtClick = counter },
                    modifier = Modifier.testTag("card").size(100.dp)
                ) {}
            }
            counter = 7
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("card").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(7, observedCounterAtClick)
    }

    @Test
    fun dpadCenterStillFiresOnClick() {
        // The whole point of TvTouchable is to ADD a touch path without breaking the existing
        // D-pad one - this verifies the real androidx.tv.material3 key handling underneath is
        // untouched by the wrapper.
        var clickCount = 0
        composeRule.setContent {
            NuvioTheme {
                Card(
                    onClick = { clickCount++ },
                    modifier = Modifier.testTag("card").size(100.dp)
                ) {}
            }
        }

        composeRule.onNodeWithTag("card").requestFocus()
        composeRule.onNodeWithTag("card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(1, clickCount)
    }

    @Test
    fun accessibilityClickActionStillFiresOnClick() {
        // performClick() drives the semantics onClick action (the TalkBack path) - must keep
        // working unchanged since the wrapper never touches the underlying component's semantics.
        var clickCount = 0
        composeRule.setContent {
            NuvioTheme {
                Button(
                    onClick = { clickCount++ },
                    modifier = Modifier.testTag("button").size(100.dp)
                ) {}
            }
        }

        composeRule.onNodeWithTag("button").performClick()
        composeRule.waitForIdle()

        assertEquals(1, clickCount)
    }
}

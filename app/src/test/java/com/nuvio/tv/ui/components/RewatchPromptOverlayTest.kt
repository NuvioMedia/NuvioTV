package com.nuvio.tv.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two answers the rewatch question has to keep straight on a remote.
 *
 * The overlay itself needs a device to draw, but the key it reacts to and the time it waits are
 * values, so they are checked here: down closes the question unanswered, and nothing else does.
 */
class RewatchPromptOverlayTest {

    @Test
    fun `down closes the question as ignore`() {
        assertEquals(
            RewatchPromptKeyOutcome.IGNORE,
            rewatchPromptKeyOutcome(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
        )
    }

    @Test
    fun `releasing down does not answer the question a second time`() {
        assertEquals(
            RewatchPromptKeyOutcome.KEEP,
            rewatchPromptKeyOutcome(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
        )
    }

    @Test
    fun `every other key stays with the buttons`() {
        // Left and right move between the answers and the click picks the focused one, so the
        // question only reacts to down, which leaves it unanswered. Back is the dialog's own
        // dismissal and does the same.
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK
        ).forEach { keyCode ->
            assertEquals(
                "key $keyCode must stay with the buttons",
                RewatchPromptKeyOutcome.KEEP,
                rewatchPromptKeyOutcome(keyCode, KeyEvent.ACTION_DOWN)
            )
        }
    }

    @Test
    fun `the question waits eight seconds for an answer`() {
        assertEquals(8_000L, REWATCH_QUESTION_TIMEOUT_MS)
    }
}

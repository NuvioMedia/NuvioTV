package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleTimingDialogFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dpadUpMovesBackThroughCueRowsAfterMovingDown() {
        val cues = List(40) { index ->
            SubtitleSyncCue(
                startTimeMs = index * 1_000L,
                endTimeMs = (index + 1) * 1_000L,
                text = "Sync cue $index"
            )
        }

        composeRule.setContent {
            NuvioTheme {
                SubtitleTimingDialog(
                    modifier = Modifier,
                    currentPositionMs = 10_000L,
                    selectedAddonSubtitle = Subtitle(
                        id = "fixture-en",
                        url = "http://fixture.test/subtitles.srt",
                        lang = "en",
                        addonName = "Focus fixture",
                        addonLogo = null
                    ),
                    cues = cues,
                    capturedVideoMs = 10_000L,
                    statusMessage = null,
                    errorMessage = null,
                    isLoadingCues = false,
                    onCaptureNow = {},
                    onCueSelected = {}
                )
            }
        }

        awaitFocus(10)
        Log.i("PR2939FocusTest", "initial focus=10 firstVisible=${firstVisibleIndex()}")
        assertCueHasRowAbove(10)

        var currentIndex = 10
        repeat(8) {
            pressAndAssert(currentIndex, Key.DirectionDown, currentIndex + 1)
            currentIndex += 1
        }

        repeat(8) {
            pressAndAssert(currentIndex, Key.DirectionUp, currentIndex - 1)
            currentIndex -= 1
        }
    }

    private fun pressAndAssert(currentIndex: Int, key: Key, expectedIndex: Int) {
        composeRule.onNodeWithTag(cueTag(currentIndex))
            .performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
        awaitFocus(expectedIndex)
        Log.i(
            "PR2939FocusTest",
            "key=$key current=$currentIndex expected=$expectedIndex firstVisible=${firstVisibleIndex()}"
        )
        if (key == Key.DirectionUp && expectedIndex > 0) {
            assertCueHasRowAbove(expectedIndex)
        }
        composeRule.onNodeWithTag(cueTag(expectedIndex)).assertIsFocused()
    }

    private fun awaitFocus(index: Int) {
        composeRule.waitUntil(5_000L) {
            runCatching {
                composeRule.onNodeWithTag(cueTag(index))
                    .fetchSemanticsNode()
                    .let { node ->
                        node.config.contains(SemanticsProperties.Focused) &&
                            node.config[SemanticsProperties.Focused]
                    }
            }.getOrDefault(false)
        }
    }

    private fun firstVisibleIndex(): Int = composeRule
        .onNodeWithTag("subtitle_timing_cue_list")
        .fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription]
        .toInt()

    private fun assertCueHasRowAbove(index: Int) {
        composeRule.waitUntil(5_000L) { firstVisibleIndex() < index }
        assertTrue("Expected a composed cue above index $index", firstVisibleIndex() < index)
    }

    private fun cueTag(index: Int): String = "subtitle_timing_cue_$index"
}

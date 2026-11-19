package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTvMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class MDBListSettingsReorderFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clickingMoveKeepsFocusOnDoneAndDoesNotJumpToSidebarRail() {
        var uiState by mutableStateOf(
            MDBListSettingsUiState(
                enabled = true,
                ratingOrder = listOf("imdb", "trakt", "tmdb")
            )
        )
        var railStoleFocus = false

        composeRule.setContent {
            NuvioTheme {
                val railRequester = remember { FocusRequester() }
                var railHadFocus by remember { mutableStateOf(false) }
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .onFocusChanged { state ->
                                if (!railHadFocus && state.hasFocus) {
                                    railStoleFocus = true
                                    runCatching { railRequester.requestFocus() }
                                }
                                railHadFocus = state.hasFocus
                            }
                    ) {
                        Button(
                            onClick = {},
                            modifier = Modifier.focusRequester(railRequester)
                        ) {
                            Text("Integrations")
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        MDBListSettingsBody(
                            uiState = uiState,
                            onEvent = {},
                            onStartMove = { key ->
                                uiState = uiState.copy(movingRatingKey = key)
                            },
                            onMoveBy = { key, direction ->
                                val list = uiState.ratingOrder.toMutableList()
                                val from = list.indexOf(key)
                                val to = from + direction
                                if (from < 0 || to !in list.indices) {
                                    null
                                } else {
                                    list.add(to, list.removeAt(from))
                                    uiState = uiState.copy(ratingOrder = list)
                                    to
                                }
                            },
                            onConfirmMove = {
                                uiState = uiState.copy(movingRatingKey = null)
                            },
                            onCancelMove = {
                                uiState = uiState.copy(movingRatingKey = null)
                            },
                            onOpenApiKeyDialog = {}
                        )
                    }
                }
            }
        }

        val moveLabel = context.getString(R.string.catalog_order_move)
        val doneLabel = context.getString(R.string.catalog_order_done)

        // Focus IMDb toggle independently (focus ring wraps only the toggle)
        val imdbToggle = composeRule.onNodeWithContentDescription("IMDb")
        imdbToggle.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        imdbToggle.assertIsFocused()

        // Focus first Move button (IMDb) and press DirectionCenter
        val firstMoveButton = composeRule.onAllNodesWithText(moveLabel).onFirst()
        firstMoveButton.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        firstMoveButton.assertIsFocused()

        railStoleFocus = false
        firstMoveButton.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        // Focus must remain on Done button and NOT jump to Integrations rail
        assertFalse("Focus should never jump to Integrations rail on Move click", railStoleFocus)
        composeRule.onNodeWithText("Integrations").assertIsNotFocused()
        val doneButton = composeRule.onNodeWithText(doneLabel)
        doneButton.assertIsDisplayed()
        doneButton.assertIsFocused()

        // Move IMDb down one slot and confirm
        doneButton.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        assertEquals(listOf("trakt", "imdb", "tmdb"), uiState.ratingOrder)
        doneButton.assertIsFocused()
        assertFalse("Focus should not jump to rail while moving", railStoleFocus)

        doneButton.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(null, uiState.movingRatingKey)
        assertEquals(listOf("trakt", "imdb", "tmdb"), uiState.ratingOrder)
        assertFalse("Focus should not jump to rail on confirm", railStoleFocus)
    }
}

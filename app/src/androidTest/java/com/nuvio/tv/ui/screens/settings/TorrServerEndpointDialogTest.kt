package com.nuvio.tv.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TorrServerEndpointDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun currentValueIsPrefilledInTheInput() {
        composeRule.setContent {
            NuvioTheme {
                TorrServerEndpointDialog(
                    currentValue = "http://10.0.0.5:8090",
                    onSave = {},
                    onDismiss = {}
                )
            }
        }

        val text = composeRule.onNodeWithTag(TorrServerSettingsTestTags.ENDPOINT_INPUT)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

        assertTrue(text.contains("http://10.0.0.5:8090"))
    }

    @Test
    fun validUrlWithoutSchemeIsNormalizedAndSaved() {
        val saved = mutableListOf<String>()
        composeRule.setContent {
            NuvioTheme {
                TorrServerEndpointDialog(
                    currentValue = "",
                    onSave = { saved.add(it) },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(TorrServerSettingsTestTags.ENDPOINT_INPUT)
            .performTextClearance()
        composeRule.onNodeWithTag(TorrServerSettingsTestTags.ENDPOINT_INPUT)
            .performTextInput("10.0.0.5:8090")
        composeRule.waitForIdle()
        activate(TorrServerSettingsTestTags.ENDPOINT_SAVE)

        assertEquals(listOf("http://10.0.0.5:8090"), saved)
    }

    @Test
    fun invalidUrlIsNotSaved() {
        val saved = mutableListOf<String>()
        composeRule.setContent {
            NuvioTheme {
                TorrServerEndpointDialog(
                    currentValue = "",
                    onSave = { saved.add(it) },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(TorrServerSettingsTestTags.ENDPOINT_INPUT)
            .performTextClearance()
        composeRule.onNodeWithTag(TorrServerSettingsTestTags.ENDPOINT_INPUT)
            .performTextInput("not a url")
        composeRule.waitForIdle()
        activate(TorrServerSettingsTestTags.ENDPOINT_SAVE)

        assertTrue(saved.isEmpty())
    }

    @Test
    fun clearSavesAnEmptyEndpoint() {
        val saved = mutableListOf<String>()
        composeRule.setContent {
            NuvioTheme {
                TorrServerEndpointDialog(
                    currentValue = "http://10.0.0.5:8090",
                    onSave = { saved.add(it) },
                    onDismiss = {}
                )
            }
        }

        activate(TorrServerSettingsTestTags.ENDPOINT_CLEAR)

        assertEquals(listOf(""), saved)
    }

    @Test
    fun saveButtonExposesClickSemantics() {
        composeRule.setContent {
            NuvioTheme {
                TorrServerEndpointDialog(
                    currentValue = "",
                    onSave = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(TorrServerSettingsTestTags.ENDPOINT_SAVE)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    private fun activate(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        node.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        composeRule.waitForIdle()
    }
}
package com.nuvio.tv.ui.screens.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAccessibilitySemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedRailItemExposesNameTabRoleAndSelectedState() {
        composeRule.setContent {
            NuvioTheme {
                SettingsRailButton(
                    title = "Playback",
                    isSelected = true,
                    onClick = {}
                )
            }
        }

        val nodes = composeRule.onAllNodes(hasContentDescription("Playback"))
        nodes.assertCountEquals(1)
        composeRule.onAllNodes(hasText("Playback")).assertCountEquals(0)
        val node = nodes.fetchSemanticsNodes().single()
        assertEquals(Role.Tab, node.config[SemanticsProperties.Role])
        assertEquals(true, node.config[SemanticsProperties.Selected])
    }

    @Test
    fun railItemExposesClickActionAndName() {
        composeRule.setContent {
            NuvioTheme {
                SettingsRailButton(
                    title = "Playback",
                    isSelected = false,
                    onClick = {}
                )
            }
        }

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        val node = composeRule.onNode(hasContentDescription("Playback")).fetchSemanticsNode()
        assertEquals(Role.Tab, node.config[SemanticsProperties.Role])
    }

    @Test
    fun topBarTabExposesItsNameRoleAndSelectedState() {
        composeRule.setContent {
            NuvioTheme {
                SettingsTopBarTab(
                    title = "Playback",
                    isSelected = true,
                    onClick = {}
                )
            }
        }

        val nodes = composeRule.onAllNodes(hasText("Playback"))
        nodes.assertCountEquals(1)
        val node = nodes.fetchSemanticsNodes().single()
        assertEquals(Role.Tab, node.config[SemanticsProperties.Role])
        assertEquals(true, node.config[SemanticsProperties.Selected])
    }
}

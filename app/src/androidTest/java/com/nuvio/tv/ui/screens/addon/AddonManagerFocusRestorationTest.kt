package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddonManagerFocusRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun catalogOrderOverridesPreviouslySavedPhoneFocus() {
        assertPendingFocusRestored("catalog")
    }

    @Test
    fun collectionsOverridesPreviouslySavedPhoneFocus() {
        assertPendingFocusRestored("collections")
    }

    @Test
    fun phoneFocusIsRestoredWhenNoNavigationTargetIsPending() {
        assertPendingFocusRestored(null)
    }

    private fun assertPendingFocusRestored(target: String?) {
        val group = FocusRequester()
        val phone = FocusRequester()
        val catalog = FocusRequester()
        val collections = FocusRequester()
        val outside = FocusRequester()
        var pendingRequester by mutableStateOf<FocusRequester?>(null)
        val focusEvents = mutableListOf<String>()

        composeRule.setContent {
            Column {
                Column(
                    Modifier
                        .focusRequester(group)
                        .addonManagerFocusRestorer(pendingRequester, phone)
                        .focusGroup()
                ) {
                    FocusTarget("phone", phone, focusEvents)
                    FocusTarget("catalog", catalog, focusEvents)
                    FocusTarget("collections", collections, focusEvents)
                }
                FocusTarget("outside", outside, focusEvents)
            }
        }

        composeRule.runOnIdle {
            phone.requestFocus()
            outside.requestFocus()
            pendingRequester = when (target) {
                "catalog" -> catalog
                "collections" -> collections
                else -> null
            }
            focusEvents.clear()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { group.requestFocus() }

        composeRule.onNodeWithTag(target ?: "phone").assertIsFocused()
        if (target != null) {
            composeRule.runOnIdle { assertFalse(focusEvents.contains("phone")) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FocusTarget(
    tag: String,
    requester: FocusRequester,
    focusEvents: MutableList<String>
) {
    Box(
        Modifier
            .size(48.dp)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) focusEvents.add(tag) }
            .focusable()
    )
}

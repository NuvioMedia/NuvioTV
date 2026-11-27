package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

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

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Test
    fun togglingCatalogHeaderCardKeepsFocusedAddonStationaryOnScreen() {
        var catalogAddonEnabled by mutableStateOf(true)
        lateinit var scrollState: ScrollState
        val items = listOf("opensubtitles", "torrentio", "cinemeta", "extra_1", "extra_2")

        composeRule.setContent {
            NuvioTheme {
                scrollState = rememberScrollState()
                TvReorderList(
                    items = items,
                    key = { it },
                    canMove = { false },
                    movingKey = null,
                    onStartMove = {},
                    onMoveBy = { _, _ -> null },
                    onConfirmMove = {},
                    onCancelMove = {},
                    modifier = Modifier.fillMaxSize(),
                    scrollState = scrollState,
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.fillMaxWidth().height(260.dp))
                            Box(Modifier.fillMaxWidth().height(120.dp))
                            if (catalogAddonEnabled) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .testTag("catalog_order_card")
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(120.dp))
                            Box(Modifier.fillMaxWidth().height(120.dp))
                        }
                    }
                ) { item, _, _, _ ->
                    Button(
                        onClick = {
                            if (item == "cinemeta") {
                                catalogAddonEnabled = !catalogAddonEnabled
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .testTag("addon_$item")
                    ) {
                        Text(item)
                    }
                }
            }
        }

        // Scroll mid-list so cinemeta is visible and not at maxValue, then focus cinemeta.
        composeRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                scrollState.scrollTo(scrollState.maxValue / 2)
            }
        }
        composeRule.onNodeWithTag("addon_cinemeta")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("addon_cinemeta").assertIsFocused()

        val midTopBefore = composeRule.onNodeWithTag("addon_cinemeta").getUnclippedBoundsInRoot().top.value
        val midScrollBefore = scrollState.value

        // Disable catalog addon -> header shrinks -> cinemeta must stay at same screen Y.
        composeRule.runOnIdle { catalogAddonEnabled = false }
        composeRule.waitForIdle()
        val midTopAfterDisable = composeRule.onNodeWithTag("addon_cinemeta").getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Expected scrollState to adjust down when header shrunk (before=$midScrollBefore, after=${scrollState.value})",
            scrollState.value < midScrollBefore
        )
        assertTrue(
            "Focused addon should not shift vertically on disable (before=$midTopBefore, after=$midTopAfterDisable)",
            abs(midTopAfterDisable - midTopBefore) <= 2f
        )

        // Re-enable catalog addon -> header grows -> cinemeta must stay at same screen Y.
        composeRule.runOnIdle { catalogAddonEnabled = true }
        composeRule.waitForIdle()
        val midTopAfterEnable = composeRule.onNodeWithTag("addon_cinemeta").getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Focused addon should not shift vertically on enable (before=$midTopBefore, after=$midTopAfterEnable)",
            abs(midTopAfterEnable - midTopBefore) <= 2f
        )

        // Now scroll to the very bottom (at maxValue) and verify disable/enable stays stationary there too.
        composeRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                scrollState.scrollTo(scrollState.maxValue)
            }
        }
        composeRule.onNodeWithTag("addon_extra_2")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        val bottomTopBefore = composeRule.onNodeWithTag("addon_extra_2").getUnclippedBoundsInRoot().top.value

        composeRule.runOnIdle { catalogAddonEnabled = false }
        composeRule.waitForIdle()
        val bottomTopAfterDisable = composeRule.onNodeWithTag("addon_extra_2").getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Bottom focused addon should not shift vertically on disable (before=$bottomTopBefore, after=$bottomTopAfterDisable)",
            abs(bottomTopAfterDisable - bottomTopBefore) <= 2f
        )

        composeRule.runOnIdle { catalogAddonEnabled = true }
        composeRule.waitForIdle()
        val bottomTopAfterEnable = composeRule.onNodeWithTag("addon_extra_2").getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Bottom focused addon should not shift vertically on enable (before=$bottomTopBefore, after=$bottomTopAfterEnable)",
            abs(bottomTopAfterEnable - bottomTopBefore) <= 2f
        )
        assertEquals(scrollState.maxValue, scrollState.value)
    }

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    @Test
    fun movingToEnableButtonOnSameCardAfterReorderConfirmDoesNotRecenterScroll() {
        var items by mutableStateOf((0 until 14).map { "cat_$it" })
        var movingKey by mutableStateOf<String?>(null)
        lateinit var scrollState: ScrollState

        composeRule.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec
                ) {
                    scrollState = rememberScrollState()
                    TvReorderList(
                        items = items,
                        key = { it },
                        canMove = { movingKey == null },
                        movingKey = movingKey,
                        onStartMove = { movingKey = it },
                        onMoveBy = { key, direction ->
                            val from = items.indexOf(key)
                            val to = from + direction
                            if (from < 0 || to !in items.indices) {
                                null
                            } else {
                                items = items.toMutableList().apply {
                                    add(to, removeAt(from))
                                }
                                to
                            }
                        },
                        onConfirmMove = { movingKey = null },
                        onCancelMove = { movingKey = null },
                        modifier = Modifier.fillMaxSize(),
                        scrollState = scrollState,
                        header = {
                            Box(Modifier.fillMaxWidth().height(160.dp))
                        },
                        useHeaderAwareBringIntoView = true,
                        scrollToTopOnEdgeItem = true,
                    ) { item, isMoving, onStartMove, onConfirmMove ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(item)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.testTag("enable_$item")
                                ) {
                                    Text("Enable")
                                }
                                Button(
                                    onClick = if (isMoving) onConfirmMove else onStartMove,
                                    modifier = Modifier.testTag("reorder_$item")
                                ) {
                                    Text("Reorder")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Focus cat_0's reorder button, enter move mode, move it down 6 slots so it rests near the bottom edge.
        composeRule.onNodeWithTag("reorder_cat_0")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reorder_cat_0").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        repeat(6) {
            composeRule.onNodeWithTag("reorder_cat_0").performKeyInput {
                pressKey(Key.DirectionDown)
            }
            composeRule.waitForIdle()
        }
        // Confirm placement of cat_0.
        composeRule.onNodeWithTag("reorder_cat_0").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        val scrollAfterConfirm = scrollState.value
        val cardTopAfterConfirm = composeRule.onNodeWithTag("reorder_cat_0").getUnclippedBoundsInRoot().top.value
        assertTrue("Expected list to be scrolled after moving card down", scrollAfterConfirm > 0)

        // Move focus left to the Enable button on the same card -> scroll must NOT recenter!
        composeRule.onNodeWithTag("reorder_cat_0").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("enable_cat_0").assertIsFocused()
        val scrollAfterLeft = scrollState.value
        val cardTopAfterLeft = composeRule.onNodeWithTag("enable_cat_0").getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Moving focus to Enable on the same placed card must not scroll/recenter (before=$scrollAfterConfirm, after=$scrollAfterLeft)",
            abs(scrollAfterLeft - scrollAfterConfirm) <= 2
        )
        assertTrue(
            "Placed card Y must remain stationary when moving to Enable (before=$cardTopAfterConfirm, after=$cardTopAfterLeft)",
            abs(cardTopAfterLeft - cardTopAfterConfirm) <= 2f
        )

        // Moving vertically to a different row must still scroll normally.
        composeRule.onNodeWithTag("enable_cat_0").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertTrue(
            "Moving down to next row should scroll again (was $scrollAfterLeft, now ${scrollState.value})",
            scrollState.value != scrollAfterLeft
        )
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

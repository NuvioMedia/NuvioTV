package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.simkl.SimklConnectionMode
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackingSettingsOverviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerRowsAreOrderedAndShowDisconnectedStatus() {
        setOverview()

        composeRule.onNodeWithTag(TrackingSettingsTestTags.TRAKT_PROVIDER).assertIsDisplayed()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.SIMKL_PROVIDER).assertIsDisplayed()
        composeRule.onAllNodesWithText(
            context.getString(R.string.tracking_status_disconnected)
        ).assertCountEquals(2)

        val traktTop = composeRule
            .onNodeWithTag(TrackingSettingsTestTags.TRAKT_PROVIDER)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val simklTop = composeRule
            .onNodeWithTag(TrackingSettingsTestTags.SIMKL_PROVIDER)
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(traktTop < simklTop)
    }

    @Test
    fun connectedProviderRowsShowUsernamesAndStatus() {
        setOverview(
            traktState = TraktUiState(
                mode = TraktConnectionMode.CONNECTED,
                username = "trakt-user"
            ),
            simklState = SimklSettingsUiState(
                mode = SimklConnectionMode.CONNECTED,
                username = "simkl-user"
            ),
            trackingState = TrackingSettingsUiState(
                connectedProviderIds = setOf(
                    TrackingProviderId.TRAKT,
                    TrackingProviderId.SIMKL
                ),
                isReady = true
            )
        )

        composeRule.onAllNodesWithText(
            context.getString(R.string.tracking_status_connected)
        ).assertCountEquals(2)
        composeRule.onNodeWithText(
            context.getString(R.string.trakt_connected_as, "trakt-user")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.simkl_connected_as, "simkl-user")
        ).assertIsDisplayed()
    }

    @Test
    fun disconnectedTraktDisablesEveryTraktFeature() {
        setOverview()
        scrollToTraktFeatures()

        composeRule.onNodeWithTag(TrackingSettingsTestTags.CONTINUE_WATCHING)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.COMMENTS)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.MORE_LIKE_THIS)
            .assertIsNotEnabled()
    }

    @Test
    fun connectedTraktRequiresTraktProgressOnlyForContinueWatchingWindow() {
        setOverview(
            traktState = TraktUiState(mode = TraktConnectionMode.CONNECTED),
            trackingState = TrackingSettingsUiState(
                watchProgressSource = WatchProgressSource.NUVIO_SYNC,
                connectedProviderIds = setOf(TrackingProviderId.TRAKT),
                isReady = true
            )
        )
        scrollToTraktFeatures()

        composeRule.onNodeWithTag(TrackingSettingsTestTags.CONTINUE_WATCHING)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.COMMENTS)
            .assertIsEnabled()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.MORE_LIKE_THIS)
            .assertIsEnabled()
    }

    @Test
    fun TraktProgressEnablesEveryConnectedTraktFeature() {
        setOverview(
            traktState = TraktUiState(mode = TraktConnectionMode.CONNECTED),
            trackingState = TrackingSettingsUiState(
                watchProgressSource = WatchProgressSource.TRAKT,
                librarySourceMode = LibrarySourceMode.TRAKT,
                connectedProviderIds = setOf(TrackingProviderId.TRAKT),
                isReady = true
            )
        )
        scrollToTraktFeatures()

        composeRule.onNodeWithTag(TrackingSettingsTestTags.CONTINUE_WATCHING)
            .assertIsEnabled()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.COMMENTS)
            .assertIsEnabled()
        composeRule.onNodeWithTag(TrackingSettingsTestTags.MORE_LIKE_THIS)
            .assertIsEnabled()
    }

    @Test
    fun initialFocusStartsOnTrakt() {
        lateinit var traktFocusRequester: FocusRequester
        composeRule.setContent {
            NuvioTheme {
                traktFocusRequester = remember { FocusRequester() }
                TrackingSettingsOverview(
                    traktState = TraktUiState(),
                    simklState = SimklSettingsUiState(),
                    trackingState = TrackingSettingsUiState(isReady = true),
                    traktFocusRequester = traktFocusRequester,
                    simklFocusRequester = remember { FocusRequester() },
                    libraryFocusRequester = remember { FocusRequester() },
                    watchProgressFocusRequester = remember { FocusRequester() },
                    continueWatchingFocusRequester = remember { FocusRequester() },
                    moreLikeThisFocusRequester = remember { FocusRequester() },
                    onTraktClick = {},
                    onSimklClick = {},
                    onLibrarySourceClick = {},
                    onWatchProgressClick = {},
                    onContinueWatchingWindowClick = {},
                    onCommentsChanged = {},
                    onMoreLikeThisClick = {}
                )
            }
        }
        composeRule.runOnIdle {
            traktFocusRequester.requestFocus()
        }

        composeRule.onNodeWithTag(TrackingSettingsTestTags.TRAKT_PROVIDER)
            .assertIsFocused()
    }

    @Test
    fun disconnectedDeviceAuthorizationShowsRetryAction() {
        composeRule.setContent {
            NuvioTheme {
                TraktAccountDialog(
                    state = TraktUiState(),
                    onStartConnection = {},
                    onRetryPolling = {},
                    onSync = {},
                    onDisconnect = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.action_retry)).assertExists()
    }

    @Test
    fun awaitingDeviceAuthorizationShowsCodeAndCancelAction() {
        composeRule.setContent {
            NuvioTheme {
                TraktAccountDialog(
                    state = TraktUiState(
                        mode = TraktConnectionMode.AWAITING_APPROVAL,
                        deviceUserCode = "ABCD1234",
                        verificationUrl = "https://trakt.tv/activate",
                        deviceCodeExpiresAtMillis = System.currentTimeMillis() + 60_000L
                    ),
                    onStartConnection = {},
                    onRetryPolling = {},
                    onSync = {},
                    onDisconnect = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("ABCD1234").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).assertExists()
    }

    private fun setOverview(
        traktState: TraktUiState = TraktUiState(),
        simklState: SimklSettingsUiState = SimklSettingsUiState(),
        trackingState: TrackingSettingsUiState = TrackingSettingsUiState(isReady = true)
    ) {
        composeRule.setContent {
            NuvioTheme {
                TrackingSettingsOverview(
                    traktState = traktState,
                    simklState = simklState,
                    trackingState = trackingState,
                    traktFocusRequester = remember { FocusRequester() },
                    simklFocusRequester = remember { FocusRequester() },
                    libraryFocusRequester = remember { FocusRequester() },
                    watchProgressFocusRequester = remember { FocusRequester() },
                    continueWatchingFocusRequester = remember { FocusRequester() },
                    moreLikeThisFocusRequester = remember { FocusRequester() },
                    onTraktClick = {},
                    onSimklClick = {},
                    onLibrarySourceClick = {},
                    onWatchProgressClick = {},
                    onContinueWatchingWindowClick = {},
                    onCommentsChanged = {},
                    onMoreLikeThisClick = {}
                )
            }
        }
    }

    private fun scrollToTraktFeatures() {
        composeRule.onNodeWithTag(TrackingSettingsTestTags.OVERVIEW_LIST)
            .performScrollToIndex(2)
    }
}

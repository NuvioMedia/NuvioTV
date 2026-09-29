package com.nuvio.tv.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.data.mdblist.MdbListDeviceSession
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListAccountDialogTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deviceAuthorizationShowsCodeVerificationAddressQrAndCancel() {
        var canceled = 0
        setDialog(
            MdbListTrackerUiState(
                session = MdbListDeviceSession(
                    "ABCD-EFGH", "https://mdblist.com/oauth/device/",
                    "https://mdblist.com/oauth/device/?user_code=ABCD-EFGH",
                    System.currentTimeMillis() + 300_000, 5, System.currentTimeMillis() + 5_000
                ),
                isPolling = true
            ),
            onDismiss = { canceled++ }
        )
        composeRule.onNodeWithText("ABCD-EFGH").assertIsDisplayed()
        composeRule.onNodeWithText("https://mdblist.com/oauth/device/").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.mdblist_qr_description)).assertIsDisplayed()
        pressButton(R.string.action_cancel)
        composeRule.runOnIdle { assertEquals(1, canceled) }
    }

    @Test
    fun failedAuthorizationOffersRetryAndDisplaysItsError() {
        var retries = 0
        setDialog(MdbListTrackerUiState(errorMessage = "Connection unavailable"), onStart = { retries++ })
        composeRule.onNodeWithText("Connection unavailable").assertIsDisplayed()
        pressButton(R.string.action_retry)
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun connectedAccountShowsStatusAndDispatchesSyncAndDisconnect() {
        var syncs = 0
        var disconnects = 0
        setDialog(
            MdbListTrackerUiState(isConnected = true, username = "mdblist-viewer", statusMessage = "Last synced just now"),
            onSync = { syncs++ }, onDisconnect = { disconnects++ }
        )
        composeRule.onNodeWithText(context.getString(R.string.mdblist_connected_as, "mdblist-viewer")).assertIsDisplayed()
        composeRule.onNodeWithText("Last synced just now").assertIsDisplayed()
        pressButton(R.string.simkl_sync_now)
        pressButton(R.string.trakt_disconnect)
        composeRule.runOnIdle {
            assertEquals(1, syncs)
            assertEquals(1, disconnects)
        }
    }

    @Test
    fun syncingAccountPreventsDuplicateSyncAndDisconnect() {
        setDialog(MdbListTrackerUiState(isConnected = true, isSyncing = true))
        composeRule.onNodeWithText(context.getString(R.string.simkl_sync_now)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.trakt_disconnect)).assertIsNotEnabled()
    }

    private fun pressButton(text: Int) {
        val button = composeRule.onNodeWithText(context.getString(text)).assertIsEnabled()
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun setDialog(
        state: MdbListTrackerUiState,
        onStart: () -> Unit = {},
        onSync: () -> Unit = {},
        onDisconnect: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.setContent {
            NuvioTheme {
                MdbListAccountDialog(state, onStart, onStart, onSync, onDisconnect, onDismiss)
            }
        }
    }
}

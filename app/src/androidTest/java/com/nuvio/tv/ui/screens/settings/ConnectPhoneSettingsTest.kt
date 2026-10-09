package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectPhoneSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pairedViewHidesQrAndRetainsFocusInClassicPane() = verifyLayout(SettingsUiStyle.CLASSIC, 560, 400)
    @Test fun pairedViewHidesQrAndRetainsFocusInShortHorizonPane() = verifyLayout(SettingsUiStyle.HORIZON, 800, 320)
    @Test fun pairedViewHidesQrAndRetainsFocusInZenPane() = verifyLayout(SettingsUiStyle.ZEN, 560, 360)

    private fun verifyLayout(style: SettingsUiStyle, width: Int, height: Int) {
        val focus = FocusRequester()
        val paired = mutableStateOf(false)
        var newCode = false
        var forgotten = false
        compose.setContent {
            NuvioTheme(settingsUiStyle = style) {
                Box(Modifier.size(width.dp, height.dp).testTag("pane")) {
                    ConnectPhoneContent(
                        bitmap = ImageBitmap(640, 640),
                        message = "In Nuvio on your phone, open Settings → TV connection and scan this code. Both devices must use your home network.",
                        paired = paired.value,
                        onNewCode = { newCode = true },
                        onForget = { forgotten = true; paired.value = false },
                        initialFocusRequester = focus,
                    )
                }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        val newButton = compose.onNodeWithText("New pairing code")
        val forgetButton = compose.onNodeWithText("Forget phone")
        compose.onNodeWithContentDescription("Pairing QR code").assertIsDisplayed()
        newButton.assertIsFocused().assertIsDisplayed().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(newCode); paired.value = true }
        compose.onNodeWithContentDescription("Pairing QR code").assertDoesNotExist()
        newButton.assertDoesNotExist()
        forgetButton.assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Phone connected. You can control TV playback from Nuvio Mobile.").assertIsDisplayed()
        val paneBounds = compose.onNodeWithTag("pane").getUnclippedBoundsInRoot()
        val bounds = forgetButton.getUnclippedBoundsInRoot()
        assertTrue("Connected action must fit without scrolling: $bounds within $paneBounds", bounds.top >= paneBounds.top && bounds.bottom <= paneBounds.bottom)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "connect-phone-${style.name}.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        forgetButton.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(forgotten) }
        newButton.assertIsFocused().assertIsDisplayed()
        forgetButton.assertDoesNotExist()
        compose.onNodeWithContentDescription("Pairing QR code").assertIsDisplayed()
    }
}

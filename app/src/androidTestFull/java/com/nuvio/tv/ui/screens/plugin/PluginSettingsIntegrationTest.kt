package com.nuvio.tv.ui.screens.plugin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.PluginRuntime
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PluginSettingsIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jsSettingsLayoutMapsToDialogInputsAndSavedValuesReturnToScraper() = runBlocking {
        val code = InstrumentationRegistry.getInstrumentation().context.assets
            .open("plugin-settings-test.js")
            .bufferedReader()
            .use { it.readText() }
        val runtime = PluginRuntime()
        val scraperId = "test-repository:settings-test"
        val layoutJson = runtime.getSettingsLayout(scraperId, code)
            ?: error("onSettings() did not return a settings layout")
        val initialSettings = mapOf(
            "update-last-scrape" to false,
            "test-text" to "existing value",
            "test-text-password" to "secret",
            "test-select" to "option-2",
            "test-toggle" to false
        )
        val savedSettings = AtomicReference<Map<String, Any>?>(null)

        composeRule.setContent {
            NuvioTheme {
                ScraperSettingsDialog(
                    scraperName = "Settings Test",
                    layoutJson = layoutJson,
                    initialSettings = initialSettings,
                    onDismiss = {},
                    onSave = { savedSettings.set(it) }
                )
            }
        }

        composeRule.onNodeWithText("Header label").assertIsDisplayed()
        composeRule.onNodeWithText("Information entry").assertIsDisplayed()
        composeRule.onNodeWithText("Text label").assertIsDisplayed()
        composeRule.onNodeWithText("existing value").assertIsDisplayed()
        composeRule.onNodeWithText("Password label").assertIsDisplayed()
        composeRule.onNodeWithText("Select").assertIsDisplayed()
        composeRule.onNodeWithText("Option 2").assertIsDisplayed()
        composeRule.onNodeWithText("Toggle")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(context.getString(R.string.plugin_settings_save))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        val settings = requireNotNull(savedSettings.get())
        assertEquals("existing value", settings["test-text"])
        assertEquals("secret", settings["test-text-password"])
        assertEquals("option-2", settings["test-select"])
        assertTrue(settings["test-toggle"] as Boolean)
        assertFalse(settings["update-last-scrape"] as Boolean)

        val results = runtime.executePlugin(
            code = code,
            tmdbId = "12345",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = scraperId,
            scraperSettings = settings,
            localStorage = mutableMapOf("previousScrape" to "stored-before-run")
        )

        assertEquals(1, results.size)
        val observed = JSONObject(results.single().title)
        assertFalse(observed.getBoolean("updateLastScrape"))
        assertEquals("existing value", observed.getString("text"))
        assertEquals("option-2", observed.getString("select"))
        assertTrue(observed.getBoolean("toggle"))
        assertEquals("stored-before-run", observed.getString("previousScrape"))
    }
}

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.*
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsenetSourcesCardTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    private fun SemanticsNodeInteraction.pressRemote() {
        performSemanticsAction(SemanticsActions.RequestFocus)
        assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun providerFormSavesCredentialsAndConnectionAllowance() {
        var saved = UsenetSourceConfiguration()
        compose.setContent {
            var configuration by remember { mutableStateOf(saved) }
            NuvioTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsenetSourcesCard(configuration, { saved = it; configuration = it }, { true }, { ProviderTestResult.SUCCESS }, remember { FocusRequester() })
                }
            }
        }
        compose.onNodeWithText(label(R.string.usenet_add_provider)).performScrollTo().pressRemote()
        compose.onNodeWithTag(label(R.string.usenet_source_name)).performTextInput("Test News")
        compose.onNodeWithTag(label(R.string.usenet_provider_host)).performTextInput("news.test")
        compose.onNodeWithTag(label(R.string.usenet_provider_username)).performScrollTo().performTextInput("user")
        compose.onNodeWithTag(label(R.string.usenet_provider_password)).performScrollTo().performTextInput("p@ss")
        compose.onNodeWithTag(label(R.string.usenet_provider_connections)).performScrollTo().performTextReplacement("30")
        compose.onNodeWithText(label(R.string.action_save)).pressRemote()
        compose.runOnIdle {
            val provider = saved.providers.single()
            assertEquals("news.test", provider.host)
            assertEquals("p@ss", provider.password)
            assertEquals(30, provider.connections)
            assertTrue(provider.tls)
        }
    }

    @Test fun providerTestShowsTheResultUntilAFieldChanges() {
        var tested: UsenetProvider? = null
        compose.setContent {
            NuvioTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsenetSourcesCard(UsenetSourceConfiguration(), {}, { true },
                        { tested = it; ProviderTestResult.AUTH }, remember { FocusRequester() })
                }
            }
        }
        compose.onNodeWithText(label(R.string.usenet_add_provider)).performScrollTo().pressRemote()
        compose.onNodeWithTag(label(R.string.usenet_source_name)).performTextInput("Test News")
        compose.onNodeWithTag(label(R.string.usenet_provider_host)).performTextInput("news.test")
        compose.onNodeWithText(label(R.string.usenet_provider_test)).pressRemote()
        compose.onNodeWithText(label(R.string.usenet_provider_test_auth)).assertExists()
        compose.runOnIdle { assertEquals("news.test", tested?.host) }
        compose.onNodeWithTag(label(R.string.usenet_provider_host)).performTextInput("x")
        compose.onNodeWithText(label(R.string.usenet_provider_test_auth)).assertDoesNotExist()
    }

    @Test fun sourceDeletionRequiresConfirmationAndPreservesOtherSources() {
        val news = UsenetProvider(name = "News", host = "news.test")
        val indexer = UsenetIndexer(name = "Index", apiUrl = "https://index.test/api")
        var saved = UsenetSourceConfiguration(providers = listOf(news), indexers = listOf(indexer))
        compose.setContent {
            var configuration by remember { mutableStateOf(saved) }
            NuvioTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    UsenetSourcesCard(configuration, { saved = it; configuration = it }, { true }, { ProviderTestResult.SUCCESS }, remember { FocusRequester() })
                }
            }
        }
        compose.onNodeWithText("News").performScrollTo().pressRemote()
        compose.onNodeWithText(label(R.string.usenet_source_delete)).pressRemote()
        compose.onNodeWithText(label(R.string.usenet_delete_confirm)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(news), saved.providers) }
        compose.onNodeWithText(label(R.string.action_cancel)).pressRemote()
        compose.onNodeWithText("News").performScrollTo().pressRemote()
        compose.onNodeWithText(label(R.string.usenet_source_delete)).pressRemote()
        compose.onNode(hasText(label(R.string.usenet_source_delete)) and hasClickAction()).pressRemote()
        compose.runOnIdle { assertTrue(saved.providers.isEmpty()); assertEquals(listOf(indexer), saved.indexers) }
    }
}

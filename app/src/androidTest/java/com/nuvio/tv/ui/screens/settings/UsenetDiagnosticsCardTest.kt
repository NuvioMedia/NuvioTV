package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsenetDiagnosticsCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun nzbHitIsVisibleWhenArticleHitsAreZero() {
        val report = """{"engine":{"nzbCache":{"lookup":"hit","bytes":41943040},"store":{"cacheHits":0}}}"""
        composeRule.setContent {
            NuvioTheme { UsenetDiagnosticsCard(2, report) }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.usenet_diag_nzb_hit), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.usenet_diag_hits), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("40.0 MiB", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun timingsAndCacheAreReadableWithoutClicking() {
        val report = """{"fastMkvStartup":true,"recordedAtMs":1788990000000,
            "marksMs":{"resolve_lock_acquired":0,"engine_ready":5,"session_request":7,
            "session_response":207,"resolved":210,"prepare":240,"decoder_init_duration":18,"first_frame":940},
            "engine":{"marksMs":{"pool_created":1,"nzb_loaded":61,"content_selected":199,
            "warmup_started":199,"head_article_ready":250,"tail_article_ready":360},
            "archiveDiscoveryMs":120,"articleSlabsBytes":12582912,
            "nzbCache":{"lookup":"miss","reason":"missing","write":"saved","bytes":18874368},
            "ranges":[{"startMs":210,"firstByteMs":360}],
            "store":{"cacheHits":3,"sharedJoins":7,"downloads":12,"cancellations":2}}}"""
        composeRule.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)
                    .padding(24.dp).testTag("usenet-diagnostics"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(3) { section -> UsenetDiagnosticsCard(section, report) }
                }
            }
        }
        composeRule.onNodeWithText("940 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usenet-diagnostics").performScrollToIndex(1)
        composeRule.onNodeWithText("120 ms", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("usenet-diagnostics").performScrollToIndex(2)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.usenet_diag_nzb_miss), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.usenet_diag_nzb_saved), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("18.0 MiB", useUnmergedTree = true).assertIsDisplayed()
        val memoryLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.usenet_diag_memory)
        composeRule.onNodeWithText(memoryLabel, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("12.0 MiB", useUnmergedTree = true).assertIsDisplayed()
    }
}

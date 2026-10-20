package com.nuvio.tv.ui.components

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTvVisualComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun dpadMovesFromChannelToProgramAndActivatesItsSuppliedKey() {
        var channelClicks = 0
        var programKey: String? = null
        compose.setContent {
            NuvioTheme(navigationMotion = NavigationMotion.OFF) {
                Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    LiveTvGuideRow("Canal de teste", "12", null, true,
                        listOf(LiveTvProgramPresentation("schedule:1", "Programa de teste", "20:00 – 21:00", true)),
                        { channelClicks++ }, { programKey = it })
                    LiveTvNowNext("Programa atual de teste", "Próximo programa de teste", "A seguir", 0.25f)
                }
            }
        }
        compose.onNodeWithText("Canal de teste").assertIsSelected().performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Programa de teste").assertIsFocused().assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, channelClicks); assertEquals("schedule:1", programKey) }
        File(instrumentation.targetContext.getExternalFilesDir(null)!!, "live-guide-components.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun unknownProgressHasNoInventedPercentageAndUpdatesAccessibly() {
        val progress = mutableStateOf<Float?>(null)
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            LiveTvNowNext("Atual", null, "A seguir", progress.value, Modifier.width(300.dp))
        } }
        val range = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onAllNodes(range).assertCountEquals(0)
        compose.runOnIdle { progress.value = 0.25f }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f))).assertIsDisplayed()
        compose.runOnIdle { progress.value = Float.POSITIVE_INFINITY }
        compose.onAllNodes(range).assertCountEquals(0)
    }

    @Test fun emptyGuideHasNoFakeProgramsOrPlaybackActions() {
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            LiveTvGuideRow("Sem programação", null, null, false, emptyList(), {}, {}, Modifier.fillMaxWidth())
        } }
        compose.onNodeWithText("Sem programação").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test fun xmlTvOnlyChannelAndProgramsDoNotExposePlaybackActions() {
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            LiveTvGuideRow(
                "Canal XMLTV",
                null,
                null,
                false,
                listOf(LiveTvProgramPresentation("guide:1", "Sem stream", "20:00 – 21:00")),
                null,
                {},
                Modifier.fillMaxWidth(),
            )
        } }
        compose.onNodeWithText("Canal XMLTV").assertIsDisplayed()
        compose.onNodeWithText("Sem stream").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun favoriteActionHasLocalizedAccessibleLabelAndInvokesItsCallback() {
        var favoriteChanges = 0
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            LiveTvGuideRow(
                "Canal favorito",
                null,
                null,
                false,
                emptyList(),
                null,
                {},
                Modifier.fillMaxWidth(),
                isFavorite = true,
                onFavoriteClick = { favoriteChanges++ },
                favoriteLabel = "Adicionar aos favoritos",
                unfavoriteLabel = "Remover dos favoritos",
            )
        } }

        compose.onNodeWithContentDescription("Remover dos favoritos").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, favoriteChanges) }
    }
}

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.ThemeSelection
import com.nuvio.tv.launcher.AppIconManager
import com.nuvio.tv.launcher.AppIconSettingsState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val gradient = CustomThemeColors(0x112233, 0x445566, 0x778899)
    private val solid = CustomThemeColors.solid(0x445566)
    private val selection = MutableStateFlow(ThemeSelection(AppTheme.CUSTOM, gradient))
    private val access = MutableStateFlow(MemberAccess.None)
    private val store = mockk<ThemeDataStore> {
        every { themeSelection } returns selection
        every { selectedFont } returns flowOf(AppFont.INTER)
        every { amoledMode } returns flowOf(false)
        every { amoledSurfacesMode } returns flowOf(false)
        every { settingsUiStyle } returns flowOf(SettingsUiStyle.CLASSIC)
        coEvery { setCustomTheme(any()) } coAnswers {
            selection.value = ThemeSelection(AppTheme.CUSTOM, firstArg())
        }
    }

    @Test
    fun nonMembersSeeAndSaveOnlySingleColorCustomThemes() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        assertTrue(AppTheme.CUSTOM in viewModel.uiState.value.availableThemes)
        assertEquals(AppTheme.CUSTOM, viewModel.uiState.value.selectedTheme)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
        assertFalse(viewModel.uiState.value.customThemeGradientEnabled)

        viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
        runCurrent()

        coVerify(exactly = 1) { store.setCustomTheme(solid) }
        assertEquals(ThemeSelection(AppTheme.CUSTOM, solid), selection.value)
    }

    @Test
    fun bothMemberTiersCanSaveAllGradientStops() = runTest {
        val viewModel = createViewModel()

        MemberTier.entries.forEach { tier ->
            access.value = MemberAccess.preview(tier)
            runCurrent()

            assertTrue(viewModel.uiState.value.customThemeGradientEnabled)
            assertEquals(gradient, viewModel.uiState.value.customThemeColors)
            viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
            runCurrent()
        }

        coVerify(exactly = MemberTier.entries.size) { store.setCustomTheme(gradient) }
    }

    @Test
    fun membershipLossKeepsSavedGradientForRenewal() = runTest {
        access.value = MemberAccess.preview(MemberTier.SUPPORTER)
        val viewModel = createViewModel()
        runCurrent()

        access.value = MemberAccess.None
        runCurrent()

        assertEquals(AppTheme.CUSTOM, viewModel.uiState.value.selectedTheme)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
        assertFalse(viewModel.uiState.value.customThemeGradientEnabled)
        assertEquals(gradient, selection.value.customColors)

        access.value = MemberAccess.preview(MemberTier.SUPPORTER_PLUS)
        runCurrent()

        assertEquals(gradient, viewModel.uiState.value.customThemeColors)
        assertTrue(viewModel.uiState.value.customThemeGradientEnabled)
        coVerify(exactly = 0) { store.setCustomTheme(any()) }
    }

    @Test
    fun membershipLossBeforeSaveConvertsTheOldEditorDraftToOneColor() = runTest {
        access.value = MemberAccess.preview(MemberTier.SUPPORTER)
        val viewModel = createViewModel()
        runCurrent()

        access.value = MemberAccess.None
        viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
        runCurrent()

        coVerify(exactly = 1) { store.setCustomTheme(solid) }
        coVerify(exactly = 0) { store.setCustomTheme(gradient) }
    }

    @Test
    fun membershipChangesUpdateEditorModeEvenWhenColorsAndEntitlementsStayTheSame() = runTest {
        selection.value = ThemeSelection(AppTheme.CUSTOM, solid)
        val viewModel = createViewModel()
        runCurrent()
        val availableThemes = viewModel.uiState.value.availableThemes

        access.value = MemberAccess(tier = MemberTier.SUPPORTER)
        runCurrent()

        assertTrue(viewModel.uiState.value.customThemeGradientEnabled)
        assertEquals(availableThemes, viewModel.uiState.value.availableThemes)
        assertEquals(solid, viewModel.uiState.value.customThemeColors)
    }

    private fun createViewModel(): ThemeSettingsViewModel {
        val members = mockk<MemberAccessRepository> { every { access } returns this@ThemeSettingsViewModelTest.access }
        val icons = mockk<AppIconManager> { every { state } returns MutableStateFlow(AppIconSettingsState()) }
        return ThemeSettingsViewModel(store, members, icons)
    }
}

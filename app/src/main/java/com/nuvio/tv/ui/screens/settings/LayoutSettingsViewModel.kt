package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LayoutSettingsUiState(
    val selectedLayout: HomeLayout = HomeLayout.MODERN,
    val hasChosen: Boolean = false,
    val availableCatalogs: List<CatalogInfo> = emptyList(),
    val heroCatalogKeys: List<String> = emptyList(),
    val sidebarCollapsedByDefault: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurEnabled: Boolean = false,
    val modernLandscapePostersEnabled: Boolean = true,
    val modernNextRowPreviewEnabled: Boolean = false,
    val heroSectionEnabled: Boolean = true,
    val searchDiscoverEnabled: Boolean = true,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val blurUnwatchedEpisodes: Boolean = false,
    val detailPageTrailerButtonEnabled: Boolean = false,
    val preferExternalMetaAddonDetail: Boolean = false
)

data class CatalogInfo(
    val key: String,
    val name: String,
    val addonName: String
)

sealed class LayoutSettingsEvent {
    data class SelectLayout(val layout: HomeLayout) : LayoutSettingsEvent()
    data class ToggleHeroCatalog(val catalogKey: String) : LayoutSettingsEvent()
    data class SetSidebarCollapsed(val collapsed: Boolean) : LayoutSettingsEvent()
    data class SetModernSidebarEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetModernSidebarBlurEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetModernLandscapePostersEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetModernNextRowPreviewEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetHeroSectionEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetSearchDiscoverEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetPosterLabelsEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetCatalogAddonNameEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetCatalogTypeSuffixEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropExpandEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropExpandDelaySeconds(val seconds: Int) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerMuted(val muted: Boolean) : LayoutSettingsEvent()
    data class SetPosterCardWidth(val widthDp: Int) : LayoutSettingsEvent()
    data class SetPosterCardCornerRadius(val cornerRadiusDp: Int) : LayoutSettingsEvent()
    data class SetBlurUnwatchedEpisodes(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerButtonEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetPreferExternalMetaAddonDetail(val enabled: Boolean) : LayoutSettingsEvent()
    data object ResetPosterCardStyle : LayoutSettingsEvent()
}

@HiltViewModel
class LayoutSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val addonRepository: AddonRepository,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LayoutSettingsUiState())
    val uiState: StateFlow<LayoutSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            layoutPreferenceDataStore.selectedLayout.collectLatest { layout ->
                _uiState.update { it.copy(selectedLayout = layout) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hasChosenLayout.collectLatest { hasChosen ->
                _uiState.update { it.copy(hasChosen = hasChosen) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.heroCatalogSelections.collectLatest { keys ->
                _uiState.update { it.copy(heroCatalogKeys = keys) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.sidebarCollapsedByDefault.collectLatest { collapsed ->
                _uiState.update { it.copy(sidebarCollapsedByDefault = collapsed) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernSidebarEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(modernSidebarEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernSidebarBlurEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(modernSidebarBlurEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernLandscapePostersEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(modernLandscapePostersEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernNextRowPreviewEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(modernNextRowPreviewEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.heroSectionEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(heroSectionEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.searchDiscoverEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(searchDiscoverEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterLabelsEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(posterLabelsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogAddonNameEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogAddonNameEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(focusedPosterBackdropExpandEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds.collectLatest { seconds ->
                _uiState.update { it.copy(focusedPosterBackdropExpandDelaySeconds = seconds) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(focusedPosterBackdropTrailerEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted.collectLatest { muted ->
                _uiState.update { it.copy(focusedPosterBackdropTrailerMuted = muted) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardWidthDp.collectLatest { widthDp ->
                _uiState.update { it.copy(posterCardWidthDp = widthDp) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardHeightDp.collectLatest { heightDp ->
                _uiState.update { it.copy(posterCardHeightDp = heightDp) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp.collectLatest { cornerRadiusDp ->
                _uiState.update { it.copy(posterCardCornerRadiusDp = cornerRadiusDp) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
                _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.detailPageTrailerButtonEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(detailPageTrailerButtonEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.preferExternalMetaAddonDetail.collectLatest { enabled ->
                _uiState.update { it.copy(preferExternalMetaAddonDetail = enabled) }
            }
        }
        loadAvailableCatalogs()
    }

    fun onEvent(event: LayoutSettingsEvent) {
        when (event) {
            is LayoutSettingsEvent.SelectLayout -> selectLayout(event.layout)
            is LayoutSettingsEvent.ToggleHeroCatalog -> toggleHeroCatalog(event.catalogKey)
            is LayoutSettingsEvent.SetSidebarCollapsed -> setSidebarCollapsed(event.collapsed)
            is LayoutSettingsEvent.SetModernSidebarEnabled -> setModernSidebarEnabled(event.enabled)
            is LayoutSettingsEvent.SetModernSidebarBlurEnabled -> setModernSidebarBlurEnabled(event.enabled)
            is LayoutSettingsEvent.SetModernLandscapePostersEnabled -> setModernLandscapePostersEnabled(event.enabled)
            is LayoutSettingsEvent.SetModernNextRowPreviewEnabled -> setModernNextRowPreviewEnabled(event.enabled)
            is LayoutSettingsEvent.SetHeroSectionEnabled -> setHeroSectionEnabled(event.enabled)
            is LayoutSettingsEvent.SetSearchDiscoverEnabled -> setSearchDiscoverEnabled(event.enabled)
            is LayoutSettingsEvent.SetPosterLabelsEnabled -> setPosterLabelsEnabled(event.enabled)
            is LayoutSettingsEvent.SetCatalogAddonNameEnabled -> setCatalogAddonNameEnabled(event.enabled)
            is LayoutSettingsEvent.SetCatalogTypeSuffixEnabled -> setCatalogTypeSuffixEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled -> setFocusedPosterBackdropExpandEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds -> setFocusedPosterBackdropExpandDelaySeconds(event.seconds)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled -> setFocusedPosterBackdropTrailerEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted -> setFocusedPosterBackdropTrailerMuted(event.muted)
            is LayoutSettingsEvent.SetPosterCardWidth -> setPosterCardWidth(event.widthDp)
            is LayoutSettingsEvent.SetPosterCardCornerRadius -> setPosterCardCornerRadius(event.cornerRadiusDp)
            is LayoutSettingsEvent.SetBlurUnwatchedEpisodes -> setBlurUnwatchedEpisodes(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled -> setDetailPageTrailerButtonEnabled(event.enabled)
            is LayoutSettingsEvent.SetPreferExternalMetaAddonDetail -> setPreferExternalMetaAddonDetail(event.enabled)
            LayoutSettingsEvent.ResetPosterCardStyle -> resetPosterCardStyle()
        }
    }

    private fun selectLayout(layout: HomeLayout) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setLayout(layout)
        }
    }

    private fun toggleHeroCatalog(catalogKey: String) {
        viewModelScope.launch {
            val selected = _uiState.value.heroCatalogKeys.toMutableList()
            if (catalogKey in selected) {
                selected.remove(catalogKey)
            } else {
                selected.add(catalogKey)
            }
            layoutPreferenceDataStore.setHeroCatalogKeys(selected)
        }
    }

    private fun setSidebarCollapsed(collapsed: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setSidebarCollapsedByDefault(collapsed)
        }
    }

    private fun setModernSidebarEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernSidebarEnabled(enabled)
        }
    }

    private fun setModernSidebarBlurEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernSidebarBlurEnabled(enabled)
        }
    }

    private fun setModernLandscapePostersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernLandscapePostersEnabled(enabled)
        }
    }

    private fun setModernNextRowPreviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernNextRowPreviewEnabled(enabled)
        }
    }

    private fun setHeroSectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setHeroSectionEnabled(enabled)
        }
    }

    private fun setSearchDiscoverEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setSearchDiscoverEnabled(enabled)
        }
    }

    private fun setPosterLabelsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterLabelsEnabled(enabled)
        }
    }

    private fun setCatalogAddonNameEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setCatalogAddonNameEnabled(enabled)
        }
    }

    private fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setCatalogTypeSuffixEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropExpandEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropExpandEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropExpandDelaySeconds(seconds: Int) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropExpandDelaySeconds(seconds)
        }
    }

    private fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerMuted(muted)
        }
    }

    private fun setPosterCardWidth(widthDp: Int) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterCardWidthDp(widthDp)
            layoutPreferenceDataStore.setPosterCardHeightDp((widthDp * 3) / 2)
        }
    }

    private fun setPosterCardCornerRadius(cornerRadiusDp: Int) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterCardCornerRadiusDp(cornerRadiusDp)
        }
    }

    private fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setDetailPageTrailerButtonEnabled(enabled)
        }
    }

    private fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setBlurUnwatchedEpisodes(enabled)
        }
    }

    private fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setPreferExternalMetaAddonDetail(enabled)
            metaRepository.clearCache()
        }
    }

    private fun resetPosterCardStyle() {
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterCardWidthDp(126)
            layoutPreferenceDataStore.setPosterCardHeightDp(189)
            layoutPreferenceDataStore.setPosterCardCornerRadiusDp(12)
        }
    }

    private fun loadAvailableCatalogs() {
        viewModelScope.launch {
            addonRepository.getInstalledAddons().collectLatest { addons ->
                val catalogs = addons.flatMap { addon ->
                    addon.catalogs
                        .filter { catalog ->
                            !catalog.extra.any { it.name == "search" && it.isRequired }
                        }
                        .map { catalog ->
                            CatalogInfo(
                                key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                                name = catalog.name,
                                addonName = addon.displayName
                            )
                        }
                }
                _uiState.update { it.copy(availableCatalogs = catalogs) }
            }
        }
    }
}

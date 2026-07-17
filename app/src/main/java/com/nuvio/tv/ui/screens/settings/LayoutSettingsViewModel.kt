package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.StreamBadgeConfigServer
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val modernLandscapePostersEnabled: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = false,
    val heroSectionEnabled: Boolean = true,
    val discoverLocation: DiscoverLocation = DiscoverLocation.IN_SEARCH,
    val lastNonOffDiscoverLocation: DiscoverLocation = DiscoverLocation.IN_SEARCH,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val classicFocusGradientEnabled: Boolean = false,
    val focusedPosterBackdropExpandEnabled: Boolean = true,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle(),
    val blurUnwatchedEpisodes: Boolean = false,
    val blurContinueWatchingNextUp: Boolean = false,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val detailPageTrailerButtonEnabled: Boolean = true,
    val detailPageTrailerAutoplayEnabled: Boolean = true,
    val detailPageTrailerAutoplayDelaySeconds: Int = 7,
    val preferExternalMetaAddonDetail: Boolean = false,
    val hideUnreleasedContent: Boolean = false,
    val showFullReleaseDate: Boolean = true,
    val nextUpFromFurthestEpisode: Boolean = true,
    val showUnairedNextUp: Boolean = true,
    val continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT
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
    data class SetModernHeroFullScreenBackdropEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetHeroSectionEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDiscoverLocation(val location: DiscoverLocation) : LayoutSettingsEvent()
    data class SetPosterLabelsEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetCatalogAddonNameEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetCatalogTypeSuffixEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetClassicFocusGradientEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropExpandEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropExpandDelaySeconds(val seconds: Int) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerMuted(val muted: Boolean) : LayoutSettingsEvent()
    data class SetFocusedPosterBackdropTrailerPlaybackTarget(
        val target: FocusedPosterTrailerPlaybackTarget
    ) : LayoutSettingsEvent()
    data class SetPosterCardWidth(val widthDp: Int) : LayoutSettingsEvent()
    data class SetPosterCardCornerRadius(val cornerRadiusDp: Int) : LayoutSettingsEvent()
    data class SetCardDepthEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetCardDepthEdgeStrength(val strength: Int) : LayoutSettingsEvent()
    data class SetCardDepthSheenStrength(val strength: Int) : LayoutSettingsEvent()
    data class SetCardDepthEdgeCoverage(val coverage: Int) : LayoutSettingsEvent()
    data class SetCardDepthSurfaceEnabled(
        val surface: CardDepthSurface,
        val enabled: Boolean
    ) : LayoutSettingsEvent()
    data class SetBlurUnwatchedEpisodes(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetBlurContinueWatchingNextUp(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetUseEpisodeThumbnailsInCw(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerButtonEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerAutoplayEnabled(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetDetailPageTrailerAutoplayDelaySeconds(val seconds: Int) : LayoutSettingsEvent()
    data class SetPreferExternalMetaAddonDetail(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetHideUnreleasedContent(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetShowFullReleaseDate(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetNextUpFromFurthestEpisode(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetShowUnairedNextUp(val enabled: Boolean) : LayoutSettingsEvent()
    data class SetContinueWatchingSortMode(val mode: ContinueWatchingSortMode) : LayoutSettingsEvent()
    data object ResetPosterCardStyle : LayoutSettingsEvent()
    data object ResetCardDepthStyle : LayoutSettingsEvent()
}

@HiltViewModel
class LayoutSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LayoutSettingsUiState())
    val uiState: StateFlow<LayoutSettingsUiState> = _uiState.asStateFlow()
    private var streamBadgeServer: StreamBadgeConfigServer? = null
    private var logoBytes: ByteArray? = null

    private val _streamBadgeUiState = MutableStateFlow(StreamBadgeSettingsUiState())
    val streamBadgeUiState: StateFlow<StreamBadgeSettingsUiState> = _streamBadgeUiState.asStateFlow()

    private inline fun updateUiStateIfChanged(
        update: (LayoutSettingsUiState) -> LayoutSettingsUiState
    ) {
        _uiState.update { current ->
            val next = update(current)
            if (next == current) current else next
        }
    }

    init {
        loadLogoBytes()
        viewModelScope.launch {
            streamBadgeSettingsDataStore.settings.collectLatest { settings ->
                _streamBadgeUiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.selectedLayout.distinctUntilChanged().collectLatest { layout ->
                updateUiStateIfChanged { it.copy(selectedLayout = layout) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hasChosenLayout.distinctUntilChanged().collectLatest { hasChosen ->
                updateUiStateIfChanged { it.copy(hasChosen = hasChosen) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.heroCatalogSelections.distinctUntilChanged().collectLatest { keys ->
                updateUiStateIfChanged { it.copy(heroCatalogKeys = keys) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.sidebarCollapsedByDefault.distinctUntilChanged().collectLatest { collapsed ->
                updateUiStateIfChanged { it.copy(sidebarCollapsedByDefault = collapsed) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernSidebarEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(modernSidebarEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernSidebarBlurEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(modernSidebarBlurEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernLandscapePostersEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(modernLandscapePostersEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(modernHeroFullScreenBackdropEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.heroSectionEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(heroSectionEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                updateUiStateIfChanged { it.copy(discoverLocation = location) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.lastNonOffDiscoverLocation.distinctUntilChanged().collectLatest { location ->
                updateUiStateIfChanged { it.copy(lastNonOffDiscoverLocation = location) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterLabelsEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(posterLabelsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogAddonNameEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(catalogAddonNameEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.classicFocusGradientEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(classicFocusGradientEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(focusedPosterBackdropExpandEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds.distinctUntilChanged().collectLatest { seconds ->
                updateUiStateIfChanged { it.copy(focusedPosterBackdropExpandDelaySeconds = seconds) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(focusedPosterBackdropTrailerEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted.distinctUntilChanged().collectLatest { muted ->
                updateUiStateIfChanged { it.copy(focusedPosterBackdropTrailerMuted = muted) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget.distinctUntilChanged().collectLatest { target ->
                updateUiStateIfChanged { it.copy(focusedPosterBackdropTrailerPlaybackTarget = target) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardWidthDp.distinctUntilChanged().collectLatest { widthDp ->
                updateUiStateIfChanged { it.copy(posterCardWidthDp = widthDp) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardHeightDp.distinctUntilChanged().collectLatest { heightDp ->
                updateUiStateIfChanged { it.copy(posterCardHeightDp = heightDp) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp.distinctUntilChanged().collectLatest { cornerRadiusDp ->
                updateUiStateIfChanged { it.copy(posterCardCornerRadiusDp = cornerRadiusDp) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.cardDepthStyle.distinctUntilChanged().collectLatest { style ->
                updateUiStateIfChanged { it.copy(cardDepthStyle = style) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.blurUnwatchedEpisodes.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(blurUnwatchedEpisodes = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.blurContinueWatchingNextUp.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(blurContinueWatchingNextUp = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(useEpisodeThumbnailsInCw = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.detailPageTrailerButtonEnabled.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(detailPageTrailerButtonEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            trailerSettingsDataStore.settings.distinctUntilChanged().collectLatest { settings ->
                updateUiStateIfChanged {
                    it.copy(
                        detailPageTrailerAutoplayEnabled = settings.enabled,
                        detailPageTrailerAutoplayDelaySeconds = settings.delaySeconds
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.preferExternalMetaAddonDetail.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(preferExternalMetaAddonDetail = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(hideUnreleasedContent = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.showFullReleaseDate.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(showFullReleaseDate = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.nextUpFromFurthestEpisode.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(nextUpFromFurthestEpisode = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.showUnairedNextUp.distinctUntilChanged().collectLatest { enabled ->
                updateUiStateIfChanged { it.copy(showUnairedNextUp = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingSortMode
                .distinctUntilChanged()
                .collect { mode ->
                    updateUiStateIfChanged { it.copy(continueWatchingSortMode = mode) }
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
            is LayoutSettingsEvent.SetModernHeroFullScreenBackdropEnabled -> setModernHeroFullScreenBackdropEnabled(event.enabled)
            is LayoutSettingsEvent.SetHeroSectionEnabled -> setHeroSectionEnabled(event.enabled)
            is LayoutSettingsEvent.SetDiscoverLocation -> setDiscoverLocation(event.location)
            is LayoutSettingsEvent.SetPosterLabelsEnabled -> setPosterLabelsEnabled(event.enabled)
            is LayoutSettingsEvent.SetCatalogAddonNameEnabled -> setCatalogAddonNameEnabled(event.enabled)
            is LayoutSettingsEvent.SetCatalogTypeSuffixEnabled -> setCatalogTypeSuffixEnabled(event.enabled)
            is LayoutSettingsEvent.SetClassicFocusGradientEnabled -> setClassicFocusGradientEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled -> setFocusedPosterBackdropExpandEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds -> setFocusedPosterBackdropExpandDelaySeconds(event.seconds)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled -> setFocusedPosterBackdropTrailerEnabled(event.enabled)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted -> setFocusedPosterBackdropTrailerMuted(event.muted)
            is LayoutSettingsEvent.SetFocusedPosterBackdropTrailerPlaybackTarget ->
                setFocusedPosterBackdropTrailerPlaybackTarget(event.target)
            is LayoutSettingsEvent.SetPosterCardWidth -> setPosterCardWidth(event.widthDp)
            is LayoutSettingsEvent.SetPosterCardCornerRadius -> setPosterCardCornerRadius(event.cornerRadiusDp)
            is LayoutSettingsEvent.SetCardDepthEnabled -> setCardDepthEnabled(event.enabled)
            is LayoutSettingsEvent.SetCardDepthEdgeStrength -> setCardDepthEdgeStrength(event.strength)
            is LayoutSettingsEvent.SetCardDepthSheenStrength -> setCardDepthSheenStrength(event.strength)
            is LayoutSettingsEvent.SetCardDepthEdgeCoverage -> setCardDepthEdgeCoverage(event.coverage)
            is LayoutSettingsEvent.SetCardDepthSurfaceEnabled ->
                setCardDepthSurfaceEnabled(event.surface, event.enabled)
            is LayoutSettingsEvent.SetBlurUnwatchedEpisodes -> setBlurUnwatchedEpisodes(event.enabled)
            is LayoutSettingsEvent.SetBlurContinueWatchingNextUp -> setBlurContinueWatchingNextUp(event.enabled)
            is LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw -> setUseEpisodeThumbnailsInCw(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled -> setDetailPageTrailerButtonEnabled(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled -> setDetailPageTrailerAutoplayEnabled(event.enabled)
            is LayoutSettingsEvent.SetDetailPageTrailerAutoplayDelaySeconds -> setDetailPageTrailerAutoplayDelaySeconds(event.seconds)
            is LayoutSettingsEvent.SetPreferExternalMetaAddonDetail -> setPreferExternalMetaAddonDetail(event.enabled)
            is LayoutSettingsEvent.SetHideUnreleasedContent -> setHideUnreleasedContent(event.enabled)
            is LayoutSettingsEvent.SetShowFullReleaseDate -> setShowFullReleaseDate(event.enabled)
            is LayoutSettingsEvent.SetNextUpFromFurthestEpisode -> setNextUpFromFurthestEpisode(event.enabled)
            is LayoutSettingsEvent.SetShowUnairedNextUp -> setShowUnairedNextUp(event.enabled)
            is LayoutSettingsEvent.SetContinueWatchingSortMode -> setContinueWatchingSortMode(event.mode)
            LayoutSettingsEvent.ResetPosterCardStyle -> resetPosterCardStyle()
            LayoutSettingsEvent.ResetCardDepthStyle -> resetCardDepthStyle()
        }
    }

    fun startStreamBadgeQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _streamBadgeUiState.update { it.copy(serverError = context.getString(R.string.error_network_required)) }
            return
        }
        stopStreamBadgeServer()
        streamBadgeServer = StreamBadgeConfigServer.startOnAvailablePort(
            currentSettingsProvider = { _streamBadgeUiState.value.settings },
            onSettingsChanged = { settings ->
                _streamBadgeUiState.update { it.copy(settings = settings) }
                viewModelScope.launch { streamBadgeSettingsDataStore.setSettings(settings) }
            },
            context = context,
            logoProvider = { logoBytes }
        )
        val server = streamBadgeServer
        if (server == null) {
            _streamBadgeUiState.update { it.copy(serverError = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }
        val url = "http://$ip:${server.listeningPort}"
        _streamBadgeUiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = QrCodeGenerator.generate(url, 512),
                serverUrl = url,
                serverError = null
            )
        }
    }

    fun stopStreamBadgeQrMode() {
        stopStreamBadgeServer()
        _streamBadgeUiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null
            )
        }
    }

    fun setShowFileSizeBadges(enabled: Boolean) {
        viewModelScope.launch { streamBadgeSettingsDataStore.setShowFileSizeBadges(enabled) }
    }

    fun setShowAddonLogo(enabled: Boolean) {
        viewModelScope.launch { streamBadgeSettingsDataStore.setShowAddonLogo(enabled) }
    }

    fun setStreamBadgePlacement(placement: StreamBadgePlacement) {
        viewModelScope.launch { streamBadgeSettingsDataStore.setStreamBadgePlacement(placement) }
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) {
        }
    }

    private fun stopStreamBadgeServer() {
        streamBadgeServer?.stop()
        streamBadgeServer = null
    }

    private fun selectLayout(layout: HomeLayout) {
        if (_uiState.value.selectedLayout == layout && _uiState.value.hasChosen) return
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
        if (_uiState.value.sidebarCollapsedByDefault == collapsed) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setSidebarCollapsedByDefault(collapsed)
        }
    }

    private fun setModernSidebarEnabled(enabled: Boolean) {
        if (_uiState.value.modernSidebarEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernSidebarEnabled(enabled)
        }
    }

    private fun setModernSidebarBlurEnabled(enabled: Boolean) {
        if (_uiState.value.modernSidebarBlurEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernSidebarBlurEnabled(enabled)
        }
    }

    private fun setModernLandscapePostersEnabled(enabled: Boolean) {
        if (_uiState.value.modernLandscapePostersEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernLandscapePostersEnabled(enabled)
        }
    }

    private fun setModernHeroFullScreenBackdropEnabled(enabled: Boolean) {
        if (_uiState.value.modernHeroFullScreenBackdropEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setModernHeroFullScreenBackdropEnabled(enabled)
        }
    }

    private fun setHeroSectionEnabled(enabled: Boolean) {
        if (_uiState.value.heroSectionEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setHeroSectionEnabled(enabled)
        }
    }

    private fun setDiscoverLocation(location: DiscoverLocation) {
        if (_uiState.value.discoverLocation == location) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setDiscoverLocation(location)
        }
    }

    private fun setPosterLabelsEnabled(enabled: Boolean) {
        if (_uiState.value.posterLabelsEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterLabelsEnabled(enabled)
        }
    }

    private fun setCatalogAddonNameEnabled(enabled: Boolean) {
        if (_uiState.value.catalogAddonNameEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCatalogAddonNameEnabled(enabled)
        }
    }

    private fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        if (_uiState.value.catalogTypeSuffixEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCatalogTypeSuffixEnabled(enabled)
        }
    }

    private fun setClassicFocusGradientEnabled(enabled: Boolean) {
        if (_uiState.value.classicFocusGradientEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setClassicFocusGradientEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropExpandEnabled(enabled: Boolean) {
        if (_uiState.value.focusedPosterBackdropExpandEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropExpandEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropExpandDelaySeconds(seconds: Int) {
        if (_uiState.value.focusedPosterBackdropExpandDelaySeconds == seconds) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropExpandDelaySeconds(seconds)
        }
    }

    private fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        if (_uiState.value.focusedPosterBackdropTrailerEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerEnabled(enabled)
        }
    }

    private fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        if (_uiState.value.focusedPosterBackdropTrailerMuted == muted) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerMuted(muted)
        }
    }

    private fun setFocusedPosterBackdropTrailerPlaybackTarget(target: FocusedPosterTrailerPlaybackTarget) {
        if (_uiState.value.focusedPosterBackdropTrailerPlaybackTarget == target) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerPlaybackTarget(target)
        }
    }

    private fun setPosterCardWidth(widthDp: Int) {
        if (_uiState.value.posterCardWidthDp == widthDp) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterCardWidthDp(widthDp)
            layoutPreferenceDataStore.setPosterCardHeightDp((widthDp * 3) / 2)
        }
    }

    private fun setPosterCardCornerRadius(cornerRadiusDp: Int) {
        if (_uiState.value.posterCardCornerRadiusDp == cornerRadiusDp) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterCardCornerRadiusDp(cornerRadiusDp)
        }
    }

    private fun setCardDepthEnabled(enabled: Boolean) {
        if (_uiState.value.cardDepthStyle.enabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCardDepthEnabled(enabled)
        }
    }

    private fun setCardDepthEdgeStrength(strength: Int) {
        if (_uiState.value.cardDepthStyle.edgeStrength == strength) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCardDepthEdgeStrength(strength)
        }
    }

    private fun setCardDepthSheenStrength(strength: Int) {
        if (_uiState.value.cardDepthStyle.sheenStrength == strength) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCardDepthSheenStrength(strength)
        }
    }

    private fun setCardDepthEdgeCoverage(coverage: Int) {
        if (_uiState.value.cardDepthStyle.edgeCoverage == coverage) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCardDepthEdgeCoverage(coverage)
        }
    }

    private fun setCardDepthSurfaceEnabled(surface: CardDepthSurface, enabled: Boolean) {
        if (_uiState.value.cardDepthStyle.isSurfaceEnabled(surface) == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setCardDepthSurfaceEnabled(surface, enabled)
        }
    }

    private fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        if (_uiState.value.detailPageTrailerButtonEnabled == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setDetailPageTrailerButtonEnabled(enabled)
        }
    }

    private fun setDetailPageTrailerAutoplayEnabled(enabled: Boolean) {
        if (_uiState.value.detailPageTrailerAutoplayEnabled == enabled) return
        viewModelScope.launch {
            trailerSettingsDataStore.setEnabled(enabled)
        }
    }

    private fun setDetailPageTrailerAutoplayDelaySeconds(seconds: Int) {
        if (_uiState.value.detailPageTrailerAutoplayDelaySeconds == seconds) return
        viewModelScope.launch {
            trailerSettingsDataStore.setDelaySeconds(seconds)
        }
    }

    private fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        if (_uiState.value.blurUnwatchedEpisodes == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setBlurUnwatchedEpisodes(enabled)
        }
    }

    private fun setBlurContinueWatchingNextUp(enabled: Boolean) {
        if (_uiState.value.blurContinueWatchingNextUp == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setBlurContinueWatchingNextUp(enabled)
        }
    }

    private fun setUseEpisodeThumbnailsInCw(enabled: Boolean) {
        if (_uiState.value.useEpisodeThumbnailsInCw == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setUseEpisodeThumbnailsInCw(enabled)
        }
    }

    private fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        if (_uiState.value.preferExternalMetaAddonDetail == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setPreferExternalMetaAddonDetail(enabled)
            metaRepository.clearCache()
        }
    }

    private fun setHideUnreleasedContent(enabled: Boolean) {
        if (_uiState.value.hideUnreleasedContent == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setHideUnreleasedContent(enabled)
        }
    }

    private fun setShowFullReleaseDate(enabled: Boolean) {
        if (_uiState.value.showFullReleaseDate == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setShowFullReleaseDate(enabled)
        }
    }

    private fun setNextUpFromFurthestEpisode(enabled: Boolean) {
        if (_uiState.value.nextUpFromFurthestEpisode == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setNextUpFromFurthestEpisode(enabled)
        }
    }

    private fun setShowUnairedNextUp(enabled: Boolean) {
        if (_uiState.value.showUnairedNextUp == enabled) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setShowUnairedNextUp(enabled)
        }
    }

    private fun setContinueWatchingSortMode(mode: ContinueWatchingSortMode) {
        if (_uiState.value.continueWatchingSortMode == mode) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setContinueWatchingSortMode(mode)
        }
    }

    private fun resetPosterCardStyle() {
        if (
            _uiState.value.posterCardWidthDp == 126 &&
            _uiState.value.posterCardHeightDp == 189 &&
            _uiState.value.posterCardCornerRadiusDp == 12
        ) {
            return
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setPosterCardWidthDp(126)
            layoutPreferenceDataStore.setPosterCardHeightDp(189)
            layoutPreferenceDataStore.setPosterCardCornerRadiusDp(12)
        }
    }

    private fun resetCardDepthStyle() {
        if (_uiState.value.cardDepthStyle == CardDepthStyle()) return
        viewModelScope.launch {
            layoutPreferenceDataStore.resetCardDepthStyle()
        }
    }

    private fun loadAvailableCatalogs() {
        viewModelScope.launch {
            addonRepository.getInstalledAddons().collectLatest { installedAddons ->
                val addons = installedAddons.enabledAddons()
                val catalogs = addons.flatMap { addon ->
                    addon.catalogs
                        .filter { catalog ->
                            !catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
                        }
                        .map { catalog ->
                            CatalogInfo(
                                key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                                name = catalog.name,
                                addonName = addon.displayName
                            )
                        }
                }
                updateUiStateIfChanged { it.copy(availableCatalogs = catalogs) }
            }
        }
    }

    override fun onCleared() {
        stopStreamBadgeServer()
        super.onCleared()
    }
}

data class StreamBadgeSettingsUiState(
    val settings: StreamBadgeSettings = StreamBadgeSettings(),
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val serverError: String? = null
) {
    val rules: StreamBadgeRules
        get() = settings.rules

    val showFileSizeBadges: Boolean
        get() = settings.showFileSizeBadges

    val showAddonLogo: Boolean
        get() = settings.showAddonLogo

    val badgePlacement: StreamBadgePlacement
        get() = settings.badgePlacement
}

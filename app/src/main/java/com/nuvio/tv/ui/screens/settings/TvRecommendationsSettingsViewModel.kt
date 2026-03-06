package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nuvio.tv.core.recommendations.RecommendationDataStore
import com.nuvio.tv.data.worker.TvRecommendationWorker
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvRecommendationsSettingsUiState(
    val availableCatalogs: List<CatalogInfo> = emptyList(),
    val enabledCatalogs: Set<String> = emptySet(),
    val syncIntervalHours: Int = 3,
    val maxItemsPerChannel: Int = 25,
    val useWidePoster: Boolean = false
)

@HiltViewModel
class TvRecommendationsSettingsViewModel @Inject constructor(
    private val dataStore: RecommendationDataStore,
    private val addonRepository: AddonRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvRecommendationsSettingsUiState())
    val uiState: StateFlow<TvRecommendationsSettingsUiState> = _uiState.asStateFlow()

    val isRecommendationsEnabled: StateFlow<Boolean> = dataStore.isEnabledFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val isPlayNextEnabled: StateFlow<Boolean> = dataStore.playNextEnabledFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    init {
        // Load Addon Catalogs
        viewModelScope.launch {
            addonRepository.getInstalledAddons().collectLatest { addons ->
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
                _uiState.update { it.copy(availableCatalogs = catalogs) }
            }
        }
        
        // Load Enabled Catalogs Selection
        viewModelScope.launch {
            dataStore.enabledCatalogsFlow.distinctUntilChanged().collectLatest { enabledSettings ->
                _uiState.update { it.copy(enabledCatalogs = enabledSettings) }
            }
        }
        
        // Load Configuration Settings
        viewModelScope.launch {
            dataStore.syncIntervalHoursFlow.distinctUntilChanged().collectLatest { interval ->
                _uiState.update { it.copy(syncIntervalHours = interval) }
            }
        }
        viewModelScope.launch {
            dataStore.maxItemsPerChannelFlow.distinctUntilChanged().collectLatest { maxItems ->
                _uiState.update { it.copy(maxItemsPerChannel = maxItems) }
            }
        }
        viewModelScope.launch {
            dataStore.useWidePosterFlow.distinctUntilChanged().collectLatest { useWide ->
                _uiState.update { it.copy(useWidePoster = useWide) }
            }
        }
    }

    fun setRecommendationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setEnabled(enabled)
            triggerImmediateSync()
        }
    }

    fun setPlayNextEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setPlayNextEnabled(enabled)
            triggerImmediateSync()
        }
    }

    fun toggleCatalog(catalogKey: String) {
        viewModelScope.launch {
            val current = _uiState.value.enabledCatalogs.toMutableSet()
            if (current.contains(catalogKey)) {
                current.remove(catalogKey)
            } else {
                current.add(catalogKey)
            }
            dataStore.setEnabledCatalogs(current)
            triggerImmediateSync()
        }
    }

    fun setSyncIntervalHours(hours: Int) {
        viewModelScope.launch { 
            dataStore.setSyncIntervalHours(hours) 
            // Interval changes are handled globally by App, no forced sync needed
        }
    }

    fun setMaxItemsPerChannel(max: Int) {
        viewModelScope.launch { 
            dataStore.setMaxItemsPerChannel(max) 
            triggerImmediateSync()
        }
    }

    fun setUseWidePoster(useWide: Boolean) {
        viewModelScope.launch { 
            dataStore.setUseWidePoster(useWide) 
            triggerImmediateSync()
        }
    }
    
    private fun triggerImmediateSync() {
        val workRequest = OneTimeWorkRequestBuilder<TvRecommendationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            "manual_tv_sync",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}

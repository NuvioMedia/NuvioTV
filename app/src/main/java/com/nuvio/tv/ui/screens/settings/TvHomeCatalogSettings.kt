@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelManager
import com.nuvio.tv.core.sync.androidtv.TvChannelPreferences
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class TvHomeCatalogOption(
    val key: String,
    val name: String,
    val addonName: String
)

@Immutable
data class TvHomeCatalogSettingsUiState(
    val catalogs: List<TvHomeCatalogOption> = emptyList(),
    val enabledKeys: Set<String> = emptySet()
)

@HiltViewModel
class TvHomeCatalogSettingsViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val tvChannelPreferences: TvChannelPreferences,
    private val androidTvChannelManager: AndroidTvChannelManager
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(TvHomeCatalogSettingsUiState())

    val uiState: StateFlow<TvHomeCatalogSettingsUiState> =
        _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                tvChannelPreferences.enabledCatalogKeys
            ) { installedAddons, enabledKeys ->
                val catalogs = installedAddons
                    .enabledAddons()
                    .flatMap { addon ->
                        addon.catalogs
                            .filterNot { catalog ->
                                catalog.extra.any {
                                    it.name.equals(
                                        "search",
                                        ignoreCase = true
                                    ) && it.isRequired
                                } || catalog.extraRequired.any {
                                    it.equals(
                                        "search",
                                        ignoreCase = true
                                    )
                                }
                            }
                            .map { catalog ->
                                TvHomeCatalogOption(
                                    key = catalogRowStableKey(
                                        addonId = addon.id,
                                        addonBaseUrl = addon.baseUrl,
                                        type = catalog.apiType,
                                        catalogId = catalog.id
                                    ),
                                    name = catalog.name
                                        .trim()
                                        .ifBlank { catalog.id },
                                    addonName = addon.displayName
                                        .trim()
                                        .ifBlank { addon.name }
                                )
                            }
                    }
                    .distinctBy { it.key }

                TvHomeCatalogSettingsUiState(
                    catalogs = catalogs,
                    enabledKeys = enabledKeys
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleCatalog(option: TvHomeCatalogOption) {
        viewModelScope.launch {
            val currentlyEnabled =
                option.key in _uiState.value.enabledKeys

            tvChannelPreferences.setCatalogEnabled(
                catalogKey = option.key,
                enabled = !currentlyEnabled
            )

            if (currentlyEnabled) {
                androidTvChannelManager.removeCatalogChannel(
                    option.key
                )
            }
        }
    }
}

@Composable
fun TvHomeCatalogSettingsSection(
    viewModel: TvHomeCatalogSettingsViewModel = hiltViewModel()
) {
    val uiState =
        viewModel.uiState.collectAsStateWithLifecycle().value

    if (uiState.catalogs.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Android TV Home")
        Text(
            "Vyber katalógy addonov, ktoré sa majú zobraziť ako samostatné riadky na domovskej obrazovke TV."
        )

        LazyRow(
            contentPadding = PaddingValues(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = uiState.catalogs,
                key = { it.key }
            ) { catalog ->
                val selected =
                    catalog.key in uiState.enabledKeys

                Button(
                    onClick = {
                        viewModel.toggleCatalog(catalog)
                    }
                ) {
                    Text(
                        buildString {
                            if (selected) append("✓ ")
                            append(catalog.name)
                            append(" · ")
                            append(catalog.addonName)
                        }
                    )
                }
            }
        }
    }
}

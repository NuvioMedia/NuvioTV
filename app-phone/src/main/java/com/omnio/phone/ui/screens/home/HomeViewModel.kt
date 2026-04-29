package com.omnio.phone.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.Addon
import com.omnio.tv.domain.model.CatalogDescriptor
import com.omnio.tv.domain.model.CatalogRow
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.CatalogRepository
import com.omnio.tv.domain.result.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MAX_HERO_ITEMS = 7

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val rowsByKey = LinkedHashMap<String, CatalogRow>()
    private var loadJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .distinctUntilChanged()
                .collectLatest { addons -> reload(addons, refresh = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val addons = addonRepository.getInstalledAddons().first()
            reload(addons, refresh = true)
        }
    }

    private suspend fun reload(addons: List<Addon>, refresh: Boolean) {
        loadJob?.cancelAndJoin()
        generation += 1
        val gen = generation
        rowsByKey.clear()

        _uiState.update {
            it.copy(
                isLoading = !refresh,
                isRefreshing = refresh,
                error = null,
                installedAddonsCount = addons.size,
                rows = emptyList(),
                heroItems = emptyList()
            )
        }

        if (addons.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            return
        }

        val pairs = addons.flatMap { addon ->
            addon.catalogs
                .filter { it.shouldShowOnHome() }
                .map { addon to it }
        }
        if (pairs.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            return
        }

        loadJob = viewModelScope.launch {
            pairs.forEach { (addon, catalog) ->
                launch { fetchCatalog(addon, catalog, gen) }
            }
        }
        loadJob?.invokeOnCompletion {
            if (gen == generation) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    private suspend fun fetchCatalog(addon: Addon, catalog: CatalogDescriptor, gen: Long) {
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType
        ).collect { result ->
            if (gen != generation) return@collect
            when (result) {
                is NetworkResult.Success -> {
                    val key = "${addon.id}|${catalog.apiType}|${catalog.id}"
                    rowsByKey[key] = result.data
                    publish()
                }
                is NetworkResult.Error,
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun publish() {
        val orderedRows = rowsByKey.values.filter { it.items.isNotEmpty() }.toList()
        val hero = orderedRows.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { !it.background.isNullOrBlank() }
            .distinctBy { it.id }
            .take(MAX_HERO_ITEMS)
            .toList()
        _uiState.update { it.copy(rows = orderedRows, heroItems = hero) }
    }
}

private fun CatalogDescriptor.shouldShowOnHome(): Boolean {
    val isSearchOnly = extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
    if (isSearchOnly) return false
    return !hasExplicitShowInHome || showInHome
}

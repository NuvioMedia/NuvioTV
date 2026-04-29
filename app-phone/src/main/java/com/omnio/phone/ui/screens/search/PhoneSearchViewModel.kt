package com.omnio.phone.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.Addon
import com.omnio.tv.domain.model.CatalogDescriptor
import com.omnio.tv.domain.model.MetaPreview
import com.omnio.tv.domain.model.skipStep
import com.omnio.tv.domain.model.supportsExtra
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.CatalogRepository
import com.omnio.tv.domain.result.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhoneSearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneSearchUiState())
    val uiState: StateFlow<PhoneSearchUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    private var searchJob: Job? = null
    private val resultsByKey = LinkedHashMap<String, MetaPreview>()

    fun onQueryChanged(value: String) {
        _uiState.update { it.copy(query = value, error = null) }
        scheduleSearch(value, debounceMs = DEBOUNCE_MS)
    }

    fun submitNow() {
        scheduleSearch(_uiState.value.query, debounceMs = 0L)
    }

    fun retry() {
        scheduleSearch(_uiState.value.submittedQuery.ifBlank { _uiState.value.query }, debounceMs = 0L)
    }

    private fun scheduleSearch(rawQuery: String, debounceMs: Long) {
        debounceJob?.cancel()
        searchJob?.cancel()

        val trimmed = rawQuery.trim()
        if (trimmed.length < MIN_QUERY_LEN) {
            resultsByKey.clear()
            _uiState.update {
                it.copy(
                    submittedQuery = "",
                    isSearching = false,
                    results = emptyList(),
                    error = null
                )
            }
            return
        }

        debounceJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            runSearch(trimmed)
        }
    }

    private fun runSearch(query: String) {
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    submittedQuery = query,
                    isSearching = true,
                    error = null,
                    results = emptyList()
                )
            }
            resultsByKey.clear()

            val addons = try {
                addonRepository.getInstalledAddons().first()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSearching = false, error = e.message ?: "Failed to load addons")
                }
                return@launch
            }

            val targets = buildSearchTargets(addons)
            if (targets.isEmpty()) {
                _uiState.update {
                    it.copy(isSearching = false, hasSearchableAddons = false)
                }
                return@launch
            }
            _uiState.update { it.copy(hasSearchableAddons = true) }

            val jobs = targets.map { (addon, catalog) ->
                launch { fetchCatalog(addon, catalog, query) }
            }
            jobs.forEach { it.join() }

            if (_uiState.value.submittedQuery == query) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    private suspend fun fetchCatalog(addon: Addon, catalog: CatalogDescriptor, query: String) {
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = catalog.skipStep(),
            extraArgs = mapOf("search" to query),
            supportsSkip = catalog.supportsExtra("skip")
        ).collect { result ->
            if (_uiState.value.submittedQuery != query) return@collect
            when (result) {
                is NetworkResult.Success -> {
                    var added = false
                    result.data.items.forEach { item ->
                        val key = "${item.apiType}:${item.id}"
                        if (key !in resultsByKey) {
                            resultsByKey[key] = item
                            added = true
                        }
                    }
                    if (added) publishResults()
                }
                is NetworkResult.Error -> {
                    if (resultsByKey.isEmpty() && _uiState.value.error == null) {
                        _uiState.update { it.copy(error = result.message) }
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun publishResults() {
        val snapshot = resultsByKey.values.toList()
        _uiState.update { it.copy(results = snapshot) }
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> =
        addons.flatMap { addon ->
            addon.catalogs
                .filter { it.supportsExtra("search") }
                .map { addon to it }
        }

    private companion object {
        const val DEBOUNCE_MS = 350L
        const val MIN_QUERY_LEN = 2
    }
}

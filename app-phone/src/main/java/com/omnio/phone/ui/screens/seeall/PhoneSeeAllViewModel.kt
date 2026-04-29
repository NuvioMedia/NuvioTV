package com.omnio.phone.ui.screens.seeall

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.repository.CatalogRepository
import com.omnio.tv.domain.result.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhoneSeeAllViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val addonBaseUrl: String =
        savedStateHandle.get<String>(PhoneSeeAllRoute.KEY_ADDON_BASE_URL).orEmpty()
    private val addonId: String =
        savedStateHandle.get<String>(PhoneSeeAllRoute.KEY_ADDON_ID).orEmpty()
    private val addonName: String =
        savedStateHandle.get<String>(PhoneSeeAllRoute.KEY_ADDON_NAME).orEmpty()
    private val type: String =
        savedStateHandle.get<String>(PhoneSeeAllRoute.KEY_TYPE).orEmpty()
    private val catalogId: String =
        savedStateHandle.get<String>(PhoneSeeAllRoute.KEY_CATALOG_ID).orEmpty()
    private val catalogName: String =
        savedStateHandle.get<String>(PhoneSeeAllRoute.KEY_CATALOG_NAME).orEmpty()

    private val _uiState = MutableStateFlow(
        PhoneSeeAllUiState(catalogName = catalogName, addonName = addonName)
    )
    val uiState: StateFlow<PhoneSeeAllUiState> = _uiState.asStateFlow()

    private val seenItemIds = LinkedHashSet<String>()
    private var nextSkip = 0
    private var loadJob: Job? = null

    init {
        loadNext()
    }

    fun loadMore() {
        val current = _uiState.value
        if (!current.hasMore || current.isLoading || current.isLoadingMore) return
        loadNext()
    }

    private fun loadNext() {
        loadJob?.cancel()
        val isFirst = _uiState.value.items.isEmpty()
        _uiState.update {
            it.copy(
                isLoading = isFirst,
                isLoadingMore = !isFirst,
                error = null
            )
        }
        loadJob = viewModelScope.launch {
            catalogRepository.getCatalog(
                addonBaseUrl = addonBaseUrl,
                addonId = addonId,
                addonName = addonName,
                catalogId = catalogId,
                catalogName = catalogName,
                type = type,
                skip = nextSkip,
                supportsSkip = true
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val row = result.data
                        val previousIds = seenItemIds.toSet()
                        val mergedNew = row.items.filter { item ->
                            seenItemIds.add(item.id)
                        }
                        val pageWasEmpty = row.items.isEmpty() ||
                            (mergedNew.isEmpty() && previousIds.isNotEmpty())
                        val combined = if (nextSkip == 0) {
                            row.items.distinctBy { it.id }
                        } else {
                            _uiState.value.items + mergedNew
                        }
                        val effectiveStep = row.skipStep.takeIf { it > 0 } ?: 100
                        val moreAvailable = row.hasMore && !pageWasEmpty
                        nextSkip = if (moreAvailable) nextSkip + effectiveStep else nextSkip
                        _uiState.update {
                            it.copy(
                                items = combined,
                                isLoading = false,
                                isLoadingMore = false,
                                hasMore = moreAvailable,
                                supportsSkip = row.supportsSkip,
                                skipStep = effectiveStep,
                                error = null
                            )
                        }
                    }
                    is NetworkResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                error = result.message
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }
}

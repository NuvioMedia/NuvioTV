package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.GitHubContributor
import com.nuvio.tv.data.repository.GitHubContributorsRepository
import com.nuvio.tv.data.repository.SupporterDonation
import com.nuvio.tv.data.repository.SupportersRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SupportersContributorsTab {
    Supporters,
    Contributors
}

data class SupportersContributorsUiState(
    val selectedTab: SupportersContributorsTab = SupportersContributorsTab.Contributors,
    val isSupportersLoading: Boolean = false,
    val hasLoadedSupporters: Boolean = false,
    val supporters: List<SupporterDonation> = emptyList(),
    val supportersErrorMessage: String? = null,
    val selectedSupporter: SupporterDonation? = null,
    val isContributorsLoading: Boolean = false,
    val hasLoadedContributors: Boolean = false,
    val contributors: List<GitHubContributor> = emptyList(),
    val contributorsErrorMessage: String? = null,
    val selectedContributor: GitHubContributor? = null
)

@HiltViewModel
class SupportersContributorsViewModel @Inject constructor(
    private val supportersRepository: SupportersRepository,
    private val contributorsRepository: GitHubContributorsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupportersContributorsUiState())
    val uiState: StateFlow<SupportersContributorsUiState> = _uiState.asStateFlow()

    init {
        loadContributorsIfNeeded()
    }

    fun onSelectTab(tab: SupportersContributorsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        when (tab) {
            SupportersContributorsTab.Supporters -> loadSupportersIfNeeded()
            SupportersContributorsTab.Contributors -> loadContributorsIfNeeded()
        }
    }

    fun retrySupporters() {
        loadSupporters(force = true)
    }

    fun retryContributors() {
        loadContributors(force = true)
    }

    fun onSupporterSelected(supporter: SupporterDonation) {
        _uiState.update { it.copy(selectedSupporter = supporter) }
    }

    fun dismissSupporterDetails() {
        _uiState.update { it.copy(selectedSupporter = null) }
    }

    fun onContributorSelected(contributor: GitHubContributor) {
        _uiState.update { it.copy(selectedContributor = contributor) }
    }

    fun dismissContributorDetails() {
        _uiState.update { it.copy(selectedContributor = null) }
    }

    private fun loadSupportersIfNeeded() {
        val current = _uiState.value
        if (current.hasLoadedSupporters || current.isSupportersLoading) return
        loadSupporters(force = false)
    }

    private fun loadContributorsIfNeeded() {
        val current = _uiState.value
        if (current.hasLoadedContributors || current.isContributorsLoading) return
        loadContributors(force = false)
    }

    private fun loadSupporters(force: Boolean) {
        val current = _uiState.value
        if (current.isSupportersLoading) return
        if (!force && current.hasLoadedSupporters) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSupportersLoading = true,
                    supportersErrorMessage = null
                )
            }

            supportersRepository.getSupporters()
                .onSuccess { supporters ->
                    _uiState.update {
                        it.copy(
                            isSupportersLoading = false,
                            hasLoadedSupporters = true,
                            supporters = supporters,
                            supportersErrorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSupportersLoading = false,
                            hasLoadedSupporters = false,
                            supporters = emptyList(),
                            supportersErrorMessage = error.message ?: "Unable to load supporters."
                        )
                    }
                }
        }
    }

    private fun loadContributors(force: Boolean) {
        val current = _uiState.value
        if (current.isContributorsLoading) return
        if (!force && current.hasLoadedContributors) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isContributorsLoading = true,
                    contributorsErrorMessage = null
                )
            }

            contributorsRepository.getContributors()
                .onSuccess { contributors ->
                    _uiState.update {
                        it.copy(
                            isContributorsLoading = false,
                            hasLoadedContributors = true,
                            contributors = contributors,
                            contributorsErrorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isContributorsLoading = false,
                            hasLoadedContributors = false,
                            contributors = emptyList(),
                            contributorsErrorMessage = error.message ?: "Unable to load contributors."
                        )
                    }
                }
        }
    }
}

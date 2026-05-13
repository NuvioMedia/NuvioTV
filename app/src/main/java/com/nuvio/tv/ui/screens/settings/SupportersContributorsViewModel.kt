package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.GitHubContributor
import com.nuvio.tv.data.repository.GitHubContributorsRepository
import com.nuvio.tv.data.repository.DevelopmentSponsor
import com.nuvio.tv.data.repository.SponsorsRepository
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
    Sponsors,
    Contributors
}

data class SupportersContributorsUiState(
    val selectedTab: SupportersContributorsTab = SupportersContributorsTab.Contributors,
    val isSupportersLoading: Boolean = false,
    val hasLoadedSupporters: Boolean = false,
    val supporters: List<SupporterDonation> = emptyList(),
    val supportersErrorMessage: String? = null,
    val selectedSupporter: SupporterDonation? = null,
    val isSponsorsLoading: Boolean = false,
    val hasLoadedSponsors: Boolean = false,
    val sponsors: List<DevelopmentSponsor> = emptyList(),
    val sponsorsErrorMessage: String? = null,
    val selectedSponsor: DevelopmentSponsor? = null,
    val isContributorsLoading: Boolean = false,
    val hasLoadedContributors: Boolean = false,
    val contributors: List<GitHubContributor> = emptyList(),
    val contributorsErrorMessage: String? = null,
    val selectedContributor: GitHubContributor? = null
)

@HiltViewModel
class SupportersContributorsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val supportersRepository: SupportersRepository,
    private val sponsorsRepository: SponsorsRepository,
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
            SupportersContributorsTab.Sponsors -> loadSponsorsIfNeeded()
            SupportersContributorsTab.Contributors -> loadContributorsIfNeeded()
        }
    }

    fun retrySupporters() {
        loadSupporters(force = true)
    }

    fun retryContributors() {
        loadContributors(force = true)
    }

    fun retrySponsors() {
        loadSponsors(force = true)
    }

    fun onSupporterSelected(supporter: SupporterDonation) {
        _uiState.update { it.copy(selectedSupporter = supporter) }
    }

    fun dismissSupporterDetails() {
        _uiState.update { it.copy(selectedSupporter = null) }
    }

    fun onSponsorSelected(sponsor: DevelopmentSponsor) {
        _uiState.update { it.copy(selectedSponsor = sponsor) }
    }

    fun dismissSponsorDetails() {
        _uiState.update { it.copy(selectedSponsor = null) }
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

    private fun loadSponsorsIfNeeded() {
        val current = _uiState.value
        if (current.hasLoadedSponsors || current.isSponsorsLoading) return
        loadSponsors(force = false)
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
                            supportersErrorMessage = error.message ?: appContext.getString(com.nuvio.tv.R.string.supporters_error_load)
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
                            contributorsErrorMessage = error.message ?: appContext.getString(com.nuvio.tv.R.string.contributors_error_load)
                        )
                    }
                }
        }
    }

    private fun loadSponsors(force: Boolean) {
        val current = _uiState.value
        if (current.isSponsorsLoading) return
        if (!force && current.hasLoadedSponsors) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSponsorsLoading = true,
                    sponsorsErrorMessage = null
                )
            }

            sponsorsRepository.getSponsors()
                .onSuccess { sponsors ->
                    _uiState.update {
                        it.copy(
                            isSponsorsLoading = false,
                            hasLoadedSponsors = true,
                            sponsors = sponsors,
                            sponsorsErrorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSponsorsLoading = false,
                            hasLoadedSponsors = false,
                            sponsors = emptyList(),
                            sponsorsErrorMessage = error.message ?: appContext.getString(com.nuvio.tv.R.string.sponsors_error_load)
                        )
                    }
                }
        }
    }
}

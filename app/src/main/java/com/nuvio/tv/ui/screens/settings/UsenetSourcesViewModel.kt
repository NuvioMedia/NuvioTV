package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.usenet.NewznabClient
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsenetSourcesUiState(
    val profileId: Int = 0,
    val configuration: UsenetSourceConfiguration = UsenetSourceConfiguration(),
    val loaded: Boolean = false,
    val error: Boolean = false
)

@HiltViewModel
class UsenetSourcesViewModel @Inject constructor(
    private val settings: UsenetSourceSettings,
    private val profiles: ProfileManager,
    private val client: NewznabClient
) : ViewModel() {
    private val state = MutableStateFlow(UsenetSourcesUiState())
    val uiState = state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.snapshots.flowOn(Dispatchers.IO).collectLatest { (profileId, result) ->
                state.value = result.fold(
                    onSuccess = { UsenetSourcesUiState(profileId, it, loaded = true) },
                    onFailure = { UsenetSourcesUiState(profileId, error = true) }
                )
            }
        }
    }

    fun update(configuration: UsenetSourceConfiguration, profileId: Int) {
        if (!state.value.loaded || profiles.activeProfileId.value != profileId) return
        // Synchronous so that quick successive edits never build on a stale configuration.
        val normalized = configuration.normalized()
        try {
            settings.update(normalized, profileId)
            state.value = UsenetSourcesUiState(profileId, normalized, loaded = true)
        } catch (_: Exception) { state.value = state.value.copy(error = true) }
    }

    suspend fun test(indexer: UsenetIndexer): Boolean = withContext(Dispatchers.IO) {
        try { indexer.validate(); client.capabilities(indexer); true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }
}

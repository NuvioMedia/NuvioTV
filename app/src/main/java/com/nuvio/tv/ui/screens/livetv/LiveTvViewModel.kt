package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.livetv.LiveTvSourceImporter
import com.nuvio.tv.data.local.LiveTvSourceStore
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvProgram
import com.nuvio.tv.domain.model.LiveTvSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTvUiState(
    val sources: List<LiveTvSource> = emptyList(),
    val channels: List<LiveTvChannel> = emptyList(),
    val programs: List<LiveTvProgram> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sourceErrors: Map<String, String> = emptyMap(),
    val lastSavedSourceId: String? = null,
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val sourceStore: LiveTvSourceStore,
    private val importer: LiveTvSourceImporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LiveTvUiState(isLoading = true))
    val state: StateFlow<LiveTvUiState> = mutableState.asStateFlow()

    init { reloadSources() }

    fun reloadSources() {
        viewModelScope.launch {
            runCatching { sourceStore.list() }
                .onSuccess { sources -> mutableState.update { it.copy(sources = sources, isLoading = false) } }
                .onFailure { error -> mutableState.update { it.copy(isLoading = false, error = error.message ?: "Falha ao carregar fontes") } }
        }
    }

    fun addSource(name: String, playlistUrl: String, epgUrl: String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            runCatching { sourceStore.add(name, playlistUrl, epgUrl) }
                .onSuccess { added ->
                    mutableState.update { it.copy(sources = it.sources + added, isLoading = false, lastSavedSourceId = added.id) }
                    refresh()
                }
                .onFailure { error -> mutableState.update { it.copy(isLoading = false, error = error.message ?: "Não foi possível salvar a fonte") } }
        }
    }

    fun setSourceEnabled(source: LiveTvSource, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { sourceStore.setEnabled(source.id, enabled) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(
                            sources = state.sources.map { if (it.id == source.id) it.copy(enabled = enabled) else it },
                            channels = if (enabled) state.channels else state.channels.filterNot { it.sourceId == source.id },
                            programs = if (enabled) state.programs else state.programs.filterNot { it.sourceId == source.id },
                        )
                    }
                    if (enabled) refresh()
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "Não foi possível atualizar a fonte") } }
        }
    }

    fun removeSource(source: LiveTvSource) {
        viewModelScope.launch {
            runCatching { sourceStore.remove(source.id) }
                .onSuccess {
                    mutableState.update { state -> state.copy(sources = state.sources.filterNot { it.id == source.id },
                        channels = state.channels.filterNot { it.sourceId == source.id },
                        programs = state.programs.filterNot { it.sourceId == source.id },
                        sourceErrors = state.sourceErrors - source.id) }
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "Não foi possível remover a fonte") } }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val sources = mutableState.value.sources.filter { it.enabled }
            if (sources.isEmpty()) {
                mutableState.update { it.copy(channels = emptyList(), programs = emptyList(), sourceErrors = emptyMap(), error = null) }
                return@launch
            }
            mutableState.update { it.copy(isLoading = true, error = null, sourceErrors = emptyMap()) }
            val channels = mutableListOf<LiveTvChannel>()
            val programs = mutableListOf<LiveTvProgram>()
            val errors = linkedMapOf<String, String>()
            for (source in sources) {
                try {
                    val result = importer.import(source)
                    channels += result.channels
                    programs += result.programs
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    errors[source.id] = failure.message ?: "Falha ao importar a fonte"
                }
            }
            mutableState.update { it.copy(
                channels = channels.distinctBy(LiveTvChannel::id),
                programs = programs.distinctBy { "${it.sourceId}|${it.channelId}|${it.startEpochMillis}|${it.title}" },
                sourceErrors = errors,
                isLoading = false,
                error = if (channels.isEmpty() && errors.isNotEmpty()) "Não foi possível importar canais das fontes ativas" else null,
            ) }
        }
    }
}

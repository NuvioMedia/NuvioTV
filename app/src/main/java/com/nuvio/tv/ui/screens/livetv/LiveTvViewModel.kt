package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.livetv.LiveTvSourceImporter
import com.nuvio.tv.core.livetv.LiveTvGuideCache
import com.nuvio.tv.data.local.LiveTvSourceStore
import com.nuvio.tv.data.local.LiveTvChannelStateStore
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvProgram
import com.nuvio.tv.domain.model.LiveTvSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val lastUpdatedEpochMillis: Map<String, Long> = emptyMap(),
    val lastSavedSourceId: String? = null,
    val favoriteChannelIds: Set<String> = emptySet(),
    val recentChannelIds: List<String> = emptyList(),
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val sourceStore: LiveTvSourceStore,
    private val importer: LiveTvSourceImporter,
    private val guideCache: LiveTvGuideCache,
    private val channelStateStore: LiveTvChannelStateStore,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LiveTvUiState(isLoading = true))
    val state: StateFlow<LiveTvUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            profileManager.activeProfileId.distinctUntilChanged().collect { _ ->
                mutableState.update { it.copy(
                    sources = emptyList(), channels = emptyList(), programs = emptyList(),
                    favoriteChannelIds = emptySet(), recentChannelIds = emptyList(),
                    sourceErrors = emptyMap(), lastUpdatedEpochMillis = emptyMap(),
                    isLoading = true, error = null,
                ) }
                reloadSources()
                try {
                    val saved = channelStateStore.read()
                    mutableState.update { it.copy(
                        favoriteChannelIds = saved.favoriteChannelIds.toSet(),
                        recentChannelIds = saved.recentChannelIds,
                    ) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the screen usable if optional local channel preferences are unreadable.
                }
            }
        }
    }

    fun toggleFavorite(channelId: String) {
        viewModelScope.launch {
            try {
                val saved = channelStateStore.toggleFavorite(channelId)
                mutableState.update { it.copy(
                    favoriteChannelIds = saved.favoriteChannelIds.toSet(),
                    recentChannelIds = saved.recentChannelIds,
                    error = null,
                ) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Não foi possível salvar o favorito") }
            }
        }
    }

    fun recordRecent(channelId: String) {
        viewModelScope.launch {
            try {
                val saved = channelStateStore.recordRecent(channelId)
                mutableState.update { it.copy(recentChannelIds = saved.recentChannelIds, error = null) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Não foi possível atualizar os canais recentes") }
            }
        }
    }

    fun reloadSources() {
        viewModelScope.launch {
            runCatching {
                sourceStore.list().also { sources ->
                    val cached = sources.filter { it.enabled }.mapNotNull { guideCache.read(it.id) }
                    mutableState.update { it.copy(
                        sources = sources,
                        channels = cached.flatMap { it.result.channels }.distinctBy { channel -> channel.id },
                        programs = cached.flatMap { it.result.programs }.distinctBy { program -> "${program.sourceId}|${program.channelId}|${program.startEpochMillis}|${program.title}" },
                        lastUpdatedEpochMillis = cached.associate { it.sourceId to it.updatedEpochMillis },
                        isLoading = false,
                    ) }
                }
            }
                .onSuccess { }
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
            runCatching {
                sourceStore.remove(source.id)
                guideCache.remove(source.id)
                channelStateStore.removeSource(source.id)
            }
                .onSuccess { channelState ->
                    mutableState.update { state -> state.copy(sources = state.sources.filterNot { it.id == source.id },
                        channels = state.channels.filterNot { it.sourceId == source.id },
                        programs = state.programs.filterNot { it.sourceId == source.id },
                        favoriteChannelIds = channelState.favoriteChannelIds.toSet(),
                        recentChannelIds = channelState.recentChannelIds,
                        sourceErrors = state.sourceErrors - source.id,
                        lastUpdatedEpochMillis = state.lastUpdatedEpochMillis - source.id) }
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.message ?: "Não foi possível remover a fonte") } }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val sources = mutableState.value.sources.filter { it.enabled }
            if (sources.isEmpty()) {
                mutableState.update { it.copy(channels = emptyList(), programs = emptyList(), sourceErrors = emptyMap(), lastUpdatedEpochMillis = emptyMap(), error = null) }
                return@launch
            }
            mutableState.update { it.copy(isLoading = true, error = null, sourceErrors = emptyMap()) }
            val resultBySource = linkedMapOf<String, com.nuvio.tv.domain.model.LiveTvImportResult>()
            val updatedBySource = linkedMapOf<String, Long>()
            for (source in sources) {
                val cached = try {
                    guideCache.read(source.id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                cached?.let {
                    resultBySource[source.id] = it.result
                    updatedBySource[source.id] = it.updatedEpochMillis
                }
            }
            mutableState.update { state ->
                state.copy(
                    channels = resultBySource.values.flatMap { it.channels }.distinctBy(LiveTvChannel::id),
                    programs = resultBySource.values.flatMap { it.programs }.distinctBy { "${it.sourceId}|${it.channelId}|${it.startEpochMillis}|${it.title}" },
                )
            }
            val errors = linkedMapOf<String, String>()
            for (source in sources) {
                try {
                    val result = importer.import(source)
                    resultBySource[source.id] = result
                    guideCache.write(source.id, result)
                    updatedBySource[source.id] = System.currentTimeMillis()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    errors[source.id] = failure.message ?: "Falha ao importar a fonte"
                }
            }
            val channels = resultBySource.values.flatMap { it.channels }
            mutableState.update { it.copy(
                channels = channels.distinctBy(LiveTvChannel::id),
                programs = resultBySource.values.flatMap { it.programs }
                    .distinctBy { "${it.sourceId}|${it.channelId}|${it.startEpochMillis}|${it.title}" },
                lastUpdatedEpochMillis = updatedBySource,
                sourceErrors = errors,
                isLoading = false,
                error = if (channels.isEmpty() && errors.isNotEmpty()) "Não foi possível importar canais das fontes ativas" else null,
            ) }
        }
    }
}

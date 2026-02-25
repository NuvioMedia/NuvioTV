package com.nuvio.tv.ui.screens.stream

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "StreamScreenViewModel"
private const val EMBEDDED_STREAM_GROUP_NAME = "Embedded Streams"
private const val EMBEDDED_STREAM_FALLBACK_NAME = "Embed Stream"

@HiltViewModel
class StreamScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var autoPlayHandledForSession = false
    private var directAutoPlayModeInitializedForSession = false
    private var directAutoPlayFlowEnabledForSession = false
    private var streamLoadJob: Job? = null

    private val videoId: String = savedStateHandle["videoId"] ?: ""
    private val contentType: String = savedStateHandle["contentType"] ?: ""
    private val title: String = savedStateHandle["title"] ?: ""
    private val poster: String? = savedStateHandle.getOptionalString("poster")
    private val backdrop: String? = savedStateHandle.getOptionalString("backdrop")
    private val logo: String? = savedStateHandle.getOptionalString("logo")
    private val season: Int? = savedStateHandle.get<String>("season")?.toIntOrNull()
    private val episode: Int? = savedStateHandle.get<String>("episode")?.toIntOrNull()
    private val episodeName: String? = savedStateHandle.getOptionalString("episodeName")
    private val runtime: Int? = savedStateHandle.get<String>("runtime")?.toIntOrNull()
    private val genres: String? = savedStateHandle.getOptionalString("genres")
    private val year: String? = savedStateHandle.getOptionalString("year")
    private val contentId: String? = savedStateHandle.getOptionalString("contentId")
    private val contentName: String? = savedStateHandle.getOptionalString("contentName")
    private val manualSelection: Boolean = savedStateHandle.get<String>("manualSelection")
        ?.toBooleanStrictOrNull()
        ?: false
    private val streamCacheKey: String = "${contentType.lowercase()}|$videoId"

    private val _uiState = MutableStateFlow(
        StreamScreenUiState(
            videoId = videoId,
            contentType = contentType,
            title = title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            season = season,
            episode = episode,
            episodeName = episodeName,
            runtime = runtime,
            genres = genres,
            year = year
        )
    )
    val uiState: StateFlow<StreamScreenUiState> = _uiState.asStateFlow()

    val playerPreference = playerSettingsDataStore.playerSettings
        .map { it.playerPreference }
        .distinctUntilChanged()

    private inline fun updateUiStateIfChanged(
        transform: (StreamScreenUiState) -> StreamScreenUiState
    ) {
        _uiState.update { state ->
            val next = transform(state)
            if (next == state) state else next
        }
    }

    init {
        if (manualSelection) {
            // Returning from a playback error: keep the user on stream selection.
            autoPlayHandledForSession = true
            directAutoPlayModeInitializedForSession = true
            directAutoPlayFlowEnabledForSession = false
            _uiState.update {
                it.copy(
                    isDirectAutoPlayFlow = false,
                    showDirectAutoPlayOverlay = false,
                    autoPlayStream = null,
                    autoPlayPlaybackInfo = null,
                    directAutoPlayMessage = null
                )
            }
        }
        loadMissingMetaDetailsIfNeeded()
        loadStreams()
    }

    private fun SavedStateHandle.getOptionalString(key: String): String? {
        return get<String>(key)?.takeIf { it.isNotBlank() }
    }

    fun onEvent(event: StreamScreenEvent) {
        when (event) {
            is StreamScreenEvent.OnAddonFilterSelected -> filterByAddon(event.addonName)
            is StreamScreenEvent.OnStreamSelected -> { /* Handle stream selection - will be handled in UI */ }
            StreamScreenEvent.OnAutoPlayConsumed -> {
                if (autoPlayHandledForSession &&
                    _uiState.value.autoPlayStream == null &&
                    _uiState.value.autoPlayPlaybackInfo == null
                ) {
                    return
                }
                autoPlayHandledForSession = true
                updateUiStateIfChanged {
                    it.copy(
                        autoPlayStream = null,
                        autoPlayPlaybackInfo = null
                    )
                }
            }
            StreamScreenEvent.OnRetry -> loadStreams()
            StreamScreenEvent.OnBackPress -> { /* Handle in screen */ }
        }
    }

    private fun shouldUseDirectAutoPlayFlow(
        playerPreference: PlayerPreference,
        streamAutoPlayMode: StreamAutoPlayMode
    ): Boolean {
        return playerPreference == PlayerPreference.INTERNAL &&
            streamAutoPlayMode != StreamAutoPlayMode.MANUAL
    }

    private fun loadStreams() {
        streamLoadJob?.cancel()
        streamLoadJob = viewModelScope.launch {
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            if (manualSelection) {
                directAutoPlayModeInitializedForSession = true
                directAutoPlayFlowEnabledForSession = false
                autoPlayHandledForSession = true
            } else if (!directAutoPlayModeInitializedForSession) {
                directAutoPlayFlowEnabledForSession = shouldUseDirectAutoPlayFlow(
                    playerPreference = playerSettings.playerPreference,
                    streamAutoPlayMode = playerSettings.streamAutoPlayMode
                )
                directAutoPlayModeInitializedForSession = true
            }

            val directFlowActive = directAutoPlayFlowEnabledForSession
            var resolvedAutoPlayTarget = false

            if (directFlowActive) {
                updateUiStateIfChanged {
                    it.copy(
                        isDirectAutoPlayFlow = true,
                        showDirectAutoPlayOverlay = true,
                        directAutoPlayMessage = context.getString(R.string.stream_finding_source)
                    )
                }
            }

            if (!autoPlayHandledForSession && playerSettings.streamReuseLastLinkEnabled) {
                val cached = streamLinkCacheDataStore.getValid(
                    contentKey = streamCacheKey,
                    maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                )
                if (cached != null) {
                    autoPlayHandledForSession = true
                    resolvedAutoPlayTarget = true
                    updateUiStateIfChanged {
                        it.copy(
                            autoPlayPlaybackInfo = StreamPlaybackInfo(
                                url = cached.url,
                                title = title,
                                streamName = cached.streamName,
                                year = year,
                                isExternal = false,
                                isTorrent = false,
                                infoHash = null,
                                ytId = null,
                                headers = cached.headers,
                                contentId = contentId ?: videoId.substringBefore(":"),
                                contentType = contentType,
                                contentName = contentName ?: title,
                                poster = poster,
                                backdrop = backdrop,
                                logo = logo,
                                videoId = videoId,
                                season = season,
                                episode = episode,
                                episodeTitle = episodeName,
                                bingeGroup = null,
                                rememberedAudioLanguage = cached.rememberedAudioLanguage,
                                rememberedAudioName = cached.rememberedAudioName
                            )
                        )
                    }
                }
            }

            updateUiStateIfChanged {
                it.copy(
                    isLoading = true,
                    error = null,
                    showDirectAutoPlayOverlay = if (directFlowActive) true else it.showDirectAutoPlayOverlay
                )
            }

            val installedAddons = addonRepository.getInstalledAddons().first()
            val installedAddonOrder = installedAddons.map { it.displayName }

            fun applySuccess(addonStreamGroups: List<AddonStreams>) {
                val orderedAddonStreams = StreamAutoPlaySelector.orderAddonStreams(
                    addonStreamGroups,
                    installedAddonOrder
                )
                val allStreams = orderedAddonStreams.flatMap { it.streams }
                val availableAddons = orderedAddonStreams.map { it.addonName }
                val selectedAutoPlayStream = if (autoPlayHandledForSession) {
                    null
                } else {
                    StreamAutoPlaySelector.selectAutoPlayStream(
                        streams = allStreams,
                        mode = playerSettings.streamAutoPlayMode,
                        regexPattern = playerSettings.streamAutoPlayRegex,
                        source = playerSettings.streamAutoPlaySource,
                        installedAddonNames = installedAddonOrder.toSet(),
                        selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                        selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins
                    )
                }
                if (selectedAutoPlayStream != null) {
                    resolvedAutoPlayTarget = true
                }

                val currentFilter = _uiState.value.selectedAddonFilter
                val filteredStreams = if (currentFilter == null) {
                    allStreams
                } else {
                    allStreams.filter { it.addonName == currentFilter }
                }

                updateUiStateIfChanged {
                    it.copy(
                        isLoading = false,
                        addonStreams = orderedAddonStreams,
                        allStreams = allStreams,
                        filteredStreams = filteredStreams,
                        availableAddons = availableAddons,
                        autoPlayStream = selectedAutoPlayStream,
                        error = null,
                        showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession) {
                            true
                        } else {
                            false
                        }
                    )
                }
            }

            if (shouldAttemptEmbeddedMetaStreamLookup()) {
                getEmbeddedStreamsFromMeta()?.let { embeddedAddonStreams ->
                    Log.d(
                        TAG,
                        "Using embedded video streams for videoId=$videoId count=${embeddedAddonStreams.streams.size}"
                    )
                    applySuccess(listOf(embeddedAddonStreams))
                    if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
                        directAutoPlayFlowEnabledForSession = false
                        updateUiStateIfChanged {
                            it.copy(
                                isDirectAutoPlayFlow = false,
                                showDirectAutoPlayOverlay = false,
                                directAutoPlayMessage = null
                            )
                        }
                    }
                    return@launch
                }
            }

            streamRepository.getStreamsFromAllAddons(
                type = contentType,
                videoId = videoId,
                season = season,
                episode = episode
            ).collectLatest { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        applySuccess(result.data)
                    }
                    is NetworkResult.Error -> {
                        if (directAutoPlayFlowEnabledForSession) {
                            directAutoPlayFlowEnabledForSession = false
                        }
                        updateUiStateIfChanged {
                            it.copy(
                                isLoading = false,
                                error = result.message,
                                isDirectAutoPlayFlow = false,
                                showDirectAutoPlayOverlay = false,
                                directAutoPlayMessage = null
                            )
                        }
                    }
                    NetworkResult.Loading -> {
                        updateUiStateIfChanged {
                            it.copy(
                                isLoading = true,
                                showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession) {
                                    true
                                } else {
                                    it.showDirectAutoPlayOverlay
                                }
                            )
                        }
                    }
                }
            }

            if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
                directAutoPlayFlowEnabledForSession = false
                updateUiStateIfChanged {
                    it.copy(
                        isDirectAutoPlayFlow = false,
                        showDirectAutoPlayOverlay = false,
                        directAutoPlayMessage = null
                    )
                }
            }
        }
    }

    private fun shouldAttemptEmbeddedMetaStreamLookup(): Boolean {
        val metaId = contentId?.takeIf { it.isNotBlank() } ?: return false
        if (contentType.isBlank()) return false
        if (contentType.equals("other", ignoreCase = true)) return true

        val canonicalVideoMetaId = videoId.substringBefore(":")
        return !metaId.equals(canonicalVideoMetaId, ignoreCase = true)
    }

    private suspend fun getEmbeddedStreamsFromMeta(): AddonStreams? {
        val metaId = contentId?.takeIf { it.isNotBlank() } ?: return null
        val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
            .first { it !is NetworkResult.Loading }
        val meta = (result as? NetworkResult.Success)?.data ?: return null
        val video = meta.videos.firstOrNull { it.id == videoId } ?: return null
        if (video.streams.isEmpty()) return null

        val streams = video.streams.map { stream ->
            stream.copy(
                name = stream.name ?: stream.title ?: stream.description ?: EMBEDDED_STREAM_FALLBACK_NAME,
                addonName = EMBEDDED_STREAM_GROUP_NAME,
                addonLogo = null
            )
        }

        return AddonStreams(
            addonName = EMBEDDED_STREAM_GROUP_NAME,
            addonLogo = null,
            streams = streams
        )
    }

    private fun loadMissingMetaDetailsIfNeeded() {
        val requiresMetadataLookup = genres.isNullOrBlank() || year.isNullOrBlank() || runtime == null
        if (!requiresMetadataLookup) return

        val metaId = contentId ?: videoId.substringBefore(":")
        if (metaId.isBlank() || contentType.isBlank()) return

        viewModelScope.launch {
            val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
                .first { it !is NetworkResult.Loading }

            if (result !is NetworkResult.Success) return@launch

            val meta = result.data
            val metaGenres = meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")
            val metaYear = meta.releaseInfo
                ?.substringBefore("-")
                ?.takeIf { it.isNotBlank() }
            val metaRuntime = extractRuntimeMinutes(meta)

            _uiState.update { state ->
                val posterValue = state.poster ?: meta.poster
                val backdropValue = state.backdrop ?: meta.background
                val logoValue = state.logo ?: meta.logo
                val genresValue = state.genres?.takeIf { it.isNotBlank() } ?: metaGenres
                val yearValue = state.year?.takeIf { it.isNotBlank() } ?: metaYear
                val runtimeValue = state.runtime ?: metaRuntime
                if (state.poster == posterValue &&
                    state.backdrop == backdropValue &&
                    state.logo == logoValue &&
                    state.genres == genresValue &&
                    state.year == yearValue &&
                    state.runtime == runtimeValue
                ) {
                    state
                } else {
                    state.copy(
                        poster = posterValue,
                        backdrop = backdropValue,
                        logo = logoValue,
                        genres = genresValue,
                        year = yearValue,
                        runtime = runtimeValue
                    )
                }
            }
        }
    }

    private fun extractRuntimeMinutes(meta: Meta): Int? {
        if (season != null && episode != null) {
            return meta.videos.firstOrNull { it.season == season && it.episode == episode }?.runtime
        }
        return meta.runtime
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
    }

    private fun filterByAddon(addonName: String?) {
        updateUiStateIfChanged { state ->
            if (state.selectedAddonFilter == addonName) {
                state
            } else {
                val filteredStreams = if (addonName == null) {
                    state.allStreams
                } else {
                    state.allStreams.filter { it.addonName == addonName }
                }
                state.copy(
                    selectedAddonFilter = addonName,
                    filteredStreams = filteredStreams
                )
            }
        }
    }

    /**
     * Gets the selected stream for playback
     */
    fun getStreamForPlayback(stream: Stream): StreamPlaybackInfo {
        val playbackInfo = StreamPlaybackInfo(
            url = stream.getStreamUrl(),
            title = _uiState.value.title,
            streamName = stream.name ?: stream.addonName,
            year = year,
            isExternal = stream.isExternal(),
            isTorrent = stream.isTorrent(),
            infoHash = stream.infoHash,
            ytId = stream.ytId,
            headers = stream.behaviorHints?.proxyHeaders?.request,
            contentId = contentId ?: videoId.substringBefore(":"),  // Use explicit contentId or extract from videoId
            contentType = contentType,
            contentName = contentName ?: title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            rememberedAudioLanguage = null,
            rememberedAudioName = null
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank()) {
            viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers
                )
            }
        }

        return playbackInfo
    }

    override fun onCleared() {
        super.onCleared()
        streamLoadJob?.cancel()
    }

}

data class StreamPlaybackInfo(
    val url: String?,
    val title: String,
    val streamName: String,
    val year: String?,
    val isExternal: Boolean,
    val isTorrent: Boolean,
    val infoHash: String?,
    val ytId: String?,
    val headers: Map<String, String>?,
    // Watch progress metadata
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val bingeGroup: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?
)

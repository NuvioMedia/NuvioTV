package com.nuvio.tv.ui.screens.stream

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.DirectDebridStreamPreparer
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
private const val DIRECT_AUTOPLAY_HARD_TIMEOUT_MS = 60_000L

@HiltViewModel
class StreamScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgePresentation: StreamBadgePresentation,
    streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val torrentSettings: TorrentSettings,
    private val watchProgressRepository: WatchProgressRepository,
    private val traktScrobbleService: TraktScrobbleService,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val traktAuthService: TraktAuthService,
    private val directDebridResolver: DirectDebridResolver,
    private val directDebridStreamPreparer: DirectDebridStreamPreparer,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val subtitleFileCache: com.nuvio.tv.core.player.SubtitleFileCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var autoPlayHandledForSession = false
    private var directAutoPlayModeInitializedForSession = false
    private var directAutoPlayFlowEnabledForSession = false
    private var streamLoadJob: Job? = null
    private var streamLoadScope: kotlinx.coroutines.CoroutineScope? = null
    private var streamLoadCompleted = false
    // Snapshot of addon streams captured when loading is cancelled mid-flight.
    // On resume, new repository emissions are merged with this baseline so
    // already-fetched results stay visible while missing addons get re-fetched.
    private var resumeBaselineStreams: List<AddonStreams>? = null
    private var sourceChipErrorDismissJob: Job? = null
    private var pendingCacheSaveJob: Job? = null

    private val embeddedStreamGroupName: String by lazy {
        context.getString(R.string.stream_embedded_group)
    }
    private val embeddedStreamFallbackName: String by lazy {
        context.getString(R.string.stream_embedded_fallback_name)
    }

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
    private val contentLanguage: String? = savedStateHandle.getOptionalString("contentLanguage")
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
    val streamBadgeSettings = streamBadgeSettingsDataStore.settings

    val playerPreference = playerSettingsDataStore.playerSettings
        .map { it.playerPreference }
        .distinctUntilChanged()

    val p2pEnabled = torrentSettings.settings
        .map { it.p2pEnabled }
        .distinctUntilChanged()

    fun enableP2p() = torrentSettings.setP2pEnabled(true)

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
                    autoPlayDecided = true,
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
            is StreamScreenEvent.OnStreamSelected -> {
                cancelStreamsLoad()
            }
            StreamScreenEvent.OnAutoPlayConsumed -> {
                if (autoPlayHandledForSession &&
                    _uiState.value.autoPlayStream == null &&
                    _uiState.value.autoPlayPlaybackInfo == null
                ) {
                    return
                }
                autoPlayHandledForSession = true
                directAutoPlayFlowEnabledForSession = false
                // Don't dismiss overlay if external player is active - it should
                // stay visible until user returns from external player.
                val keepOverlay = externalPlaybackTracker.isTracking
                updateUiStateIfChanged {
                    it.copy(
                        autoPlayStream = null,
                        autoPlayPlaybackInfo = null,
                        isDirectAutoPlayFlow = false,
                        showDirectAutoPlayOverlay = if (keepOverlay) true else false,
                        directAutoPlayMessage = null
                    )
                }
            }
            StreamScreenEvent.OnRetry -> loadStreams()
            StreamScreenEvent.OnBackPress -> { /* Handle in screen */ }
            StreamScreenEvent.OnResume -> {
                // If loading was cancelled (e.g. user went to player) and
                // hasn't completed yet, resume it. The baseline snapshot
                // captured at cancel time keeps existing results visible
                // while the repository re-fetches remaining addons.
                if (!streamLoadCompleted && streamLoadJob == null) {
                    loadStreams()
                }
            }
        }
    }

    fun cancelStreamsLoad() {
        // Capture current results as baseline before cancelling, so that
        // resuming loading can merge new data on top of what we already have.
        val currentStreams = _uiState.value.addonStreams
        if (currentStreams.isNotEmpty()) {
            resumeBaselineStreams = currentStreams
        }
        streamLoadScope?.cancel()
        streamLoadScope = null
        streamLoadJob = null
        updateUiStateIfChanged { it.copy(isLoading = false) }
    }

    private fun shouldUseDirectAutoPlayFlow(
        playerPreference: PlayerPreference,
        streamAutoPlayMode: StreamAutoPlayMode
    ): Boolean {
        return streamAutoPlayMode != StreamAutoPlayMode.MANUAL
    }

    private fun loadStreams() {
        streamLoadScope?.cancel()
        streamLoadScope = null
        streamLoadJob = null
        sourceChipErrorDismissJob?.cancel()
        val newScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob())
        streamLoadScope = newScope
        streamLoadJob = newScope.launch {
            streamLoadCompleted = false
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
                // In MANUAL mode, still enable direct auto-play if a persisted
                // binge group exists - same behavior as playNextEpisode in the player.
                if (!directAutoPlayFlowEnabledForSession &&
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode &&
                    playerSettings.streamAutoPlayReuseBingeGroup
                ) {
                    val hasBingeGroup = contentId?.let { bingeGroupCacheDataStore.get(it) } != null
                    if (hasBingeGroup) {
                        directAutoPlayFlowEnabledForSession = true
                    }
                }
                directAutoPlayModeInitializedForSession = true
            }

            if (
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
                !StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex)
            ) {
                directAutoPlayFlowEnabledForSession = false
                autoPlayHandledForSession = true
            }

            val directFlowActive = directAutoPlayFlowEnabledForSession
            var resolvedAutoPlayTarget = false

            if (directFlowActive) {
                updateUiStateIfChanged {
                    it.copy(
                        isDirectAutoPlayFlow = true,
                        showDirectAutoPlayOverlay = true,
                        autoPlayDecided = true,
                        directAutoPlayMessage = if (playerSettings.showPlayerLoadingStatus) {
                            context.getString(R.string.stream_finding_source)
                        } else {
                            null
                        }
                    )
                }
            } else {
                updateUiStateIfChanged {
                    it.copy(autoPlayDecided = true)
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
                    val isCachedTorrent = cached.infoHash != null
                    val showOverlay = playerSettings.playerPreference == PlayerPreference.EXTERNAL
                    updateUiStateIfChanged {
                        it.copy(
                            autoPlayPlaybackInfo = StreamPlaybackInfo(
                                url = cached.url.takeIf { u -> u.isNotBlank() },
                                title = title,
                                streamName = cached.streamName,
                                year = cached.year ?: year,
                                isExternal = false,
                                isTorrent = isCachedTorrent,
                                infoHash = cached.infoHash,
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
                                bingeGroup = cached.bingeGroup,
                                filename = cached.filename,
                                videoHash = cached.videoHash,
                                videoSize = cached.videoSize,
                                fileIdx = cached.fileIdx,
                                sources = cached.sources,
                                contentLanguage = cached.contentLanguage ?: contentLanguage
                            ),
                            showDirectAutoPlayOverlay = showOverlay || it.showDirectAutoPlayOverlay,
                            isDirectAutoPlayFlow = showOverlay || it.isDirectAutoPlayFlow
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

            val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
            val installedAddonOrder = installedAddons.map { it.displayName }
            val directDebridSourceNames = emptyList<String>()
            val directDebridAvailable = false
            val persistedBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode &&
                playerSettings.streamAutoPlayReuseBingeGroup) {
                contentId?.let { bingeGroupCacheDataStore.get(it) }
            } else null

            fun applySuccess(addonStreamGroups: List<AddonStreams>, isAllLoaded: Boolean) {
                val orderedAddonStreams = StreamAutoPlaySelector.orderAddonStreams(
                    addonStreamGroups,
                    installedAddonOrder
                )
                
                val allStreams = orderedAddonStreams.flatMap { addonStreams ->
                    addonStreams.streams
                }
                val availableAddons = orderedAddonStreams.map { it.addonName }
                // Auto-select only after all addons have responded or the
                // configured timeout has elapsed. This gives slower addons a
                // chance to return higher-quality streams before the selector
                // picks from whatever is available.
                val shouldAutoSelect = !autoPlayHandledForSession && !resolvedAutoPlayTarget && isAllLoaded
                val selectedAutoPlayStream = if (!shouldAutoSelect) {
                    null
                } else {
                    StreamAutoPlaySelector.selectAutoPlayStream(
                        streams = allStreams,
                        mode = playerSettings.streamAutoPlayMode,
                        regexPattern = playerSettings.streamAutoPlayRegex,
                        source = playerSettings.streamAutoPlaySource,
                        installedAddonNames = installedAddonOrder.toSet(),
                        selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                        selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                        preferredBingeGroup = persistedBingeGroup,
                        preferBingeGroupInSelection = persistedBingeGroup != null
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
                        sourceChips = mergeSourceChipStatuses(
                            existing = _uiState.value.sourceChips,
                            succeededNames = orderedAddonStreams.map { it.addonName }
                        ),
                        // Preserve an already-resolved stream: the post-collect
                        // "isAllLoaded=true" pass re-runs the selector with
                        // shouldAutoSelect=false once a target is resolved, and
                        // would otherwise clobber the real pick with null before
                        // Compose observes it.
                        autoPlayStream = selectedAutoPlayStream ?: it.autoPlayStream,
                        error = null,
                        showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession || it.autoPlayPlaybackInfo != null) {
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
                    applySuccess(listOf(embeddedAddonStreams), isAllLoaded = true)
                    updateSourceChipsForEmbedded(embeddedAddonStreams.addonName)
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

            // Grab and clear the baseline snapshot.  When non-null we are
            // resuming after a cancel and should merge incoming repository
            // emissions with these previously-fetched results.
            val baseline = resumeBaselineStreams
            resumeBaselineStreams = null

            // If resuming, seed the UI with the baseline immediately so
            // the user sees their previous results right away.
            if (baseline != null) {
                applySuccess(baseline, isAllLoaded = false)
            }

            updateSourceChipsForFetchStart(installedAddons, directDebridSourceNames, baseline)

            // Merges repository data with the resume baseline.  Addons
            // present in the new data override the baseline; addons only
            // in the baseline are preserved until the repository catches up.
            fun mergeWithBaseline(repoData: List<AddonStreams>): List<AddonStreams> {
                if (baseline == null) return repoData
                val repoAddonNames = repoData.map { it.addonName }.toSet()
                val preserved = baseline.filter { it.addonName !in repoAddonNames }
                return repoData + preserved
            }

            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var debridPreparationLaunched = false
            val isUnlimitedTimeout = playerSettings.streamAutoPlayTimeoutSeconds == PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED

            fun launchDirectDebridPreparationIfNeeded(streamGroups: List<AddonStreams>) {
                if (debridPreparationLaunched || streamGroups.none { group -> group.streams.any { it.isReadyForDebridPreparation() } }) {
                    return
                }
                debridPreparationLaunched = true
                viewModelScope.launch {
                    directDebridStreamPreparer.prepare(
                        streams = _uiState.value.allStreams,
                        season = season,
                        episode = episode,
                        playerSettings = playerSettings,
                        installedAddonNames = installedAddonOrder.toSet()
                    ) { original, prepared ->
                        updateUiStateIfChanged { state ->
                            val updatedGroups = directDebridStreamPreparer.replacePreparedStream(
                                groups = state.addonStreams,
                                original = original,
                                prepared = prepared
                            )
                            if (updatedGroups == state.addonStreams) {
                                state
                            } else {
                                val updatedAllStreams = updatedGroups.flatMap { addonStreams ->
                                    addonStreams.streams
                                }
                                val currentFilter = state.selectedAddonFilter
                                val filteredStreams = if (currentFilter == null) {
                                    updatedAllStreams
                                } else {
                                    updatedAllStreams.filter { it.addonName == currentFilter }
                                }
                                state.copy(
                                    addonStreams = updatedGroups,
                                    allStreams = updatedAllStreams,
                                    filteredStreams = filteredStreams
                                )
                            }
                        }
                    }
                }
            }

            val streamLoadInner = launch {
                streamRepository.getStreamsFromAllAddons(
                    type = contentType,
                    videoId = videoId,
                    season = season,
                    episode = episode
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            val merged = mergeWithBaseline(result.data)
                            lastSuccessData = merged
                            applySuccess(merged, isAllLoaded = false)
                            launchDirectDebridPreparationIfNeeded(merged)

                            if (autoSelectTriggered || resolvedAutoPlayTarget || autoPlayHandledForSession) {
                                // Already resolved — nothing more to do.
                            } else if (timeoutElapsed) {
                                // Timeout elapsed: run full auto-select (binge
                                // group preferred, then fallback to mode).
                                applySuccess(merged, isAllLoaded = true)
                                if (resolvedAutoPlayTarget) {
                                    autoSelectTriggered = true
                                } else if (directAutoPlayFlowEnabledForSession && !isUnlimitedTimeout) {
                                    // Bounded/instant timeout: no match found.
                                    // If there are still torrents with a pending
                                    // debrid cache check, wait for the next emission
                                    // (which will carry the CACHED/NOT_CACHED result)
                                    // instead of showing the picker immediately.
                                    val hasCheckingTorrents = merged.any { group ->
                                        group.streams.any { s ->
                                            s.isTorrent() && s.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.CHECKING
                                        }
                                    }
                                    if (!hasCheckingTorrents) {
                                        autoPlayHandledForSession = true
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
                            } else if (directFlowActive && persistedBingeGroup != null) {
                                // Before timeout: eagerly check binge group only
                                // (no fallback to FIRST_STREAM/REGEX yet). If a
                                // match is found we can start playback immediately
                                // without waiting for the full timeout.
                                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(
                                    merged, installedAddonOrder
                                )
                                val allStreams = orderedStreams.flatMap { it.streams }
                                val earlyMatch = StreamAutoPlaySelector.selectAutoPlayStream(
                                    streams = allStreams,
                                    mode = playerSettings.streamAutoPlayMode,
                                    regexPattern = playerSettings.streamAutoPlayRegex,
                                    source = playerSettings.streamAutoPlaySource,
                                    installedAddonNames = installedAddonOrder.toSet(),
                                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                    preferredBingeGroup = persistedBingeGroup,
                                    preferBingeGroupInSelection = true,
                                    bingeGroupOnly = true
                                )
                                if (earlyMatch != null) {
                                    resolvedAutoPlayTarget = true
                                    autoSelectTriggered = true
                                    updateUiStateIfChanged {
                                        it.copy(
                                            autoPlayStream = earlyMatch,
                                            showDirectAutoPlayOverlay = true
                                        )
                                    }
                                }
                            }
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
                // All addons finished — run auto-select if not yet triggered
                if (!autoSelectTriggered) {
                    autoSelectTriggered = true
                    lastSuccessData?.let { applySuccess(it, isAllLoaded = true) }
                }
                markRemainingSourceChipsAsError()
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

            // Timeout semantics:
            // - 0 (instant): timeoutElapsed immediately, first addon response
            //   triggers auto-select; if no match -> dismiss overlay at once.
            // - 1-30s (bounded): wait the configured delay, then auto-select
            //   from whatever streams arrived; if no match -> dismiss overlay.
            // - unlimited: check each addon response as it arrives; if a match
            //   is found use it immediately; otherwise keep waiting until all
            //   addons finish or the hard timeout (60s) forces a fallback.
            val timeoutMs = playerSettings.streamAutoPlayTimeoutSeconds * 1_000L
            if (PlayerSettings.isBoundedTimeout(playerSettings.streamAutoPlayTimeoutSeconds)) {
                delay(timeoutMs)
            }
            timeoutElapsed = true
            val directDebridLoadedByTimeout = !directDebridAvailable ||
                lastSuccessData?.any { it.addonName in directDebridSourceNames } == true
            if (!autoSelectTriggered && lastSuccessData != null && directDebridLoadedByTimeout) {
                applySuccess(lastSuccessData, isAllLoaded = true)
                if (resolvedAutoPlayTarget) {
                    autoSelectTriggered = true
                }
            }

            // For instant/bounded timeout: if streams arrived but no auto-play
            // target was resolved, tear down the overlay immediately so the
            // user sees the stream picker.
            // For unlimited: keep the overlay — we continue checking as more
            // addons respond until the hard timeout below.
            if (directFlowActive && !resolvedAutoPlayTarget && lastSuccessData != null && !isUnlimitedTimeout) {
                // If torrents are still pending cache check, the next emission
                // will carry the result — don't tear down yet.
                val hasCheckingTorrents = lastSuccessData?.any { group ->
                    group.streams.any { s ->
                        s.isTorrent() && s.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.CHECKING
                    }
                } == true
                if (!hasCheckingTorrents) {
                    autoPlayHandledForSession = true
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

            // Hard wall-clock fallback: if the upstream stream flow never terminates
            // (e.g. a scraper hangs and keeps the plugin channelFlow open), the direct
            // autoplay overlay would otherwise stay visible indefinitely. Force a
            // teardown so the user lands in the manual stream list with whatever
            // results have already arrived.
            if (directFlowActive) {
                delay(DIRECT_AUTOPLAY_HARD_TIMEOUT_MS)
                if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
                    Log.w(TAG, "Direct autoplay hard timeout reached; falling back to manual selection")
                    lastSuccessData?.let {
                        if (!autoSelectTriggered) {
                            autoSelectTriggered = true
                            applySuccess(it, isAllLoaded = true)
                        }
                    }
                    if (!resolvedAutoPlayTarget) {
                        directAutoPlayFlowEnabledForSession = false
                        updateUiStateIfChanged {
                            it.copy(
                                isLoading = false,
                                isDirectAutoPlayFlow = false,
                                showDirectAutoPlayOverlay = false,
                                directAutoPlayMessage = null
                            )
                        }
                        streamLoadInner.cancel()
                        markRemainingSourceChipsAsError()
                    }
                }
            }
            // Wait for the inner collection to actually finish before
            // marking the load as completed.  Without this join the outer
            // coroutine races past the timeout/hard-timeout blocks and
            // sets streamLoadCompleted = true while addons are still
            // being fetched — which makes OnResume think there is nothing
            // left to do when the user returns from the player.
            streamLoadInner.join()
            // Only mark completed if the coroutine was NOT cancelled.
            // ensureActive() throws CancellationException if the scope
            // was cancelled (e.g. user selected a stream and navigated
            // to the player), preventing a false "completed" flag that
            // would block resumption on return.
            ensureActive()
            streamLoadCompleted = true
        }
    }

    private fun shouldAttemptEmbeddedMetaStreamLookup(): Boolean {
        val metaId = contentId?.takeIf { it.isNotBlank() } ?: return false
        if (contentType.isBlank()) return false
        if (contentType.equals("other", ignoreCase = true)) return true

        val canonicalVideoMetaId = videoId.substringBefore(":")
        return !metaId.equals(canonicalVideoMetaId, ignoreCase = true)
    }

    private suspend fun updateSourceChipsForFetchStart(
        installedAddons: List<com.nuvio.tv.domain.model.Addon>,
        directDebridSourceNames: List<String>,
        baseline: List<AddonStreams>? = null
    ) {
        val addonNames = installedAddons
            .filter { it.supportsStreamResourceForChip(contentType) }
            .map { it.displayName }

        val pluginNames = try {
            if (pluginManager.pluginsEnabled.first()) {
                val groupByRepository = pluginManager.groupStreamsByRepository.first()
                val scrapers = pluginManager.enabledScrapers.first()
                    .filter { it.supportsType(contentType) }
                if (groupByRepository) {
                    val repositoriesById = pluginManager.repositories.first().associateBy { it.id }
                    scrapers
                        .map { scraper ->
                            repositoriesById[scraper.repositoryId]?.name?.takeIf { it.isNotBlank() } ?: scraper.name
                        }
                        .distinct()
                } else {
                    scrapers
                        .map { it.name }
                        .distinct()
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        val orderedNames = (directDebridSourceNames + addonNames + pluginNames).distinct()
        if (orderedNames.isEmpty()) {
            updateUiStateIfChanged { it.copy(sourceChips = emptyList()) }
            return
        }

        // When resuming, addons that already returned results in the
        // previous run keep their SUCCESS status instead of flashing
        // back to LOADING.  Only genuinely-pending sources show the
        // loading indicator.
        val alreadySucceeded = baseline?.map { it.addonName }?.toSet() ?: emptySet()

        updateUiStateIfChanged { state ->
            state.copy(
                sourceChips = orderedNames.map { name ->
                    if (name in alreadySucceeded) {
                        SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
                    } else {
                        SourceChipItem(name = name, status = SourceChipStatus.LOADING)
                    }
                }
            )
        }
    }

    private fun updateSourceChipsForEmbedded(name: String) {
        updateUiStateIfChanged { state ->
            val chips = if (state.sourceChips.any { it.name == name }) {
                state.sourceChips.map { chip ->
                    if (chip.name == name) chip.copy(status = SourceChipStatus.SUCCESS) else chip
                }
            } else {
                listOf(SourceChipItem(name = name, status = SourceChipStatus.SUCCESS))
            }
            state.copy(sourceChips = chips)
        }
    }

    private fun mergeSourceChipStatuses(
        existing: List<SourceChipItem>,
        succeededNames: List<String>
    ): List<SourceChipItem> {
        if (succeededNames.isEmpty()) return existing
        if (existing.isEmpty()) {
            return succeededNames.distinct().map { name ->
                SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
            }
        }

        val successSet = succeededNames.toSet()
        val updated = existing.map { chip ->
            if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
        }.toMutableList()

        val knownNames = updated.map { it.name }.toSet()
        succeededNames.forEach { name ->
            if (name !in knownNames) {
                updated += SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
            }
        }
        return updated
    }

    private fun markRemainingSourceChipsAsError() {
        var markedAnyError = false
        updateUiStateIfChanged { state ->
            val hasPending = state.sourceChips.any { it.status == SourceChipStatus.LOADING }
            if (!hasPending) return@updateUiStateIfChanged state
            markedAnyError = true
            state.copy(
                sourceChips = state.sourceChips.map { chip ->
                    if (chip.status == SourceChipStatus.LOADING) {
                        chip.copy(status = SourceChipStatus.ERROR)
                    } else {
                        chip
                    }
                }
            )
        }
        if (markedAnyError) {
            scheduleErrorChipRemoval()
        }
    }

    private fun scheduleErrorChipRemoval() {
        sourceChipErrorDismissJob?.cancel()
        sourceChipErrorDismissJob = viewModelScope.launch {
            delay(1600L)
            updateUiStateIfChanged { state ->
                val remaining = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
                if (remaining.size == state.sourceChips.size) state else state.copy(sourceChips = remaining)
            }
        }
    }

    private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" &&
                (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) }) &&
                run {
                    val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                        ?: idPrefixes.takeIf { it.isNotEmpty() }
                    prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
                }
        }
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
                name = stream.name ?: stream.title ?: stream.description ?: embeddedStreamFallbackName,
                addonName = embeddedStreamGroupName,
                addonLogo = null
            )
        }

        val group = AddonStreams(
            addonName = embeddedStreamGroupName,
            addonLogo = null,
            streams = streams
        )
        return streamBadgePresentation.apply(listOf(group)).firstOrNull() ?: group
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
                val backdropValue = state.backdrop ?: meta.backdropUrl
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

    suspend fun resolveStreamForPlayback(stream: Stream): StreamPlaybackInfo? {
        if (!directDebridResolver.shouldResolveToPlayableStream(stream)) {
            return getStreamForPlayback(stream)
        }

        val showLoadingStatus = playerSettingsDataStore.playerSettings.first().showPlayerLoadingStatus
        updateUiStateIfChanged {
            it.copy(
                showDirectAutoPlayOverlay = true,
                directAutoPlayMessage = if (showLoadingStatus) {
                    context.getString(R.string.debrid_resolving_stream)
                } else {
                    null
                },
                playbackErrorMessage = null
            )
        }

        val basePlaybackInfo = getStreamForPlayback(stream)
        return when (val result = directDebridResolver.resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> {
                if (!_uiState.value.isDirectAutoPlayFlow) {
                    updateUiStateIfChanged {
                        it.copy(
                            showDirectAutoPlayOverlay = false,
                            directAutoPlayMessage = null
                        )
                    }
                } else {
                    updateUiStateIfChanged {
                        it.copy(directAutoPlayMessage = null)
                    }
                }
                cancelStreamsLoad()
                val resolved = basePlaybackInfo.copy(
                    url = result.url,
                    isExternal = false,
                    isTorrent = false,
                    infoHash = null,
                    headers = null,
                    filename = result.filename ?: basePlaybackInfo.filename,
                    videoSize = result.videoSize ?: basePlaybackInfo.videoSize
                )
                // Save resolved URL to cache for reuse last link
                if (!result.url.isNullOrBlank()) {
                    pendingCacheSaveJob = viewModelScope.launch {
                        streamLinkCacheDataStore.save(
                            contentKey = streamCacheKey,
                            url = result.url,
                            streamName = resolved.streamName,
                            headers = null,
                            filename = resolved.filename,
                            videoHash = resolved.videoHash,
                            videoSize = resolved.videoSize,
                            bingeGroup = resolved.bingeGroup,
                            contentLanguage = contentLanguage,
                            year = year
                        )
                    }
                }
                resolved
            }
            DirectDebridResolveResult.MissingApiKey -> {
                showDirectDebridPlaybackError(context.getString(R.string.debrid_missing_api_key), refreshStreams = false)
                null
            }
            DirectDebridResolveResult.NotCached -> {
                showDirectDebridPlaybackError(context.getString(R.string.debrid_not_cached), refreshStreams = false)
                null
            }
            DirectDebridResolveResult.Stale -> {
                showDirectDebridPlaybackError(context.getString(R.string.debrid_stale_stream), refreshStreams = true)
                null
            }
            DirectDebridResolveResult.Error -> {
                showDirectDebridPlaybackError(context.getString(R.string.debrid_resolution_failed), refreshStreams = false)
                null
            }
        }
    }

    fun onPlaybackErrorShown() {
        updateUiStateIfChanged { it.copy(playbackErrorMessage = null) }
    }

    fun onInternalPlayerLaunching() {
        updateUiStateIfChanged {
            it.copy(showDirectAutoPlayOverlay = false, directAutoPlayMessage = null)
        }
    }

    /**
     * Returns true if an external player is currently active (launched but not yet returned).
     * Used to keep the overlay visible while external player is on screen.
     */
    fun isExternalPlayerActive(): Boolean = externalPlaybackTracker.isTracking

    fun isExternalPlayerAutoLaunch(): Boolean = externalPlaybackTracker.isAutoLaunch

    /** Release the MainActivity auto-next loader once this Stream screen has settled. */
    fun dismissExternalAutoNextOverlay() {
        externalPlaybackTracker.dismissAutoNextOverlay()
    }

    /** Set to true when external player is launched, reset on stop. */
    private var externalPlayerLaunched = false
    private var externalPlayerLaunchTimeMs = 0L

    fun stopExternalPlayerTracking() {
        if (!externalPlayerLaunched) return
        // Ignore if called during subtitle fetch (MAX_VALUE) or within 500ms of
        // actual player launch — this is a spurious ON_RESUME from DisposableEffect
        // registration, not a real return from external player.
        if (externalPlayerLaunchTimeMs == Long.MAX_VALUE) return
        if (System.currentTimeMillis() - externalPlayerLaunchTimeMs < 500L) return
        externalPlayerLaunched = false
        externalPlayerLaunchTimeMs = 0L
        if (com.nuvio.tv.core.player.ZidooPlayerMonitor.isZidooDevice()) {
            externalPlaybackTracker.dismissOverlayOnly()
        } else {
            externalPlaybackTracker.stopTracking()
        }
        updateUiStateIfChanged {
            it.copy(
                showDirectAutoPlayOverlay = false,
                externalPlayerOverlayVisible = false,
                directAutoPlayMessage = null
            )
        }
    }

    private fun showDirectDebridPlaybackError(message: String, refreshStreams: Boolean) {
        directAutoPlayFlowEnabledForSession = false
        updateUiStateIfChanged {
            it.copy(
                isDirectAutoPlayFlow = false,
                showDirectAutoPlayOverlay = false,
                directAutoPlayMessage = null,
                autoPlayStream = null,
                playbackErrorMessage = message
            )
        }
        if (refreshStreams) {
            loadStreams()
        }
    }

    /**
     * Gets the selected stream for playback
     */
    fun getStreamForPlayback(stream: Stream): StreamPlaybackInfo {
        cancelStreamsLoad()
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
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            addonName = stream.addonName,
            addonLogo = stream.addonLogo,
            streamDescription = stream.description,
            fileIdx = stream.fileIdx,
            sources = stream.sources,
            contentLanguage = contentLanguage
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank() && !playbackInfo.isExternal) {
            pendingCacheSaveJob = viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers,
                    filename = playbackInfo.filename,
                    videoHash = playbackInfo.videoHash,
                    videoSize = playbackInfo.videoSize,
                    bingeGroup = playbackInfo.bingeGroup,
                    contentLanguage = contentLanguage,
                    year = year
                )
            }
        }
        // Persist binge group per-content for cross-episode reuse (independent of URL).
        val bg = playbackInfo.bingeGroup
        val cid = playbackInfo.contentId
        if (bg != null && !cid.isNullOrBlank()) {
            viewModelScope.launch {
                bingeGroupCacheDataStore.save(cid, bg)
            }
        }

        return playbackInfo
    }

    suspend fun awaitStreamLinkCacheSave() {
        pendingCacheSaveJob?.join()
    }

    override fun onCleared() {
        super.onCleared()
        streamLoadScope?.cancel()
        streamLoadScope = null
        streamLoadJob = null
        sourceChipErrorDismissJob?.cancel()
    }

    /**
     * Get the resume position (in ms) for the given playback info.
     * Returns 0 if no progress is saved.
     */
    suspend fun getResumePositionMs(playbackInfo: StreamPlaybackInfo): Long {
        val contentId = playbackInfo.contentId ?: return 0L
        val progress = if (playbackInfo.season != null && playbackInfo.episode != null) {
            watchProgressRepository.getEpisodeProgress(contentId, playbackInfo.season, playbackInfo.episode)
        } else {
            watchProgressRepository.getProgress(contentId)
        }
        val wp = progress.first() ?: return 0L
        // Don't resume if completed
        if (wp.isCompleted()) return 0L
        return wp.position
    }

    /**
     * Launch external player via the centralized [ExternalPlaybackTracker].
     * Handles metadata, keep-alive service, Zidoo polling, and ActivityResult - all
     * independently of composable lifecycle.
     *
     * If "Forward subtitles to external player" is enabled, fetches subtitles in
     * preferred language before launching (with overlay feedback).
     */
    suspend fun launchExternalPlayer(
        playbackInfo: StreamPlaybackInfo,
        url: String,
        resumePositionMs: Long = 0L,
        autoLaunch: Boolean = false,
        context: android.content.Context
    ) {
        updateUiStateIfChanged {
            it.copy(
                showDirectAutoPlayOverlay = true,
                externalPlayerOverlayVisible = true,
                directAutoPlayMessage = null
            )
        }
        externalPlayerLaunched = true
        // Block stopExternalPlayerTracking during subtitle fetch and player launch.
        // Will be set to real timestamp right before the player intent is sent.
        externalPlayerLaunchTimeMs = Long.MAX_VALUE

        val contentId = playbackInfo.contentId ?: videoId.substringBefore(":")
        val metadata = com.nuvio.tv.core.player.ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = playbackInfo.contentType ?: "movie",
            contentName = playbackInfo.contentName ?: playbackInfo.title,
            poster = playbackInfo.poster,
            backdrop = playbackInfo.backdrop,
            logo = playbackInfo.logo,
            videoId = playbackInfo.videoId ?: contentId,
            season = playbackInfo.season,
            episode = playbackInfo.episode,
            episodeTitle = playbackInfo.episodeTitle,
            year = playbackInfo.year
        )

        val settings = playerSettingsDataStore.playerSettings.first()
        val subtitleInputs = if (settings.externalPlayerForwardSubtitles) {
            fetchSubtitlesForExternalPlayer(metadata, playbackInfo, settings)
        } else {
            null
        }

        // Set timestamp right before actual launch so the 500ms guard
        // protects against spurious ON_RESUME after the player intent is sent.
        externalPlayerLaunchTimeMs = System.currentTimeMillis()

        externalPlaybackTracker.launchPlayer(
            metadata = metadata,
            url = url,
            title = metadata.buildPlayerTitle(),
            headers = playbackInfo.headers,
            resumePositionMs = resumePositionMs,
            subtitles = subtitleInputs,
            autoLaunch = autoLaunch,
            context = context
        )
    }

    /**
     * Fetch subtitles in preferred language for external player.
     * Shows "Loading subtitles..." on overlay during fetch.
     * Returns null on failure (player launches without subtitles).
     */
    private suspend fun fetchSubtitlesForExternalPlayer(
        metadata: com.nuvio.tv.core.player.ExternalPlaybackMetadata,
        playbackInfo: StreamPlaybackInfo,
        settings: PlayerSettings
    ): List<com.nuvio.tv.core.player.SubtitleInput>? {
        val preferred = settings.subtitleStyle.preferredLanguage.trim().lowercase()
        if (preferred == "none") return null

        val preferredLanguages = listOfNotNull(
            preferred,
            settings.subtitleStyle.secondaryPreferredLanguage?.trim()?.lowercase()
                ?.takeIf { it != "none" && it.isNotBlank() }
        ).distinct()

        if (preferredLanguages.isEmpty()) return null

        val showLoadingStatus = settings.showPlayerLoadingStatus
        updateUiStateIfChanged {
            it.copy(
                directAutoPlayMessage = if (showLoadingStatus) {
                    context.getString(R.string.subtitle_loading_addon)
                } else {
                    null
                }
            )
        }

        return try {
            val allSubtitles = subtitleRepository.getSubtitles(
                type = metadata.contentType,
                id = metadata.contentId,
                videoId = metadata.videoId,
                videoHash = playbackInfo.videoHash,
                videoSize = playbackInfo.videoSize,
                filename = playbackInfo.filename,
                onProgress = { completed, total, addonName ->
                    val msg = if (completed == 0) {
                        context.getString(R.string.player_loading_subtitles_from, total)
                    } else if (addonName != null) {
                        context.getString(R.string.player_loading_subtitles_addon, addonName, completed, total)
                    } else {
                        context.getString(R.string.player_loading_subtitles_progress, completed, total)
                    }
                    if (showLoadingStatus) {
                        updateUiStateIfChanged { it.copy(directAutoPlayMessage = msg) }
                    }
                }
            )

            // Filter to preferred languages only
            val filtered = allSubtitles.filter { subtitle ->
                preferredLanguages.any { lang ->
                    com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils.matchesLanguageCode(
                        subtitle.lang, lang
                    )
                }
            }

            if (filtered.isEmpty()) {
                Log.d(TAG, "No subtitles found for preferred languages: $preferredLanguages")
                null
            } else {
                Log.d(TAG, "Found ${filtered.size} subtitles for external player, downloading to cache...")
                val inputs = filtered.map { subtitle ->
                    com.nuvio.tv.core.player.SubtitleInput(
                        url = subtitle.url,
                        name = "${subtitle.getDisplayLanguage()} - ${subtitle.addonName}",
                        lang = subtitle.lang
                    )
                }
                // Download subtitle files to local cache and convert to content:// URIs
                subtitleFileCache.cacheSubtitles(inputs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch subtitles for external player", e)
            null
        }
    }

    /**
     * Save watch progress returned by an external player.
     * Called when the external player returns position/duration data via ActivityResult.
     *
     * Sends both scrobbleStart + scrobbleStop to Trakt so the playback session is properly
     * recorded (Trakt requires an active session before stop will persist progress).
     */
    fun saveExternalPlayerProgress(
        playbackInfo: StreamPlaybackInfo,
        positionMs: Long,
        durationMs: Long?
    ) {
        val contentId = playbackInfo.contentId ?: return
        val videoId = playbackInfo.videoId ?: contentId
        val effectiveDuration = durationMs ?: 0L

        viewModelScope.launch {
            val progress = WatchProgress(
                contentId = contentId,
                contentType = playbackInfo.contentType ?: "movie",
                name = playbackInfo.contentName ?: playbackInfo.title,
                poster = playbackInfo.poster,
                backdrop = playbackInfo.backdrop,
                logo = playbackInfo.logo,
                videoId = videoId,
                season = playbackInfo.season,
                episode = playbackInfo.episode,
                episodeTitle = playbackInfo.episodeTitle,
                position = positionMs,
                duration = effectiveDuration,
                lastWatched = System.currentTimeMillis()
            )
            Log.d(TAG, "Saving external player progress: pos=${positionMs}ms, dur=${effectiveDuration}ms, " +
                "content=$contentId, video=$videoId")
            watchProgressRepository.saveProgress(progress)

            // Send Trakt scrobble (start + stop) so the playback session is recorded.
            // Only attempt if Trakt is authenticated to avoid unnecessary API calls.
            if (traktAuthService.getCurrentAuthState().isAuthenticated &&
                traktAuthService.hasRequiredCredentials()) {
                val progressPercent = if (effectiveDuration > 0L) {
                    (positionMs.toFloat() / effectiveDuration.toFloat() * 100f).coerceIn(0f, 100f)
                } else {
                    0f
                }
                if (progressPercent > 0f) {
                    val scrobbleItem = buildScrobbleItem(playbackInfo)
                    if (scrobbleItem != null) {
                        Log.d(TAG, "Sending Trakt scrobble for external player: ${progressPercent}%")
                        traktScrobbleService.scrobbleStart(scrobbleItem, progressPercent = 0f)
                        traktScrobbleService.scrobbleStop(scrobbleItem, progressPercent = progressPercent)
                    }
                }
            }
        }
    }

    private suspend fun buildScrobbleItem(playbackInfo: StreamPlaybackInfo): TraktScrobbleItem? {
        val rawContentId = playbackInfo.contentId ?: return null
        val parsedIds = parseContentIds(rawContentId)
        val ids = toTraktIds(parsedIds)
        if (ids.trakt == null && ids.imdb.isNullOrBlank() && ids.tmdb == null) return null

        val parsedYear = extractYear(playbackInfo.year)
        val normalizedType = playbackInfo.contentType?.lowercase()
        val isEpisode = normalizedType in listOf("series", "tv") &&
            playbackInfo.season != null && playbackInfo.episode != null

        return if (isEpisode) {
            // Use episode mapping to translate addon season/episode to Trakt numbering
            // (handles anime, specials, different season structures)
            val mapped = traktEpisodeMappingService.prefetchEpisodeMapping(
                contentId = rawContentId,
                contentType = playbackInfo.contentType,
                videoId = playbackInfo.videoId,
                season = playbackInfo.season,
                episode = playbackInfo.episode
            )
            val effectiveSeason = mapped?.season ?: playbackInfo.season ?: return null
            val effectiveEpisode = mapped?.episode ?: playbackInfo.episode ?: return null

            TraktScrobbleItem.Episode(
                showTitle = playbackInfo.contentName ?: playbackInfo.title,
                showYear = parsedYear,
                showIds = ids,
                season = effectiveSeason,
                number = effectiveEpisode,
                episodeTitle = playbackInfo.episodeTitle
            )
        } else {
            TraktScrobbleItem.Movie(
                title = playbackInfo.contentName ?: playbackInfo.title,
                year = parsedYear,
                ids = ids
            )
        }
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
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val addonName: String? = null,
    val addonLogo: String? = null,
    val streamDescription: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val contentLanguage: String? = null
)

private fun Stream.isReadyForDebridPreparation(): Boolean =
    getStreamUrl() == null &&
        (isDirectDebrid() || (needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED))

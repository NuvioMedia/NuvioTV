package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }

    
    val desiredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && desiredSeason != null) {
        selectEpisodesSeason(desiredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            showControls = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.buildSourceRequestKey(type: String, videoId: String, season: Int?, episode: Int?): String {
    return "$type|$videoId|${season ?: -1}|${episode ?: -1}"
}

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    val type: String
    val vid: String
    val seasonArg: Int?
    val episodeArg: Int?

    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        type = contentType ?: return
        vid = currentVideoId ?: contentId ?: return
        seasonArg = currentSeason
        episodeArg = currentEpisode
    } else {
        type = contentType ?: "movie"
        vid = contentId ?: return
        seasonArg = null
        episodeArg = null
    }

    val requestKey = buildSourceRequestKey(type = type, videoId = vid, season = seasonArg, episode = episodeArg)
    val state = _uiState.value
    val hasCachedPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload) {
        return
    }
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    sourceStreamsJob?.cancel()
    sourceChipErrorDismissJob?.cancel()
    sourceStreamsJob = scope.launch {
        sourceStreamsCacheRequestKey = requestKey
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = true,
                sourceStreamsError = null,
                sourceAllStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceAllStreams,
                sourceSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.sourceSelectedAddonFilter,
                sourceFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceFilteredStreams,
                sourceAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.sourceAvailableAddons,
                sourceChips = if (forceRefresh || targetChanged) emptyList() else it.sourceChips
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first()
        val installedAddonOrder = installedAddons.map { it.displayName }
        updateSourceChipsForFetchStart(type, installedAddons)

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = allStreams,
                            sourceSelectedAddonFilter = null,
                            sourceFilteredStreams = allStreams,
                            sourceAvailableAddons = availableAddons,
                            sourceChips = mergeSourceChipStatuses(
                                existing = it.sourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            sourceStreamsError = null
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingSourceStreams = true) }
                }
            }
        }
        markRemainingSourceChipsAsError()
    }
}

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceChips = emptyList()
        )
    }
    sourceChipErrorDismissJob?.cancel()
    scheduleHideControls()
}

internal fun PlayerRuntimeController.filterSourceStreamsByAddon(addonName: String?) {
    val allStreams = _uiState.value.sourceAllStreams
    val filteredStreams = if (addonName == null) {
        allStreams
    } else {
        allStreams.filter { it.addonName == addonName }
    }
    _uiState.update {
        it.copy(
            sourceSelectedAddonFilter = addonName,
            sourceFilteredStreams = filteredStreams
        )
    }
}

private suspend fun PlayerRuntimeController.updateSourceChipsForFetchStart(
    type: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type) }
        .map { it.displayName }

    val pluginNames = try {
        if (pluginManager.pluginsEnabled.first()) {
            pluginManager.enabledScrapers.first()
                .filter { it.supportsType(type) }
                .map { it.name }
                .distinct()
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    val ordered = (addonNames + pluginNames).distinct()
    _uiState.update {
        it.copy(
            sourceChips = ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) }
        )
    }
}

private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }

    val successSet = succeededNames.toSet()
    val updated = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()

    val known = updated.map { it.name }.toSet()
    succeededNames.forEach { name ->
        if (name !in known) updated += SourceChipItem(name, SourceChipStatus.SUCCESS)
    }
    return updated
}

private fun PlayerRuntimeController.markRemainingSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.sourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
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
    if (!markedAnyError) return

    sourceChipErrorDismissJob?.cancel()
    sourceChipErrorDismissJob = scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.copy(
                sourceChips = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
            )
        }
    }
}

private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String): Boolean {
    return resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) })
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(stream: Stream) {
    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(sourceStreamsError = "Invalid stream URL") }
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        .filterKeys { !it.equals("Range", ignoreCase = true) }
    currentStreamUrl = url
    currentHeaders = newHeaders
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentFilename = stream.behaviorHints?.filename
        ?: url.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    hasRetriedCurrentStreamAfter416 = false
    lastSavedPosition = 0L
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null
        )
    }
    showStreamSourceIndicator(stream)
    resetNextEpisodeCardState(clearEpisode = false)

    _exoPlayer?.let { player ->
        try {
            player.setMediaSource(mediaSourceFactory.createMediaSource(url, newHeaders))
            player.prepare()
            player.playWhenReady = true
            startFrameRateProbe(
                url,
                newHeaders,
                _uiState.value.frameRateMatchingMode != FrameRateMatchingMode.OFF
            )
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Failed to play selected stream") }
            return
        }
    } ?: run {
        initializePlayer(url, newHeaders)
    }

    loadSavedProgressFor(currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return

    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(
            episodesSelectedSeason = season,
            episodes = episodesForSeason
        )
    }
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type = contentType
    val id = contentId
    if (type.isNullOrBlank() || id.isNullOrBlank()) return
    if (type !in listOf("series", "tv")) return
    if (_uiState.value.episodesAll.isNotEmpty() || _uiState.value.isLoadingEpisodes) return

    scope.launch {
        _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }

        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                val allEpisodes = result.data.videos
                    .sortedWith(
                        compareBy<Video> { it.season ?: Int.MAX_VALUE }
                            .thenBy { it.episode ?: Int.MAX_VALUE }
                            .thenBy { it.title }
                    )

                applyMetaDetails(result.data)

                val seasons = allEpisodes
                    .mapNotNull { it.season }
                    .distinct()
                    .sorted()

                val preferredSeason = when {
                    currentSeason != null && seasons.contains(currentSeason) -> currentSeason
                    initialSeason != null && seasons.contains(initialSeason) -> initialSeason
                    else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
                }

                val selectedSeason = preferredSeason ?: 1
                val episodesForSeason = allEpisodes
                    .filter { (it.season ?: -1) == selectedSeason }
                    .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

                _uiState.update {
                    it.copy(
                        isLoadingEpisodes = false,
                        episodesAll = allEpisodes,
                        episodesAvailableSeasons = seasons,
                        episodesSelectedSeason = selectedSeason,
                        episodes = episodesForSeason,
                        episodesError = null
                    )
                }
            }

            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = result.message) }
            }

            NetworkResult.Loading -> {
                
            }
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video, forceRefresh: Boolean) {
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = "Missing content type") }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasCachedPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasCachedPayload) {
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsJob?.cancel()
    episodeStreamsJob = scope.launch {
        episodeStreamsCacheRequestKey = requestKey
        val previousAddonFilter = _uiState.value.episodeSelectedAddonFilter
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeAllStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeAllStreams,
                episodeSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.episodeSelectedAddonFilter,
                episodeFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeFilteredStreams,
                episodeAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.episodeAvailableAddons,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first()
        val installedAddonOrder = installedAddons.map { it.displayName }

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    val selectedAddon = previousAddonFilter?.takeIf { it in availableAddons }
                    val filteredStreams = if (selectedAddon == null) {
                        allStreams
                    } else {
                        allStreams.filter { it.addonName == selectedAddon }
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeSelectedAddonFilter = selectedAddon,
                            episodeFilteredStreams = filteredStreams,
                            episodeAvailableAddons = availableAddons,
                            episodeStreamsError = null
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetVideoId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetVideoId },
        state.episodesAll.firstOrNull { it.id == targetVideoId },
        state.episodes.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        },
        state.episodesAll.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(stream: Stream, forcedTargetVideo: Video? = null) {
    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = "Invalid stream URL") }
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        .filterKeys { !it.equals("Range", ignoreCase = true) }
    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    currentStreamUrl = url
    currentHeaders = newHeaders
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentFilename = stream.behaviorHints?.filename
        ?: url.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    hasRetriedCurrentStreamAfter416 = false
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    refreshScrobbleItem()

    lastSavedPosition = 0L
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName, 
            currentStreamUrl = url,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            
            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,
            
            activeSkipInterval = null,
            skipIntervalDismissed = false,
            showNextEpisodeCard = false,
            nextEpisodeCardDismissed = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)

    updateEpisodeDescription()
    refreshSubtitlesForCurrentEpisode()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null

    
    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)

    _exoPlayer?.let { player ->
        try {
            player.setMediaSource(mediaSourceFactory.createMediaSource(url, newHeaders))
            player.prepare()
            player.playWhenReady = true
            startFrameRateProbe(
                url,
                newHeaders,
                _uiState.value.frameRateMatchingMode != FrameRateMatchingMode.OFF
            )
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Failed to play selected stream") }
            return
        }
    } ?: run {
        initializePlayer(url, newHeaders)
    }

    loadSavedProgressFor(currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal fun PlayerRuntimeController.playNextEpisode() {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return

    val state = _uiState.value
    if (state.nextEpisode?.hasAired == false) {
        return
    }
    if (state.nextEpisodeAutoPlaySearching || state.nextEpisodeAutoPlayCountdownSec != null) {
        return
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            _uiState.update {
                it.copy(
                    showNextEpisodeCard = true,
                    nextEpisodeCardDismissed = false,
                    nextEpisodeAutoPlaySearching = true,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }

            val installedAddons = addonRepository.getInstalledAddons().first()
            val installedAddonOrder = installedAddons.map { it.displayName }
            var selectedStream: Stream? = null
            val terminalResult = streamRepository.getStreamsFromAllAddons(
                type = type,
                videoId = nextVideo.id,
                season = nextVideo.season,
                episode = nextVideo.episode
            ).firstOrNull { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                        val allStreams = orderedStreams.flatMap { it.streams }
                        selectedStream = StreamAutoPlaySelector.selectAutoPlayStream(
                            streams = allStreams,
                            mode = playerSettings.streamAutoPlayMode,
                            regexPattern = playerSettings.streamAutoPlayRegex,
                            source = playerSettings.streamAutoPlaySource,
                            installedAddonNames = installedAddonOrder.toSet(),
                            selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                            selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                            preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                                currentStreamBingeGroup
                            } else {
                                null
                            }
                        )
                        selectedStream != null
                    }
                    is NetworkResult.Error -> true
                    NetworkResult.Loading -> false
                }
            }

            val streamToPlay = selectedStream
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                for (remaining in 3 downTo 1) {
                    _uiState.update {
                        it.copy(
                            showNextEpisodeCard = true,
                            nextEpisodeCardDismissed = false,
                            nextEpisodeAutoPlaySearching = false,
                            nextEpisodeAutoPlaySourceName = sourceName,
                            nextEpisodeAutoPlayCountdownSec = remaining
                        )
                    }
                    delay(1000)
                }
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                switchToEpisodeStream(stream = streamToPlay, forcedTargetVideo = nextVideo)
            } else {
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = terminalResult is NetworkResult.Error
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    showNextEpisodeCard = false,
                    nextEpisodeCardDismissed = true,
                    nextEpisodeAutoPlaySearching = false,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}

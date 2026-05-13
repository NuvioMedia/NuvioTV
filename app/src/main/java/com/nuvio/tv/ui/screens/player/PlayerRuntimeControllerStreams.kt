package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.local.toTrackPreference
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Hard ceiling for next-episode stream search to prevent hanging forever. */
private const val NEXT_EPISODE_HARD_TIMEOUT_MS = 120_000L

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
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
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
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
                        val selectedAddon = it.sourceSelectedAddonFilter?.takeIf { selected ->
                            selected in availableAddons
                        }
                        val filteredStreams = if (selectedAddon == null) {
                            allStreams
                        } else {
                            allStreams.filter { stream -> stream.addonName == selectedAddon }
                        }
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = allStreams,
                            sourceSelectedAddonFilter = selectedAddon,
                            sourceFilteredStreams = filteredStreams,
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
            val mediaType = when (type.lowercase()) {
                "series", "tv", "show" -> "tv"
                else -> type.lowercase()
            }
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val scrapers = pluginManager.enabledScrapers.first()
                .filter { it.supportsType(mediaType) }
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

private fun PlayerRuntimeController.applySelectedStreamState(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    val (cleanUrl, mergedHeaders) = PlayerMediaSourceFactory.extractUserInfoAuth(url, headers)
    currentStreamUrl = cleanUrl
    currentHeaders = mergedHeaders
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    currentStreamResponseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = cleanUrl,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    applyStreamMetadata(stream)
}

/**
 * Apply stream metadata that is common to both HTTP and torrent paths.
 * Ensures binge-group, addon info, and video hints are always set regardless
 * of stream type — critical for next-episode binge matching.
 */
private fun PlayerRuntimeController.applyStreamMetadata(stream: Stream) {
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentAddonName = stream.addonName
    currentAddonLogo = stream.addonLogo
    currentStreamDescription = stream.description
    currentVideoCodec = null
    currentVideoWidth = null
    currentVideoHeight = null
    currentVideoBitrate = null
}

private fun PlayerRuntimeController.persistSelectedStreamForReuse(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = headers,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            bingeGroup = stream.behaviorHints?.bingeGroup
        )
    }
}

private fun PlayerRuntimeController.persistTorrentStreamForReuse(stream: Stream) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val infoHash = stream.infoHash ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = "",
            streamName = streamName,
            headers = emptyMap(),
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            infoHash = infoHash,
            fileIdx = stream.fileIdx,
            sources = stream.sources,
            bingeGroup = stream.behaviorHints?.bingeGroup
        )
    }
}

private fun PlayerRuntimeController.openExternalStreamInBrowser(
    stream: Stream,
    fromEpisodePanel: Boolean
): Boolean {
    if (!stream.isExternal()) return false

    val externalUrl = stream.getStreamUrl()
    if (externalUrl.isNullOrBlank()) {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            } else {
                it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            }
        }
        return true
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching {
        context.startActivity(browserIntent)
    }.onSuccess {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(
                    showEpisodesPanel = false,
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false,
                    episodeStreamsError = null
                )
            } else {
                it.copy(
                    showSourcesPanel = false,
                    isLoadingSourceStreams = false,
                    sourceStreamsError = null
                )
            }
        }
    }.onFailure { error ->
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            } else {
                it.copy(sourceStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            }
        }
    }

    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(stream: Stream) {
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = false)) {
        return
    }

    // Torrent streams: delegate to torrent-aware path
    if (stream.isTorrent()) {
        val infoHash = stream.infoHash ?: return
        stopTorrentStream()
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlayJob = null
        flushPlaybackSnapshotForSwitchOrExit()
        resetLoadingOverlayForNewStream()
        releasePlayer(flushPlaybackState = false)
        hasRetriedCurrentStreamAfter416 = false
        errorRetryCount = 0
        subtitleDisabledByPersistedPreference = false
        subtitleAddonRestoredByPersistedPreference = false
        pendingRestoredAddonSubtitle = null
        lastSavedPosition = 0L
        _uiState.update {
            it.copy(
                isBuffering = true,
                error = null,
                currentStreamName = stream.name ?: stream.addonName,
                currentStreamUrl = "",
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1,
                showSourcesPanel = false,
                isLoadingSourceStreams = false,
                sourceStreamsError = null,
                isTorrentStream = true
            )
        }
        applyStreamMetadata(stream)
        currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
        showStreamSourceIndicator(stream)
        resetPostPlayOverlayState(clearEpisode = false)
        launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
        persistTorrentStreamForReuse(stream)
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    persistSelectedStreamForReuse(stream = stream, url = url, headers = newHeaders)
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L

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
            sourceStreamsError = null,
            isTorrentStream = false
        )
    }
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)

    preparePlaybackBeforeStart(
        url = url,
        headers = newHeaders,
        loadSavedProgress = true
    )
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
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_missing_content_type)) }
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

internal fun PlayerRuntimeController.switchToEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video? = null,
    isAutoPlay: Boolean = false
) {
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = true)) {
        return
    }

    // Torrent streams: delegate to torrent-aware path
    if (stream.isTorrent()) {
        val infoHash = stream.infoHash ?: return
        consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
            currentCount = consecutiveAutoPlayCount,
            isAutoPlay = isAutoPlay,
        )
        stopTorrentStream()
        switchToEpisodeStreamCommon(stream, forcedTargetVideo)
        launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
        persistTorrentStreamForReuse(stream)
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay,
    )

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )
    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    persistSelectedStreamForReuse(stream = stream, url = url, headers = newHeaders)
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
    lastSavedPosition = 0L

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
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
            isTorrentStream = false,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)

    updateEpisodeDescription()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)

    preparePlaybackBeforeStart(
        url = url,
        headers = newHeaders,
        loadSavedProgress = true
    )
}

/**
 * Shared episode stream setup used by both torrent and HTTP episode switching.
 */
private fun PlayerRuntimeController.switchToEpisodeStreamCommon(
    stream: Stream,
    forcedTargetVideo: Video? = null
) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    flushPlaybackSnapshotForSwitchOrExit()

    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename

    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasRetriedCurrentStreamAfter416 = false
    errorRetryCount = 0
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
    lastSavedPosition = 0L

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = true,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)
    updateEpisodeDescription()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal fun PlayerRuntimeController.playNextEpisode(userInitiated: Boolean = false) {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return

    val state = _uiState.value
    val nextInfo = state.nextEpisode ?: return
    if (!nextInfo.hasAired) {
        return
    }
    val activeAutoPlay = state.postPlayMode as? PostPlayMode.AutoPlay
    if (activeAutoPlay != null &&
        (activeAutoPlay.searching || activeAutoPlay.countdownSec != null)
    ) {
        return
    }

    val episodeForMode = state.nextEpisode ?: nextInfo
    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = episodeForMode,
                searching = true,
            ),
            postPlayDismissedForCurrentEpisode = false,
        )
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val shouldAutoSelectInManualMode =
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                    (
                        playerSettings.streamAutoPlayNextEpisodeEnabled ||
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                        )
            val bingeGroupOnlyManualMode =
                shouldAutoSelectInManualMode &&
                    !playerSettings.streamAutoPlayNextEpisodeEnabled &&
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
            if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            val installedAddons = addonRepository.getInstalledAddons().first()
            val installedAddonOrder = installedAddons.map { it.displayName }
            val effectiveMode = if (shouldAutoSelectInManualMode) {
                StreamAutoPlayMode.FIRST_STREAM
            } else {
                playerSettings.streamAutoPlayMode
            }
            val effectiveSource = if (shouldAutoSelectInManualMode) {
                StreamAutoPlaySource.ALL_SOURCES
            } else {
                playerSettings.streamAutoPlaySource
            }
            val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedAddons
            }
            val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedPlugins
            }
            val effectiveRegex = if (shouldAutoSelectInManualMode) {
                ""
            } else {
                playerSettings.streamAutoPlayRegex
            }
            var selectedStream: Stream? = null
            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var lastError: NetworkResult.Error? = null

            fun trySelectStream(data: List<AddonStreams>): Stream? {
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder)
                val allStreams = orderedStreams.flatMap { it.streams }
                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedAddonOrder.toSet(),
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                        currentStreamBingeGroup
                    } else {
                        null
                    },
                    preferBingeGroupInSelection = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode,
                    bingeGroupOnly = bingeGroupOnlyManualMode
                )
            }

            val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds

            val innerJob = launch {
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = nextVideo.id,
                    season = nextVideo.season,
                    episode = nextVideo.episode
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            lastSuccessData = result.data
                            if (timeoutElapsed && !autoSelectTriggered) {
                                val candidate = trySelectStream(result.data)
                                if (candidate != null) {
                                    autoSelectTriggered = true
                                    selectedStream = candidate
                                }
                            }
                        }
                        is NetworkResult.Error -> lastError = result
                        NetworkResult.Loading -> Unit
                    }
                }
                if (!autoSelectTriggered) {
                    autoSelectTriggered = true
                    lastSuccessData?.let { selectedStream = trySelectStream(it) }
                }
            }

            val timeoutMs = timeoutSeconds * 1_000L
            if (timeoutMs > 0L && timeoutSeconds < 11) {
                delay(timeoutMs)
                timeoutElapsed = true
                if (!autoSelectTriggered && lastSuccessData != null) {
                    val candidate = trySelectStream(lastSuccessData!!)
                    if (candidate != null) {
                        autoSelectTriggered = true
                        selectedStream = candidate
                    }
                }
                if (selectedStream != null) {
                    // Found a match within timeout - use it.
                    innerJob.cancel()
                } else if (lastSuccessData != null) {
                    // Streams arrived but no match (e.g. binge group not found).
                    // Respect the original timeout - don't wait further.
                    innerJob.cancel()
                    autoSelectTriggered = true
                } else {
                    // No addon responded yet - wait for the first result with
                    // a hard ceiling so we never hang indefinitely.
                    val completed = withTimeoutOrNull(timeoutMs) { innerJob.join() }
                    if (completed == null) {
                        innerJob.cancel()
                        // One last attempt with whatever data arrived
                        if (!autoSelectTriggered && lastSuccessData != null) {
                            selectedStream = trySelectStream(lastSuccessData!!)
                        }
                    }
                }
            } else {
                // "Unlimited" mode - still apply a hard ceiling to prevent
                // hanging forever if a scraper/addon never responds.
                val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { innerJob.join() }
                if (completed == null) {
                    innerJob.cancel()
                    if (!autoSelectTriggered && lastSuccessData != null) {
                        selectedStream = trySelectStream(lastSuccessData!!)
                    }
                }
            }

            val streamToPlay = selectedStream
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                for (remaining in 3 downTo 1) {
                    _uiState.update { current ->
                        val episodeForMode = current.nextEpisode ?: nextInfo
                        current.copy(
                            postPlayMode = PostPlayMode.AutoPlay(
                                nextEpisode = episodeForMode,
                                searching = false,
                                sourceName = sourceName,
                                countdownSec = remaining,
                            ),
                            postPlayDismissedForCurrentEpisode = false,
                        )
                    }
                    delay(1000)
                }
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                switchToEpisodeStream(
                    stream = streamToPlay,
                    forcedTargetVideo = nextVideo,
                    isAutoPlay = !userInitiated
                )
            } else {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = lastError != null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}

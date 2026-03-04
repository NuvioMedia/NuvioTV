package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return

    scope.launch {
        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                applyMetaDetails(result.data)
            }
            is NetworkResult.Error -> {
                
            }
            NetworkResult.Loading -> {
                
            }
        }
    }
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    val description = resolveDescription(meta)

    _uiState.update { state ->
        state.copy(
            description = description ?: state.description,
            castMembers = if (meta.castMembers.isNotEmpty()) meta.castMembers else state.castMembers
        )
    }
    recomputeNextEpisode(resetVisibility = false)
}

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    if (type in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.season == currentSeason && video.episode == currentEpisode
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }

    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = metaVideos.firstOrNull { video ->
        video.season == currentSeason && video.episode == currentEpisode
    }?.overview

    if (!overview.isNullOrBlank()) {
        _uiState.update { it.copy(description = overview) }
    }
}

internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        nextEpisodeVideo = null
        _uiState.update {
            it.copy(
                nextEpisode = null,
                showNextEpisodeCard = false,
                nextEpisodeCardDismissed = false,
                nextEpisodeAutoPlaySearching = false,
                nextEpisodeAutoPlaySourceName = null,
                nextEpisodeAutoPlayCountdownSec = null
            )
        }
        return
    }

    val season = currentSeason
    val episode = currentEpisode
    if (season == null || episode == null) {
        nextEpisodeVideo = null
        _uiState.update {
            it.copy(
                nextEpisode = null,
                showNextEpisodeCard = false,
                nextEpisodeCardDismissed = false,
                nextEpisodeAutoPlaySearching = false,
                nextEpisodeAutoPlaySourceName = null,
                nextEpisodeAutoPlayCountdownSec = null
            )
        }
        return
    }

    val resolvedNext = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = metaVideos,
        currentSeason = season,
        currentEpisode = episode
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        _uiState.update {
            it.copy(
                nextEpisode = null,
                showNextEpisodeCard = false,
                nextEpisodeCardDismissed = false,
                nextEpisodeAutoPlaySearching = false,
                nextEpisodeAutoPlaySourceName = null,
                nextEpisodeAutoPlayCountdownSec = null
            )
        }
        return
    }

    val hasAired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: return,
        episode = resolvedNext.episode ?: return,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        hasAired = hasAired,
        unairedMessage = if (hasAired) {
            null
        } else {
            context.getString(com.nuvio.tv.R.string.next_episode_not_aired_yet)
        }
    )

    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        state.copy(
            nextEpisode = nextInfo,
            showNextEpisodeCard = if (shouldResetVisibility) false else state.showNextEpisodeCard,
            nextEpisodeCardDismissed = if (shouldResetVisibility) false else state.nextEpisodeCardDismissed
        )
    }
}

internal fun PlayerRuntimeController.resetNextEpisodeCardState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            showNextEpisodeCard = false,
            nextEpisodeCardDismissed = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

internal fun PlayerRuntimeController.evaluateNextEpisodeCardVisibility(positionMs: Long, durationMs: Long) {
    if (!hasRenderedFirstFrame) return

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.showNextEpisodeCard) {
            _uiState.update { it.copy(showNextEpisodeCard = false) }
        }
        return
    }
    if (state.showNextEpisodeCard || state.nextEpisodeCardDismissed) return

    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (shouldShow) {
        _uiState.update { it.copy(showNextEpisodeCard = true) }
        if (
            state.nextEpisode.hasAired &&
            streamAutoPlayNextEpisodeEnabledSetting
        ) {
            playNextEpisode()
        }
    }
}

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank()) return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    if (skipIntervals.isEmpty()) {
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null) }
        }
        return
    }

    val positionSec = positionMs / 1000.0
    val active = skipIntervals.find { interval ->
        positionSec >= interval.startTime && positionSec < (interval.endTime - 0.5)
    }

    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        
        if (currentActive == null || active.type != currentActive.type || active.startTime != currentActive.startTime) {
            lastActiveSkipType = active.type
            _uiState.update { it.copy(activeSkipInterval = active, skipIntervalDismissed = false) }
        }
    } else if (currentActive != null) {
        
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}

internal fun PlayerRuntimeController.tryShowParentalGuide() {
    val state = _uiState.value
    if (!state.parentalGuideHasShown && state.parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        _uiState.update { it.copy(showParentalGuide = true, parentalGuideHasShown = true) }
    }
}

internal fun PlayerRuntimeController.fetchParentalGuide(id: String?, type: String?, season: Int?, episode: Int?) {
    if (id.isNullOrBlank()) return
    
    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return

    scope.launch {
        val response = if (type in listOf("series", "tv") && season != null && episode != null) {
            parentalGuideRepository.getTVGuide(imdbId, season, episode)
        } else {
            parentalGuideRepository.getMovieGuide(imdbId)
        }

        if (response?.parentalGuide != null) {
            val guide = response.parentalGuide
            val labels = mapOf(
                "nudity" to context.getString(R.string.parental_nudity),
                "violence" to context.getString(R.string.parental_violence),
                "profanity" to context.getString(R.string.parental_profanity),
                "alcohol" to context.getString(R.string.parental_alcohol),
                "frightening" to context.getString(R.string.parental_frightening)
            )
            val severityOrder = mapOf(
                "severe" to 0, "moderate" to 1, "mild" to 2
            )

            val entries = listOfNotNull(
                guide.nudity?.let { "nudity" to it },
                guide.violence?.let { "violence" to it },
                guide.profanity?.let { "profanity" to it },
                guide.alcohol?.let { "alcohol" to it },
                guide.frightening?.let { "frightening" to it }
            )

            val warnings = entries
                .filter { it.second.lowercase() != "none" }
                .sortedBy { severityOrder[it.second.lowercase()] ?: 3 }
                .map {
                    val localizedSeverity = when (it.second.lowercase()) {
                        "severe" -> context.getString(R.string.parental_severity_severe)
                        "moderate" -> context.getString(R.string.parental_severity_moderate)
                        "mild" -> context.getString(R.string.parental_severity_mild)
                        else -> it.second
                    }
                    ParentalWarning(label = labels[it.first] ?: it.first, severity = localizedSeverity)
                }
                .take(5)

            _uiState.update {
                it.copy(
                    parentalWarnings = warnings,
                    showParentalGuide = false,
                    parentalGuideHasShown = false
                )
            }

            
            if (_uiState.value.isPlaying) {
                tryShowParentalGuide()
            }
        }
    }
}

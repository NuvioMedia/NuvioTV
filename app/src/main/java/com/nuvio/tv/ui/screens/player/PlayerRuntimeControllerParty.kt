package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.R
import com.nuvio.tv.core.party.OwnSeek
import com.nuvio.tv.core.party.PartyCorrection
import com.nuvio.tv.core.party.PartyEvent
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyMemberStatus
import com.nuvio.tv.core.party.PartyPlayback
import com.nuvio.tv.core.party.PartySource
import com.nuvio.tv.core.party.PartyStream
import com.nuvio.tv.core.party.PartySync
import com.nuvio.tv.core.party.isShareableStreamUrl
import com.nuvio.tv.core.party.partyMediaKey
import com.nuvio.tv.ui.screens.party.messageRes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal val PlayerRuntimeController.partyKey: String
    get() = partyMediaKey(contentType.orEmpty(), contentId.orEmpty(), currentSeason, currentEpisode)

internal val PlayerRuntimeController.canUseParty: Boolean
    get() = !contentId.isNullOrBlank() && !contentType.isNullOrBlank() && !contentType.equals("cloud", ignoreCase = true)

internal val PlayerRuntimeController.isPartySynced: Boolean
    get() = party.state.value.party?.media?.key == partyKey

internal val PlayerRuntimeController.isPartyControlLocked: Boolean
    get() = party.state.value.party?.let { current -> !current.canControl && current.media.key == partyKey } == true

internal fun PlayerRuntimeController.partyBlocksControl(): Boolean {
    if (!isPartyControlLocked) return false
    showPartyMessage(context.getString(R.string.party_host_only_note))
    return true
}

internal fun PlayerRuntimeController.showPartyMessage(text: String) {
    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update { it.copy(showStreamSourceIndicator = true, streamSourceIndicatorText = text) }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.showPartyPanel() {
    _uiState.update {
        it.copy(
            showPartyPanel = true,
            showControls = true,
            showMoreDialog = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showSourcesPanel = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
        )
    }
}

internal fun PlayerRuntimeController.dismissPartyPanel() {
    _uiState.update { it.copy(showPartyPanel = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.toggleOwnPlayback() {
    when {
        hasActivePlayIntent() -> {
            setPartyBehind(true)
            applyPartyPaused(paused = true, force = true)
        }
        _uiState.value.partyBehind -> applyPartyPaused(paused = false, force = true)
        else -> partyBlocksControl()
    }
    showControlsTemporarily()
}

internal fun PlayerRuntimeController.ownSeekTarget(targetMs: Long): Long? {
    val playback = party.playback.value ?: return targetMs
    val partyPositionMs = PartySync.expectedPositionMs(playback, party.nowMs(), currentPlaybackDurationMs().coerceAtLeast(0L))
    return when (val seek = PartySync.ownSeek(targetMs, partyPositionMs, _uiState.value.partyBehind)) {
        is OwnSeek.Behind -> {
            setPartyBehind(true)
            seek.positionMs
        }
        OwnSeek.CatchUp -> {
            setPartyBehind(false)
            null
        }
        OwnSeek.Blocked -> {
            partyBlocksControl()
            null
        }
    }
}

internal fun PlayerRuntimeController.catchUpParty() {
    setPartyBehind(false)
}

internal fun PlayerRuntimeController.currentPartyMedia(): PartyMedia = PartyMedia(
    type = contentType.orEmpty(),
    metaId = contentId.orEmpty(),
    metaType = contentType.orEmpty(),
    videoId = currentVideoId,
    season = currentSeason,
    episode = currentEpisode,
    title = contentName?.takeIf { it.isNotBlank() } ?: title,
    episodeTitle = currentEpisodeTitle,
    description = _uiState.value.description?.take(DESCRIPTION_LIMIT),
    poster = poster,
    background = backdrop,
    logo = logo,
    source = PartySource(
        infoHash = currentInfoHash,
        fileIdx = currentFileIdx,
        filename = currentFilename,
        videoSize = currentVideoSize,
        bingeGroup = currentStreamBingeGroup,
        name = _uiState.value.currentStreamName,
    ),
)

internal fun PlayerRuntimeController.currentPartyStream(): PartyStream? {
    if (isTorrentStream || _uiState.value.isServerStream) return null
    val url = currentStreamUrl
    if (!isShareableStreamUrl(url)) return null
    return PartyStream(
        mediaKey = partyKey,
        url = url,
        headers = currentHeaders,
        responseHeaders = currentStreamResponseHeaders,
        name = _uiState.value.currentStreamName,
        description = currentStreamDescription,
        addonName = currentAddonName.orEmpty(),
        addonLogo = currentAddonLogo,
        filename = currentFilename,
        videoSize = currentVideoSize,
        bingeGroup = currentStreamBingeGroup,
    )
}

internal fun PlayerRuntimeController.partyBaseRate(): Float =
    party.playback.value?.rate ?: _uiState.value.playbackSpeed

internal fun PlayerRuntimeController.publishPartyPlayback(
    positionMs: Long = currentPlaybackPositionMs() ?: 0L,
    rate: Float = partyBaseRate(),
    paused: Boolean = !hasActivePlayIntent(),
) {
    if (!isPartySynced) return
    party.publishPlayback(paused = paused, positionMs = positionMs, rate = rate)
}

internal fun PlayerRuntimeController.partyResumePositionMs(): Long? {
    if (!isPartySynced) return null
    val playback = party.playback.value ?: return null
    if (playback.mediaKey != null && playback.mediaKey != partyKey) return null
    return PartySync.expectedPositionMs(playback, party.nowMs())
}

internal fun PlayerRuntimeController.startParty() {
    scope.launch {
        val error = party.create(currentPartyMedia()) ?: return@launch
        showPartyMessage(context.getString(error.messageRes))
    }
}

internal fun PlayerRuntimeController.leavePartyPlayback() {
    val current = party.state.value.party ?: return
    val playback = party.playback.value
    val othersWatching = current.presence.values.any {
        it.memberId != current.memberId && it.mediaKey == current.media.key && it.status != PartyMemberStatus.Browsing
    }
    if (current.media.key == partyKey && playback != null && !playback.paused && !othersWatching) {
        party.publishPlayback(paused = true, positionMs = currentPlaybackPositionMs() ?: playback.positionMs, rate = playback.rate)
    }
    party.setLocalStatus(PartyMemberStatus.Browsing, null)
}

internal fun PlayerRuntimeController.bindParty() {
    scope.launch {
        party.state
            .map { it.party?.id }
            .distinctUntilChanged()
            .collectLatest { partyId ->
                if (partyId == null) {
                    clearPartyNudge()
                    setPartyBehind(false)
                } else {
                    runPartySync()
                }
            }
    }
    scope.launch {
        party.events.collect { event ->
            if (event is PartyEvent.MediaChanged) handlePartyMediaChange(event.media)
        }
    }
}

private fun PlayerRuntimeController.setPartyBehind(behind: Boolean) {
    if (_uiState.value.partyBehind != behind) {
        _uiState.update { it.copy(partyBehind = behind, partyBehindMs = it.partyBehindMs.takeIf { behind }) }
    }
}

private fun PlayerRuntimeController.applyPartyPaused(paused: Boolean, force: Boolean) {
    if (!force && hasActivePlayIntent() == !paused) return
    if (paused) {
        userPausedManually = true
        setPlaybackPaused(true)
        if (isUsingMpvEngine()) {
            stopProgressUpdates()
            stopWatchProgressSaving()
            emitPauseScrobbleForCurrentProgress()
        }
    } else {
        userPausedManually = false
        cancelPauseOverlay()
        setPlaybackPaused(false)
        if (isUsingMpvEngine()) {
            startProgressUpdates()
            startWatchProgressSaving()
            emitScrobbleStart()
        }
    }
}

private fun PlayerRuntimeController.applyPartyRate(rate: Float) {
    setPlaybackSpeedInternal(rate)
    _uiState.update { it.copy(playbackSpeed = rate) }
}

private fun PlayerRuntimeController.clearPartyNudge(playback: PartyPlayback? = party.playback.value) {
    if (partyRateNudge == null) return
    partyRateNudge = null
    setPlaybackSpeedInternal(playback?.rate ?: _uiState.value.playbackSpeed)
}

private fun PlayerRuntimeController.handlePartyMediaChange(media: PartyMedia) {
    if (media.key == partyKey) return
    val next = _uiState.value.nextEpisode
    if (media.metaId == contentId && next != null && next.hasAired && next.season == media.season && next.episode == media.episode) {
        playNextEpisode(userInitiated = true)
        return
    }
    showPartyPanel()
}

private suspend fun PlayerRuntimeController.runPartySync() {
    val clock = TimeSource.Monotonic
    var bufferingSince: TimeMark? = null
    var mismatchSince: TimeMark? = null
    var lastSeek: TimeMark? = null
    var lastCorrection: TimeMark? = null
    var lastSeq: Pair<Long, String?>? = null
    var lastStatus: Triple<PartyMemberStatus, String, Long?>? = null
    var lastShared: PartyStream? = null
    var wasInBackground = false
    var wasRendered = false
    var publishedInitial = false
    var seekLeadMs = 0L
    var measureSeekLead = false
    var wasBehind = false
    try {
        while (currentCoroutineContext().isActive) {
            delay(PARTY_TICK)
            val current = party.state.value.party ?: break
            val state = _uiState.value
            val key = partyKey

            val previousKey = partyPreviousKey
            if (previousKey != key) {
                partyPreviousKey = key
                setPartyBehind(false)
                if (previousKey != null && current.media.key == previousKey) party.publishMedia(currentPartyMedia(), positionMs = 0L)
            }

            val shared = if (current.isHost && current.sharing) currentPartyStream() else null
            if (shared != lastShared) {
                lastShared = shared
                if (current.isHost && current.sharing) party.shareStream(shared)
            }

            val resumedFromBackground = wasInBackground && !isInBackground
            wasInBackground = isInBackground
            if (resumedFromBackground) lastSeq = null
            val rendered = hasRenderedFirstFrame
            if (rendered && !wasRendered) lastSeq = null
            wasRendered = rendered
            val loading = state.isBuffering || state.showLoadingOverlay
            val behindGap = party.playback.value
                ?.takeIf { state.partyBehind && current.media.key == key }
                ?.let { playbackNow ->
                    currentPlaybackPositionMs()?.let { local ->
                        PartySync.expectedPositionMs(playbackNow, party.nowMs(), currentPlaybackDurationMs().coerceAtLeast(0L)) - local
                    }
                }
            val status = when {
                state.error != null || isInBackground -> PartyMemberStatus.Browsing
                behindGap != null -> PartyMemberStatus.Behind
                !hasRenderedFirstFrame -> PartyMemberStatus.Loading
                loading && !state.playbackEnded -> {
                    val since = bufferingSince ?: clock.markNow().also { bufferingSince = it }
                    if (since.elapsedNow() > BUFFERING_REPORT_DELAY) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
                }
                else -> {
                    bufferingSince = null
                    PartyMemberStatus.Ready
                }
            }
            val reportedStatus = Triple(
                status,
                key,
                behindGap?.takeIf { status == PartyMemberStatus.Behind }?.let(PartySync::reportedGapMs),
            )
            if (reportedStatus != lastStatus) {
                lastStatus = reportedStatus
                party.setLocalStatus(status, key, reportedStatus.third)
            }

            val positionMs = currentPlaybackPositionMs()
            if (current.media.key != key || positionMs == null || state.error != null || isInBackground) {
                clearPartyNudge()
                continue
            }
            val durationMs = currentPlaybackDurationMs().coerceAtLeast(0L)
            val playback = party.playback.value
            if (playback == null) {
                if (hasRenderedFirstFrame && !publishedInitial && current.isHost) {
                    publishedInitial = true
                    party.publishPlayback(paused = !hasActivePlayIntent(), positionMs = positionMs, rate = state.playbackSpeed)
                }
                continue
            }
            publishedInitial = true
            if (!hasRenderedFirstFrame) continue
            if (_uiState.value.partyBehind && current.canControl) setPartyBehind(false)
            if (_uiState.value.partyBehind) {
                clearPartyNudge(playback)
                mismatchSince = null
                val gap = PartySync.expectedPositionMs(playback, party.nowMs(), durationMs) - positionMs
                if (PartySync.shouldRejoin(gap, playing = hasActivePlayIntent() && !loading)) {
                    setPartyBehind(false)
                } else {
                    val shown = _uiState.value.partyBehindMs
                    if (shown == null || abs(gap - shown) >= BEHIND_DISPLAY_STEP_MS) {
                        _uiState.update { it.copy(partyBehindMs = gap.coerceAtLeast(0L)) }
                    }
                    wasBehind = true
                    continue
                }
            }
            if (wasBehind) {
                wasBehind = false
                lastSeq = null
                if (!current.canControl) showPartyMessage(context.getString(R.string.party_caught_up))
            }

            val expected = PartySync.expectedPositionMs(playback, party.nowMs(), durationMs)
            val identity = playback.seq to playback.memberId
            if (identity != lastSeq) {
                val forced = lastSeq == null
                lastSeq = identity
                applyPartyPaused(playback.paused, force = forced)
                val own = playback.memberId == current.memberId && !forced
                if ((!own || playback.paused) && abs(positionMs - expected) > PartySync.PAUSED_TOLERANCE_MS) {
                    seekPlaybackTo(if (playback.paused) expected else expected + seekLeadMs, SeekParameters.EXACT)
                    measureSeekLead = !playback.paused
                }
                lastSeek = clock.markNow()
                if (partyRateNudge == null && abs(state.playbackSpeed - playback.rate) > 0.01f) applyPartyRate(playback.rate)
                mismatchSince = null
                continue
            }

            if (loading || pendingPreviewSeekPosition != null || state.playbackEnded) {
                mismatchSince = null
                continue
            }

            val playing = hasActivePlayIntent()
            if (playing == playback.paused) {
                val since = mismatchSince ?: clock.markNow().also { mismatchSince = it }
                if (since.elapsedNow() > OUTSIDE_TOGGLE_DELAY) {
                    mismatchSince = null
                    when {
                        current.canControl -> party.publishPlayback(paused = !playing, positionMs = positionMs, rate = playback.rate)
                        playing -> applyPartyPaused(playback.paused, force = true)
                        else -> setPartyBehind(true)
                    }
                }
                continue
            }
            mismatchSince = null

            val settled = lastSeek?.let { it.elapsedNow() > SEEK_SETTLE } ?: true
            val due = lastCorrection?.let { it.elapsedNow() >= CORRECTION_INTERVAL } ?: true
            if (!settled || !due) continue
            lastCorrection = clock.markNow()
            val drift = positionMs - expected
            if (measureSeekLead && !playback.paused) {
                measureSeekLead = false
                if (abs(drift) < MAX_SEEK_LEAD_MS) seekLeadMs = (seekLeadMs - drift).coerceIn(0L, MAX_SEEK_LEAD_MS)
            }
            when (val correction = PartySync.correction(positionMs, expected, playback, partyRateNudge != null)) {
                is PartyCorrection.Seek -> {
                    clearPartyNudge(playback)
                    val target = if (playback.paused) correction.positionMs else correction.positionMs + seekLeadMs
                    seekPlaybackTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target, SeekParameters.EXACT)
                    lastSeek = clock.markNow()
                    measureSeekLead = !playback.paused
                }
                is PartyCorrection.Nudge -> {
                    if (partyRateNudge != correction.rate) {
                        partyRateNudge = correction.rate
                        setPlaybackSpeedInternal(correction.rate)
                    }
                }
                PartyCorrection.InSync -> clearPartyNudge(playback)
            }
        }
    } finally {
        clearPartyNudge()
    }
}

private val PARTY_TICK = 250.milliseconds
private val BUFFERING_REPORT_DELAY = 1_000.milliseconds
private val OUTSIDE_TOGGLE_DELAY = 1_200.milliseconds
private val SEEK_SETTLE = 2_500.milliseconds
private val CORRECTION_INTERVAL = 500.milliseconds
private const val MAX_SEEK_LEAD_MS = 3_000L
private const val BEHIND_DISPLAY_STEP_MS = 1_000L
private const val DESCRIPTION_LIMIT = 600

package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CACHED_LINK_REFRESH_POLL_COUNT = 30
private const val CACHED_LINK_REFRESH_POLL_INTERVAL_MS = 500L

/** What is known about the source behind a saved link, used to find it again among fresh streams. */
internal data class CachedSourceIdentity(
    val addonName: String?,
    val streamName: String?,
    val infoHash: String?,
    val filename: String?,
    val bingeGroup: String?
)

/**
 * Fetches the playing source again from its add-on (re-resolving debrid) and continues from the
 * saved position when its link has likely expired:
 * - a link reopened from the reuse-last-link cache, which may have expired since it was saved;
 * - a link with expiring credentials in its URL, which may already be stale when picked from
 *   cached results or may expire during playback.
 * Each failed URL is refreshed at most once.
 *
 * Returns `true` when a refresh was started and the error must not be shown yet.
 */
internal fun PlayerRuntimeController.tryRefreshExpiredLinkAfterError(errorMessage: String): Boolean {
    val failedUrl = currentStreamUrl
    if (failedUrl.isBlank() || failedUrl.startsWith("torrent://")) return false
    val fromLinkCache = failedUrl == linkCacheSourceUrl
    if (!fromLinkCache && !failedUrl.hasLikelyExpiringPlaybackCredentials()) return false
    if (failedUrl == linkRefreshAttemptedUrl) return false
    linkRefreshAttemptedUrl = failedUrl
    linkCacheSourceUrl = null

    val state = _uiState.value
    val expected = CachedSourceIdentity(
        addonName = currentAddonName,
        streamName = state.currentStreamName ?: streamName,
        infoHash = state.currentStreamInfoHash ?: navigationArgs.infoHash,
        filename = currentFilename,
        bingeGroup = currentStreamBingeGroup
    )
    val season = currentSeason
    val episode = currentEpisode
    Log.i(PlayerRuntimeController.TAG, "Link likely expired, fetching the source again from ${expected.addonName}")

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        streamCacheKey?.let { streamLinkCacheDataStore.remove(it) }
        loadSourceStreams(forceRefresh = true)

        var candidate: Stream? = null
        var polls = 0
        while (polls < CACHED_LINK_REFRESH_POLL_COUNT) {
            val sources = _uiState.value
            candidate = findCachedSourceRefreshCandidate(sources.sourceAllStreams, failedUrl, expected)
            if (candidate != null || (!sources.isLoadingSourceStreams && sources.sourceAllStreams.isNotEmpty())) break
            delay(CACHED_LINK_REFRESH_POLL_INTERVAL_MS)
            polls++
        }

        val refreshed = candidate?.let { found ->
            // Debrid links are always requested anew; a prepared or in-memory one may be the expired link.
            val unresolved = if (found.debridResolved) {
                found.copy(url = null, externalUrl = null, debridResolved = false)
            } else {
                found
            }
            val result = directDebridResolver.resolveToPlayableStream(
                stream = unresolved,
                season = season,
                episode = episode,
                forceRefresh = true
            )
            (result as? DirectDebridPlayableResult.Success)?.stream
        }?.takeIf { stream ->
            val url = stream.getStreamUrl()
            !url.isNullOrBlank() && url != failedUrl
        }

        if (refreshed == null) {
            Log.w(PlayerRuntimeController.TAG, "No fresh link was found for the failed source")
            _uiState.update {
                it.copy(
                    error = errorMessage,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
            return@launch
        }
        // Saves the fresh link for reuse and resumes from the saved position.
        switchToSourceStream(refreshed)
    }
    return true
}

internal fun findCachedSourceRefreshCandidate(
    streams: List<Stream>,
    failedUrl: String,
    expected: CachedSourceIdentity
): Stream? {
    val addonName = expected.addonName?.takeIf { it.isNotBlank() } ?: return null
    return streams
        .asSequence()
        .filter { it.addonName == addonName }
        .mapNotNull { stream ->
            val url = stream.getStreamUrl()
            val hasFreshUrl = !url.isNullOrBlank() && url != failedUrl
            val canResolve = stream.debridResolved ||
                (url.isNullOrBlank() && (stream.isDirectDebrid() || stream.needsLocalDebridResolve()))
            if (!hasFreshUrl && !canResolve) return@mapNotNull null

            var score = 100
            if (!expected.infoHash.isNullOrBlank() &&
                stream.getEffectiveInfoHash().equals(expected.infoHash, ignoreCase = true)
            ) {
                score += 60
            }
            val name = stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName
            if (!expected.streamName.isNullOrBlank() && name == expected.streamName) score += 40
            if (!expected.filename.isNullOrBlank() && stream.behaviorHints?.filename == expected.filename) score += 30
            if (!expected.bingeGroup.isNullOrBlank() && stream.behaviorHints?.bingeGroup == expected.bingeGroup) {
                score += 20
            }
            if (url?.hasLikelyExpiringPlaybackCredentials() == true) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second
}

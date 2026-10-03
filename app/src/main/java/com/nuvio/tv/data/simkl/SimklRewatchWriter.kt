package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes the rewatch a playback was confirmed to be, and reads the account back for the run it makes.
 *
 * Simkl leaves the canonical row untouched and keeps a repeat viewing as its own session, which is why
 * the write happens after the scrobble instead of on it: nothing can be recorded before the user
 * answers. The write is the one call that carries `allow_rewatch=yes` here, and it always sends an
 * episode with coordinates, so a whole-series write, which would mark every episode and lose the
 * rewatch, is not reachable from this path. A run that is already open is named by its session id, so
 * the confirmation continues that run instead of opening another one beside it.
 *
 * TV equivalent of mobile `SimklMutationRepository.recordConfirmedRewatch`, `rewatchReachedTheAccount`
 * and `refreshRewatchSessions`. Mobile has them as methods of one `object`; TV keeps them in one
 * class, because they need `SimklMutationService`, `SimklSyncRemote` and `SimklSyncRepository`.
 */
@Singleton
class SimklRewatchWriter @Inject constructor(
    private val service: SimklMutationService,
    private val remote: SimklSyncRemote,
    private val syncRepository: SimklSyncRepository
) {
    /**
     * Records the rewatch of a playback the user confirmed. Returns whether the rewatch was taken by
     * the account.
     *
     * Whether the write landed is answered by the write itself. Simkl reports an episode the history
     * already holds as `not_found`, and it answers that way every time the question is asked, because
     * the prompt only appears for a repeat viewing of something watched more than 48 hours ago on a
     * plan that allows rewatches. Reading the account back instead would be the better proof, but Simkl
     * publishes the session a while after accepting the write, so that answer arrives too late to
     * decide anything.
     *
     * Continue Watching is refreshed in the background. Once two episodes of the run are rewatched the
     * row follows, on every device, and the refresh is what makes it follow without a sync.
     */
    suspend fun recordConfirmedRewatch(
        media: TrackingMediaReference,
        watchedAtEpochMs: Long
    ): Boolean {
        val resolved = media.resolveAnimeEpisodeForSimkl()
        // The run this viewing joins, when the account already has one open. Simkl asks for the session
        // on every write after the one that opened it, so a later confirmation continues that run
        // instead of leaving the account to pick a session. The sessions are the ones the last read of
        // the account left, and the refresh below is what keeps them current.
        val runningSessionId = syncRepository.state.value.snapshot.rewatchSessions
            .activeRewatchSessionId(resolved)
        val written = runCatching {
            service.addToHistory(
                items = listOf(
                    TrackingHistoryItem(
                        media = resolved,
                        watchedAtEpochMs = watchedAtEpochMs,
                        rewatchId = runningSessionId
                    )
                ),
                allowRewatch = true
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to record confirmed Simkl rewatch: ${error.message}")
        }.isSuccess
        refreshRewatchSessions(resolved)
        if (written) return true
        // A write that came back as an error can still have landed: Simkl records a repeat viewing and
        // reports it with a status the client reads as a failure. The account is asked before the answer
        // is called a failure, and only the episode coordinates are compared, which nothing else on the
        // account can produce at this moment.
        return rewatchReachedTheAccount(resolved)
    }

    /**
     * Looks at the account for the episode a failed write was supposed to record.
     *
     * Two looks at most, because the user is waiting for the answer here: a rewatch that Simkl took
     * shows up on the sessions within seconds, and one that it refused never will.
     */
    private suspend fun rewatchReachedTheAccount(media: TrackingMediaReference): Boolean {
        val episode = media.episode ?: return false
        val seasonNumber = episode.season ?: return false
        for (waitMs in SIMKL_REWATCH_RECHECK_DELAYS_MS) {
            delay(waitMs)
            val sessions = runCatching { remote.fetchRewatchSessions() }.getOrNull() ?: continue
            if (
                sessions.holdsRewatchEpisode(media) ||
                sessions.holdsRewatchAt(seasonNumber = seasonNumber, episodeNumber = episode.number)
            ) {
                syncRepository.adoptRewatchSessions(sessions)
                return true
            }
        }
        return false
    }

    /**
     * Reads the rewatch sessions back until they carry the episode, then stores the runs they make.
     *
     * Runs in the background, because Simkl can take a while to publish a session and nobody is waiting
     * for this. The read that sees the episode is also the one that puts the run into Continue Watching,
     * so a confirmed rewatch reaches the row without a manual sync.
     */
    private fun refreshRewatchSessions(media: TrackingMediaReference) {
        scope.launch {
            var lastRead: List<SimklLibraryEntry>? = null
            var seen = false
            for (waitMs in SIMKL_REWATCH_SESSION_READ_DELAYS_MS) {
                delay(waitMs)
                val sessions = runCatching { remote.fetchRewatchSessions() }
                    .onFailure { error ->
                        Log.w(TAG, "Could not read the rewatch sessions back: ${error.message}")
                    }
                    .getOrNull() ?: continue
                lastRead = sessions
                if (sessions.holdsRewatchEpisode(media)) {
                    seen = true
                    break
                }
            }
            val last = lastRead
            if (!seen && last != null) {
                Log.i(
                    TAG,
                    "The rewatch sessions do not hold the confirmed episode yet: " +
                        "${last.count(SimklLibraryEntry::isRewatch)} session rows"
                )
            }
            last?.let { sessions -> syncRepository.adoptRewatchSessions(sessions) }
        }
    }

    /** Keeps the session refresh alive after the answer is given and its caller is gone. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "SimklRewatch"
    }
}

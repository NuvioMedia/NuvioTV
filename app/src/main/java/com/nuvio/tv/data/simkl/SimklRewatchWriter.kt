package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingMediaReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes what the user decided about a run, and reads the account back for the session it makes.
 *
 * Dropping a run is the write here: Simkl closes the session (it keeps its watched episodes and can
 * be resumed by another write), and because Continue Watching reads the account, the run leaves the
 * row on every device it is signed in on. Nothing is written before the user asks for it, so this is
 * the one path that carries `allow_rewatch=yes` outside the scrobble itself.
 *
 * The read-after-write is what makes the row follow: Simkl publishes a session state some time after
 * accepting the write, so the sessions are read back until they say what the write should have done,
 * and the last read is stored either way.
 *
 * TV equivalent of mobile `SimklMutationRepository.rewatchReachedTheAccount` and
 * `refreshRewatchSessions`; mobile has them as methods of one `object`, TV keeps them next to the
 * write that needs them.
 */
@Singleton
class SimklRewatchWriter @Inject constructor(
    private val service: SimklMutationService,
    private val remote: SimklSyncRemote,
    private val syncRepository: SimklSyncRepository
) {
    /**
     * Drops a running rewatch by closing its session. Returns whether the account took the write.
     *
     * The sessions are read back in the background: Continue Watching follows the session list, so
     * the run leaves the row once the read sees the new state.
     */
    suspend fun closeRewatchSession(media: TrackingMediaReference, rewatchId: Long): Boolean {
        val closed = runCatching {
            service.closeRewatchSession(media = media, rewatchId = rewatchId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to close the Simkl rewatch session: ${error.message}")
        }.getOrDefault(false)
        refreshRewatchSessions { sessions ->
            sessions.none { session ->
                session.rewatchId == rewatchId && session.isRunningRewatchSession()
            }
        }
        return closed
    }

    /**
     * Reads the rewatch sessions back until they say what the write should have done, then stores them.
     *
     * Runs in the background, because Simkl can take a while to publish a session change and nobody
     * is waiting for this. The last read is stored even when the condition never became true, so the
     * snapshot carries the freshest state the account offered.
     *
     * Internal because a manual mark that opened or extended a run needs the same read: the mark
     * writes history, not the session sidecar, so the row would keep the old run until a sync.
     */
    internal fun refreshRewatchSessions(settled: (List<SimklLibraryEntry>) -> Boolean) {
        scope.launch {
            val result = readRewatchSessionsUntilSettled(
                dateFrom = syncRepository.state.value.snapshot.watermark,
                delays = SIMKL_REWATCH_SESSION_READ_DELAYS_MS,
                read = { window ->
                    runCatching { remote.fetchRewatchSessions(window) }
                        .onFailure { error ->
                            Log.w(TAG, "Could not read the rewatch sessions back: ${error.message}")
                        }
                        .getOrNull()
                },
                settled = settled,
            )
            // A write the account took but did not act on looks exactly like one it never saw, and
            // only the reads tell the two apart. The line is what a report of a card that came back
            // is diagnosed from.
            if (!result.settled && result.sessions != null) {
                Log.w(TAG, "The account did not report the session change the write asked for")
            }
            result.sessions?.let { sessions -> syncRepository.adoptRewatchSessions(sessions) }
        }
    }

    /** Keeps the session refresh alive after the answer is given and its caller is gone. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "SimklRewatch"
    }
}

internal data class RewatchSessionReadResult(
    val sessions: List<SimklLibraryEntry>?,
    val settled: Boolean,
)

/**
 * Reads the rewatch sessions back until [settled] accepts them.
 *
 * The delta read is paired with the sync watermark, so it carries the items whose activity moved,
 * and it is what every wait reads: it costs one small response. A rewatch session can change without
 * moving that activity, so when no delta settled, one whole read is made at the end before giving
 * up. A whole read downloads the show and anime library with its episode dates, and reading it after
 * every unsettled delta cost up to three of them per mark. The last read is returned either way.
 */
internal suspend fun readRewatchSessionsUntilSettled(
    dateFrom: String?,
    delays: LongArray,
    read: suspend (String?) -> List<SimklLibraryEntry>?,
    settled: (List<SimklLibraryEntry>) -> Boolean,
): RewatchSessionReadResult {
    var lastRead: List<SimklLibraryEntry>? = null
    for (waitMs in delays) {
        delay(waitMs)
        val sessions = read(dateFrom) ?: continue
        lastRead = sessions
        if (settled(sessions)) return RewatchSessionReadResult(sessions = sessions, settled = true)
    }
    if (dateFrom != null) {
        read(null)?.let { whole ->
            lastRead = whole
            if (settled(whole)) return RewatchSessionReadResult(sessions = whole, settled = true)
        }
    }
    return RewatchSessionReadResult(sessions = lastRead, settled = false)
}

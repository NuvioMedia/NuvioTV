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
     */
    private fun refreshRewatchSessions(settled: (List<SimklLibraryEntry>) -> Boolean) {
        scope.launch {
            var lastRead: List<SimklLibraryEntry>? = null
            var settledSeen = false
            for (waitMs in SIMKL_REWATCH_SESSION_READ_DELAYS_MS) {
                delay(waitMs)
                val sessions = runCatching { remote.fetchRewatchSessions() }
                    .onFailure { error ->
                        Log.w(TAG, "Could not read the rewatch sessions back: ${error.message}")
                    }
                    .getOrNull() ?: continue
                lastRead = sessions
                if (settled(sessions)) {
                    settledSeen = true
                    break
                }
            }
            // A write the account took but did not act on looks exactly like one it never saw, and
            // only the reads tell the two apart. The line is what a report of a card that came back
            // is diagnosed from.
            if (!settledSeen && lastRead != null) {
                Log.w(TAG, "The account did not report the session change the write asked for")
            }
            lastRead?.let { sessions -> syncRepository.adoptRewatchSessions(sessions) }
        }
    }

    /** Keeps the session refresh alive after the answer is given and its caller is gone. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "SimklRewatch"
    }
}

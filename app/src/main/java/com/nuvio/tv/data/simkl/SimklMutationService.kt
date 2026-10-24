package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class SimklMutationService internal constructor(
    private val client: SimklApiClient,
    private val onMutationCommitted: suspend (SimklMutationReceipt) -> Unit = {}
) {
    @Inject
    constructor(client: SimklApiClient, syncRepository: SimklSyncRepository) : this(
        client,
        { receipt ->
            syncRepository.commitMutation(receipt)
            if (receipt.requiresReconciliation) {
                syncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            }
        }
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    suspend fun moveToList(
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/add-to-list",
                body = buildSimklListMutationBody(candidates, destination, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toListMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromList(items: Collection<TrackingMediaReference>): TrackingMutationResult =
        removeFromHistory(items)

    /**
     * Writes marks to the history.
     *
     * The request asks for rewatch recording when any item says it is a repeat viewing
     * ([TrackingHistoryItem.isRewatch]): that is the query flag, Simkl reads the whole request through
     * its rewatch logic with it, and without it a mark of something already watched is answered as a
     * plain add and recorded as nothing at all.
     */
    suspend fun addToHistory(
        items: Collection<TrackingHistoryItem>,
        allowRewatch: Boolean = false
    ): TrackingMutationResult {
        val candidates = items.toList().also { historyItems ->
            require(historyItems.all { item -> item.media.hasResolvableIdentity }) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val recordRewatch = allowRewatch || candidates.any(TrackingHistoryItem::isRewatch)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = if (recordRewatch) SIMKL_ALLOW_REWATCH_QUERY else emptyMap(),
                body = buildSimklHistoryMutationBody(
                    candidates,
                    // The per-item flag is what the writer sets; the request-level one is for a caller
                    // that marks a whole batch, and it is never needed to record a repeat alongside a
                    // first watch.
                    isRewatch = allowRewatch,
                    json = json
                ),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
                // A repeat viewing of an episode the history already holds is answered with the same
                // conflict a stop scrobble gets, and behind both is a session that was opened. Reading
                // it as a failure is what made a recorded rewatch report an error.
                scrobbleStopConflictIsSuccess = recordRewatch
            )
        )
        val receipt = response.toHistoryMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    /**
     * Moves a running rewatch session to `closed`, which is how a run is dropped.
     *
     * The transition is documented on Simkl's rewatches guide: the session keeps its watched
     * episodes and can be resumed by another write, so dropping a run never loses history. Nothing
     * is committed locally from the answer: what the account keeps is read back from the sessions by
     * the caller, which is also what Continue Watching reads.
     */
    suspend fun closeRewatchSession(
        media: TrackingMediaReference,
        rewatchId: Long
    ): Boolean {
        require(media.hasResolvableIdentity) { "Simkl mutation requires a media ID or title for the item" }
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = SIMKL_ALLOW_REWATCH_QUERY,
                body = buildSimklRewatchCloseBody(media, rewatchId, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        return response.status in 200..299 || response.isSoftSuccess
    }

    suspend fun removeFromHistory(items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history/remove",
                body = buildSimklHistoryRemovalBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toHistoryRemovalReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    /**
     * Removes one episode from a named rewatch session, leaving the canonical watch in place.
     *
     * The removal endpoint accepts the session id next to the episode coordinates; a request without
     * it addresses the episode's canonical watch instead.
     */
    suspend fun removeRewatchFromHistory(
        items: Collection<TrackingMediaReference>,
        rewatchId: Long
    ): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history/remove",
                body = buildSimklRewatchRemovalBody(candidates, rewatchId, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toHistoryRemovalReceipt(candidates, json, rewatchId)
        onMutationCommitted(receipt)
        return receipt.result
    }

    /**
     * Reports one playback event to Simkl.
     *
     * The call carries [completionThresholdPercent] as `min_progress`, so the account judges the stop
     * by the same number this client does: at or above it the item is marked watched, below it Simkl
     * keeps a resumable pause, and a later start only completes the session it replaces once that
     * session reached the number. There is no separate pause call for a low stop any more.
     */
    internal suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
        recordRewatch: Boolean = false,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT
    ): SimklScrobbleResult {
        require(event.media.hasResolvableIdentity) { "Simkl scrobble requires a media ID or title" }
        require(event.media.kind == TrackingMediaKind.MOVIE || event.media.episode != null) {
            "Simkl series scrobble requires an episode"
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation request action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
        val response = try {
            client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/scrobble/${action.wireValue}",
                    query = simklScrobbleQuery(
                        completionThresholdPercent = completionThresholdPercent,
                        recordRewatch = recordRewatch
                    ),
                    body = buildSimklScrobbleBody(event, json),
                    retryPolicy = SimklRetryPolicy.NEVER,
                    scrobbleStopConflictIsSuccess = action == TrackingScrobbleAction.STOP
                )
            )
        } catch (error: Throwable) {
            Log.e(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl mutation failed action=${action.wireValue} " +
                    "error=${error.javaClass.simpleName}:${error.message} ${event.scrobbleDiagnosticSummary()}",
                error
            )
            throw error
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation response action=${action.wireValue} status=${response.status} " +
                "softSuccess=${response.isSoftSuccess} ${event.scrobbleDiagnosticSummary()}"
        )
        return response.toSimklScrobbleResult(
            requestedAction = action,
            event = event,
            json = json,
            completionThresholdPercent = completionThresholdPercent
        )
    }

    private fun Collection<TrackingMediaReference>.validated(): List<TrackingMediaReference> =
        toList().also { candidates ->
            require(candidates.all(TrackingMediaReference::hasResolvableIdentity)) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
}

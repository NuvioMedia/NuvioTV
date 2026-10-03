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
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = if (allowRewatch) SIMKL_ALLOW_REWATCH_QUERY else emptyMap(),
                body = buildSimklHistoryMutationBody(
                    candidates,
                    isRewatch = allowRewatch,
                    json = json
                ),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
                // A repeat viewing of an episode the history already holds is answered with the same
                // conflict a stop scrobble gets, and behind both is a session that was opened. Reading
                // it as a failure is what made a recorded rewatch report an error.
                scrobbleStopConflictIsSuccess = allowRewatch
            )
        )
        val receipt = response.toHistoryMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
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
                    query = if (recordRewatch) SIMKL_ALLOW_REWATCH_QUERY else emptyMap(),
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

package com.nuvio.tv.core.tracking

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingScrobbleCoordinator @Inject constructor(
    private val providerRegistry: TrackingProviderRegistry
) {
    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> {
        val scrobblers = providerRegistry.connectedScrobblers()
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "coordinator dispatch action=${action.wireValue} providers=" +
                scrobblers.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                " ${event.scrobbleDiagnosticSummary()}"
        )
        return dispatch(
            scrobblers = scrobblers,
            action = action,
            event = event
        ).also { failures ->
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "coordinator complete action=${action.wireValue} failures=" +
                    failures.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                    " ${event.scrobbleDiagnosticSummary()}"
            )
        }
    }

    suspend fun scrobbleSeek(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchSeek(
        scrobblers = providerRegistry.connectedScrobblers(),
        action = action,
        event = event
    )

    /**
     * Gives the connected trackers a chance to prepare the user before the player starts. A tracker
     * that fails here must not stop the playback, so each one is isolated.
     */
    suspend fun preparePlayback(media: TrackingMediaReference, progressPercent: Double = 0.0) {
        for (scrobbler in providerRegistry.connectedScrobblers()) {
            try {
                scrobbler.preparePlayback(media, progressPercent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    "TrackingScrobble",
                    "${scrobbler.providerId.storageId} preparePlayback failed",
                    error
                )
            }
        }
    }

    private suspend fun dispatch(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, false) }

    private suspend fun dispatchSeek(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingSeekScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, true) }

    private fun List<TrackingScrobbleFailure>.log(
        action: TrackingScrobbleAction,
        seek: Boolean
    ) {
        forEach { failure ->
            Log.w(
                "TrackingScrobble",
                "${failure.providerId.storageId} ${if (seek) "seek " else ""}scrobble ${action.wireValue} failed",
                failure.cause
            )
        }
    }
}

suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers
        .filter { scrobbler ->
            val accepts = !event.sessionless || scrobbler.acceptsSessionlessScrobbles
            if (!accepts) {
                Log.d(
                    TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                    "coordinator skipped action=${action.wireValue} " +
                        "provider=${scrobbler.providerId.storageId} reason=sessionless"
                )
            }
            accepts
        }
        .map { scrobbler ->
            async {
                try {
                    scrobbler.scrobble(action, event)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    TrackingScrobbleFailure(scrobbler.providerId, error)
                }
            }
        }.awaitAll().filterNotNull()
}

suspend fun dispatchTrackingSeekScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(
    scrobblers = scrobblers.filter { it.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART },
    action = action,
    event = event
)

package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class SimklTrackingScrobbler @Inject constructor(
    private val authRepository: SimklAuthRepository,
    private val syncRepository: SimklSyncRepository,
    private val mutationService: SimklMutationService,
    private val rewatchConsentRepository: SimklRewatchConsentRepository,
    private val settingsDataStore: TraktSettingsDataStore
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.SIMKL

    /*
     * Simkl keeps a "watching now" state of its own and its pause carries the completion threshold,
     * so the account itself answers whether the playback was paused or finished. A playback that no
     * session owns is welcome here: the pause tells Simkl where the user stopped and the start after
     * the resume tells it the playback continued.
     */
    override val acceptsSessionlessScrobbles = true

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val authenticated = authRepository.state.value.isAuthenticated
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter received action=${action.wireValue} authenticated=$authenticated " +
                event.scrobbleDiagnosticSummary()
        )
        if (!authenticated) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl adapter skipped action=${action.wireValue} reason=not_authenticated"
            )
            return
        }
        syncRepository.ensureLoaded()
        val enrichedEvent = event.copy(
            media = syncRepository.state.value.snapshot
                .enrichMediaReference(event.media)
                .resolveAnimeEpisodeForSimkl()
        )
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter enriched action=${action.wireValue} ${enrichedEvent.scrobbleDiagnosticSummary()}"
        )
        val mode = rewatchMode()
        val accountType = authRepository.state.value.accountType
        // One number for the whole path: the scrobble Simkl is sent, the rewatch gates and the local
        // commit. The content end marker comes from the player (`TrackingScrobbleEvent.contentEndPercent`),
        // so a playback that reached the credits is finished even with a higher user threshold. An
        // external player and a start have no marker, and there the user threshold alone decides.
        //
        // Simkl takes whole numbers only, and it is told the number this path decides with, so the
        // account and the client never part ways over a fraction of a percent. A stop below the
        // threshold travels as a stop all the same: with the threshold on the call, the account itself
        // answers a pause and keeps the playback resumable, which is what a stop there has to do.
        val completionThresholdPercent = simklMinProgressPercent(
            resolvedSimklCompletionPercent(
                userThresholdPercent = watchedThresholdPercent().toDouble(),
                contentEndPercent = enrichedEvent.contentEndPercent
            )
        ).toDouble()
        // The run of the item, when the account already has one, and the answer the user gave to
        // the question before the playback: together they decide whether the scrobble asks Simkl for
        // a rewatch. A session the account runs releases the answer, which was for the run it opened.
        val knownSnapshot = syncRepository.state.value.snapshot
        val runningSessionId = knownSnapshot.rewatchSessions.activeRewatchSessionId(enrichedEvent.media)
        if (runningSessionId != null) {
            rewatchConsentRepository.releaseGrant(enrichedEvent.media)
        }
        val playbackHadSession = simklPlaybackHadSession(
            hasPlaybackSession = enrichedEvent.hasPlaybackSession,
            startProgressPercent = enrichedEvent.startProgressPercent,
            completionThresholdPercent = completionThresholdPercent
        )
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl rewatch session start=${enrichedEvent.startProgressPercent} " +
                "threshold=$completionThresholdPercent hadSession=$playbackHadSession"
        )
        val recordRewatch = shouldRecordSimklRewatchOnStop(
            mode = mode,
            accountType = accountType,
            action = action,
            progressPercent = enrichedEvent.progressPercent,
            completionThresholdPercent = completionThresholdPercent,
            hasRunningSession = runningSessionId != null,
            consented = rewatchConsentRepository.grantedFor(enrichedEvent.media),
            hasPlaybackSession = playbackHadSession
        )
        val result = mutationService.scrobble(
            action = action,
            event = enrichedEvent,
            recordRewatch = recordRewatch,
            completionThresholdPercent = completionThresholdPercent
        )
        if (action != TrackingScrobbleAction.START) {
            syncRepository.commitScrobble(result)
        }
        if (recordRewatch || result.rewatchStatus != null) {
            Log.i(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch action=${action.wireValue} status=" +
                    "${result.rewatchStatus?.name?.lowercase() ?: "none"} " +
                    "rewatching=${result.rewatchId != null}"
            )
        }
        // Simkl named the plan the account is on, and it is not the one this client had cached: every
        // rewatch decision below is gated on that plan, so the cache is corrected where the answer is,
        // instead of at the next read of the user settings. The watch itself is unaffected, and only
        // the plan is written, so an account that resubscribes finds its setting as it left it.
        val planAllowsRewatches = if (result.rewatchStatus == SimklRewatchStatus.PRO_REQUIRED) {
            Log.i(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch plan no longer covers sessions; correcting the cached plan"
            )
            authRepository.markPlanAsFree()
            false
        } else {
            isSimklRewatchPlanEligible(accountType)
        }
        if (action == TrackingScrobbleAction.START) {
            // The snapshot as it stood before this start answers whether the account already held
            // the item, which is what the question before a run is about.
            val priorWatch = syncRepository.state.value.snapshot.priorWatchForScrobble(result)
            val askToStartRun = shouldAskToStartSimklRewatch(
                mode = mode,
                accountType = accountType,
                action = action,
                priorWatch = priorWatch,
                nowEpochMs = System.currentTimeMillis(),
                hasRunningSession = runningSessionId != null,
                progressPercent = enrichedEvent.progressPercent,
                hasPlaybackIdentity = enrichedEvent.playbackId != 0L
            )
            if (askToStartRun && planAllowsRewatches) {
                rewatchConsentRepository.ask(enrichedEvent.media)
            }
        }
        if (action == TrackingScrobbleAction.STOP) {
            // A dismissal is for the playback it happened during: the playback has ended, so the
            // series may be asked about again the next time it plays.
            rewatchConsentRepository.clearDismissal(enrichedEvent.media)
            if (result.rewatchId != null) {
                // A rewatch was actually recorded, so the yes before the playback has done its job.
                // For a movie this is the only release it gets, since movie sessions are never read.
                // A show keeps the grant until a running session is seen above: its session only
                // reaches the snapshot a few seconds later, and releasing here would leave
                // autoplay's next episode with no grant and no running session, asking again.
                if (enrichedEvent.media.kind == TrackingMediaKind.MOVIE) {
                    rewatchConsentRepository.releaseGrant(enrichedEvent.media)
                }
                // A session the account just took is not on the snapshot until the sessions are read
                // again, and Continue Watching follows that read: asking for a refresh keeps the row
                // current instead of waiting for the next sync.
                syncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            }
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter complete action=${action.wireValue} " +
                enrichedEvent.scrobbleDiagnosticSummary()
        )
    }

    /*
     * The rewatch mode and the completion threshold are read from `TraktSettingsDataStore`, a single
     * `first()` at the start of the path, so a change on the settings screen takes effect at the next
     * scrobble.
     */
    private suspend fun rewatchMode(): SimklRewatchMode =
        settingsDataStore.simklRewatchMode.first()

    private suspend fun watchedThresholdPercent(): Int =
        settingsDataStore.simklWatchedThresholdPercent.first()
}

@Singleton
class SimklTrackingProvider @Inject constructor(
    authRepository: SimklAuthRepository,
    override val scrobbler: SimklTrackingScrobbler
) : TrackingProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = authRepository.state
        .map { state -> state.isAuthenticated }
        .stateIn(scope, SharingStarted.Eagerly, authRepository.state.value.isAuthenticated)
}

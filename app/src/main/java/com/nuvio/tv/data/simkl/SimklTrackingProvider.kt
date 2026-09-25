package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
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
    private val rewatchPromptRepository: SimklRewatchPromptRepository,
    private val settingsDataStore: TraktSettingsDataStore
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.SIMKL

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
        // One number for the whole path: pause, stop, the rewatch gates and the local commit. The
        // content end marker comes from the player (`TrackingScrobbleEvent.contentEndPercent`), so a
        // playback that reached the credits is finished even with a higher user threshold. An external
        // player and a start have no marker, and there the user threshold alone decides.
        val completionThresholdPercent = resolvedSimklCompletionPercent(
            userThresholdPercent = watchedThresholdPercent().toDouble(),
            contentEndPercent = enrichedEvent.contentEndPercent
        )
        // A playback stopped below the threshold is a pause for Simkl: as a stop the account would
        // apply its own 80 percent rule and mark the title watched anyway, even though the user
        // threshold is higher. The same action holds for the rewatch gate, the prompt and the commit.
        val reportingAction = simklReportingAction(
            action = action,
            progressPercent = enrichedEvent.progressPercent,
            completionThresholdPercent = completionThresholdPercent
        )
        val recordRewatch = shouldRecordSimklRewatchOnStop(
            mode = mode,
            accountType = accountType,
            action = reportingAction,
            progressPercent = enrichedEvent.progressPercent,
            completionThresholdPercent = completionThresholdPercent
        )
        val result = mutationService.scrobble(
            action = reportingAction,
            event = enrichedEvent,
            recordRewatch = recordRewatch,
            completionThresholdPercent = completionThresholdPercent
        )
        // The prior watch has to be read before the commit, or the playback looks like a repeat
        // viewing of itself. Only a stop can raise the prompt.
        val priorWatch = if (reportingAction == TrackingScrobbleAction.STOP) {
            syncRepository.state.value.snapshot.priorWatchForScrobble(result)
        } else {
            SimklPriorWatch.None
        }
        if (reportingAction != TrackingScrobbleAction.START) {
            syncRepository.commitScrobble(result)
        }
        if (recordRewatch || result.rewatchStatus != null) {
            Log.i(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch action=${reportingAction.wireValue} status=" +
                    "${result.rewatchStatus?.name?.lowercase() ?: "none"} " +
                    "rewatching=${result.rewatchId != null}"
            )
        }
        val nowEpochMs = System.currentTimeMillis()
        val watchedAtEpochMs = result.watchedAt?.let(::parseSimklUtcEpochMs) ?: nowEpochMs
        val askToRecord = shouldPromptSimklRewatch(
            mode = mode,
            accountType = accountType,
            action = reportingAction,
            outcome = result.outcome,
            progressPercent = result.progress,
            priorWatch = priorWatch,
            nowEpochMs = nowEpochMs,
            completionThresholdPercent = completionThresholdPercent
        )
        if (askToRecord) {
            rewatchPromptRepository.request(
                RewatchPrompt(
                    media = enrichedEvent.media,
                    watchedAtEpochMs = watchedAtEpochMs
                )
            )
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter complete action=${reportingAction.wireValue} " +
                enrichedEvent.scrobbleDiagnosticSummary()
        )
    }

    /*
     * Difference from mobile: mobile reads `simklRewatchMode` and `simklWatchedThresholdPercent` from
     * `TrackingSettingsRepository`. TV has no such `object` repository, so both values are read from
     * `TraktSettingsDataStore`, where the keys live. `scrobble` is `suspend`, so the read is a single
     * `first()` at the start of the path, the same as `minimumRewatchRunEpisodes` in
     * `SimklSyncRepository.kt` and `SimklSyncEngine.kt`. A change on the settings screen therefore
     * changes the behaviour at the next scrobble.
     */
    private suspend fun rewatchMode(): SimklRewatchMode =
        settingsDataStore.simklRewatchMode.first()

    private suspend fun watchedThresholdPercent(): Int =
        settingsDataStore.simklWatchedThresholdPercent.first()
}

/**
 * The action Simkl is actually told about.
 *
 * A playback the user stopped below the completion threshold is reported as a pause, because a stop
 * there would have Simkl apply its own 80 percent rule and mark the title watched anyway, while the
 * user's own number says the playback did not finish. The same action is then what the rewatch gate,
 * the prompt and the local commit are decided with.
 */
internal fun simklReportingAction(
    action: TrackingScrobbleAction,
    progressPercent: Double,
    completionThresholdPercent: Double
): TrackingScrobbleAction =
    if (action == TrackingScrobbleAction.STOP && progressPercent < completionThresholdPercent) {
        TrackingScrobbleAction.PAUSE
    } else {
        action
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

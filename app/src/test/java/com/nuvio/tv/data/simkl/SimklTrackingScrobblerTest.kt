package com.nuvio.tv.data.simkl

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the rewatch rules reach the account: what the scrobbler tells Simkl, what it commits locally
 * and when it asks the user.
 *
 * The decision has to be made here, and not in the player or in the coordinator, because one scrobble
 * event is broadcast to every provider and only Simkl has rewatch sessions.
 */
class SimklTrackingScrobblerTest {

    private val authRepository = mockk<SimklAuthRepository>(relaxed = true)
    private val syncRepository = mockk<SimklSyncRepository>(relaxed = true)
    private val mutationService = mockk<SimklMutationService>(relaxed = true)
    private val consentRepository = mockk<SimklRewatchConsentRepository>(relaxed = true)

    /*
     * The rewatch mode and the completion threshold are read by the scrobbler from
     * `TraktSettingsDataStore`, so the tests give it a real store over an in-memory preferences
     * store. Nothing is written to it, so the documented defaults hold, the same as before.
     */
    private val settingsPreferences = TestPreferencesStore()
    private val settingsDataStore = TraktSettingsDataStore(
        mockk<ProfileDataStoreFactory>().also { factory ->
            every { factory.get(any(), any()) } returns settingsPreferences
        },
        mockk<ProfileManager>().also { manager ->
            every { manager.activeProfileId } returns MutableStateFlow(1)
        }
    )

    private val scrobbler = SimklTrackingScrobbler(
        authRepository = authRepository,
        syncRepository = syncRepository,
        mutationService = mutationService,
        rewatchConsentRepository = consentRepository,
        settingsDataStore = settingsDataStore
    )

    @Test
    fun `a finished stop is sent as a stop and commits the playback`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 95.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a playback stopped under the threshold keeps the account's own pause`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 45.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 45.0))

        // The stop travels as a stop and carries the threshold it is judged by, so the account
        // answers the pause itself and keeps the playback resumable. Nothing here has to guess what
        // the account would do with its own 80 percent rule.
        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        // The answered outcome decides the local commit, so the playback is stored as the pause the
        // account kept.
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a start never carries the rewatch flag and never commits`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.START, progress = 0.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.START, event(progressPercent = 0.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.START,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        coVerify(exactly = 0) { syncRepository.commitScrobble(any()) }
    }

    @Test
    fun `a pause is sent without the rewatch flag`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 45.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.PAUSE, event(progressPercent = 45.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.PAUSE,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a stop clears the dismissal so a later playback asks again`() = runBlocking {
        connect()
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 45.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 45.0))

        // The dismissal is scoped to one playback, so the STOP that ends it drops the marker.
        verify(exactly = 1) { consentRepository.clearDismissal(any()) }
    }

    @Test
    fun `a recorded rewatch for a show leaves the yes in place`() = runBlocking {
        connect()
        accountAnswers(
            scrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                progress = 95.0,
                rewatchId = 4711L
            )
        )

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        // The show's session only reaches the snapshot a few seconds later, so releasing here would
        // leave autoplay's next episode with no grant and no running session: the yes stays until a
        // running session is seen.
        verify(exactly = 0) { consentRepository.releaseGrant(any()) }
    }

    @Test
    fun `a recorded rewatch for a movie releases the yes`() = runBlocking {
        connect()
        accountAnswers(
            scrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                progress = 95.0,
                rewatchId = 4711L
            )
        )

        scrobbler.scrobble(
            TrackingScrobbleAction.STOP,
            event(progressPercent = 95.0, kind = TrackingMediaKind.MOVIE)
        )

        // A movie's session is never read, so the stop that recorded the rewatch is the only release
        // the yes gets.
        verify(exactly = 1) { consentRepository.releaseGrant(any()) }
    }

    @Test
    fun `a disconnected account is told nothing`() = runBlocking {
        every { authRepository.state } returns MutableStateFlow(SimklAuthState(isAuthenticated = false))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        coVerify(exactly = 0) { mutationService.scrobble(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncRepository.commitScrobble(any()) }
    }

    @Test
    fun `no question is raised while the rewatch mode is the default one`() = runBlocking {
        connect()
        watchedEpisode()
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.START, progress = 0.0))

        scrobbler.scrobble(TrackingScrobbleAction.START, event(progressPercent = 0.0))

        // There is nothing in the store, so the mode is the documented default (OFF): the question
        // has nothing to ask, and nothing is written without an answer.
        verify(exactly = 0) { consentRepository.ask(any()) }
    }

    @Test
    fun `the stored rewatch mode lets a finished stop ask Simkl for a rewatch`() = runBlocking {
        connect()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.AUTOMATIC)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 95.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        // The mode from the settings is what allows `allow_rewatch` on the stop; with the default
        // OFF this would be `recordRewatch = false`.
        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = true,
                completionThresholdPercent = 80.0
            )
        }
    }

    @Test
    fun `a stop from a playback that began above the bar does not ask for a rewatch`() = runBlocking {
        connect()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.AUTOMATIC)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 93.0))

        scrobbler.scrobble(
            TrackingScrobbleAction.STOP,
            event(progressPercent = 93.0, hasPlaybackSession = false)
        )

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
    }

    @Test
    fun `the stored threshold decides when the account counts a stop as watched`() = runBlocking {
        connect()
        settingsDataStore.setSimklWatchedThresholdPercent(95)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 90.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 90.0))

        // The user threshold is 95 and the stop carries it, so 90 percent is a pause for the
        // account: the number on the call, not a rewritten action, is what keeps it one.
        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 95.0
            )
        }
    }

    @Test
    fun `a threshold stored out of range is read back inside the range`() = runBlocking {
        connect()
        settingsDataStore.setSimklWatchedThresholdPercent(140)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 94.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 94.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 100.0
            )
        }
    }

    @Test
    fun `the credits marker from the player decides a stop above the stored threshold`() = runBlocking {
        connect()
        settingsDataStore.setSimklWatchedThresholdPercent(95)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 90.0))

        // The marker says the content ends at 91 percent, already read a few seconds early by the
        // player (`PlayerContentEndPolicy.CONTENT_END_TOLERANCE_SECONDS`), so this layer takes the
        // number it is given: a playback at 90 percent is finished even with a threshold of 95.
        scrobbler.scrobble(
            TrackingScrobbleAction.STOP,
            event(progressPercent = 90.0, contentEndPercent = 91.0)
        )

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 91.0
            )
        }
    }

    @Test
    fun `a marker under what Simkl counts cannot move the threshold`() = runBlocking {
        connect()
        settingsDataStore.setSimklWatchedThresholdPercent(95)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 90.0))

        // A marker under what Simkl counts at all cannot be used, so the user threshold decides: 95
        // percent, which a stop at 90 is under. The marker arrives with the player's tolerance on it.
        scrobbler.scrobble(
            TrackingScrobbleAction.STOP,
            event(progressPercent = 90.0, contentEndPercent = 79.5)
        )

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 95.0
            )
        }
    }

    @Test
    fun `the threshold Simkl is told is a whole number`() = runBlocking {
        connect()
        settingsDataStore.setSimklWatchedThresholdPercent(95)
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 96.0)
        accountAnswers(result)

        // The marker says the content ends at 96.5 percent, read a few seconds early by the player.
        // Simkl takes whole numbers and drops the decimals, so both sides decide with 96, which is
        // the number the call carries.
        scrobbler.scrobble(
            TrackingScrobbleAction.STOP,
            event(progressPercent = 96.0, contentEndPercent = 96.5)
        )

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 96.0
            )
        }
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a playback of something already watched asks before it begins`() = runBlocking {
        connect()
        watchedEpisode()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.SEMI_AUTOMATIC)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.START, progress = 0.0))

        scrobbler.scrobble(TrackingScrobbleAction.START, event(progressPercent = 0.0))

        // The question is raised while the playback is beginning, and its answer is what the stop at
        // the end of that playback writes from.
        verify(exactly = 1) { consentRepository.ask(any()) }
        coVerify(exactly = 0) { authRepository.markPlanAsFree() }
    }

    @Test
    fun `an external start does not ask a question it cannot have answered`() = runBlocking {
        connect()
        watchedEpisode()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.SEMI_AUTOMATIC)
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.START, progress = 0.0))

        scrobbler.scrobble(
            TrackingScrobbleAction.START,
            event(progressPercent = 0.0, playbackId = 0L)
        )

        // The external player reports its start after it has already closed, so the answer could not
        // reach the playback the question is about.
        verify(exactly = 0) { consentRepository.ask(any()) }
    }

    @Test
    fun `a finished stop carries the answer into the write`() = runBlocking {
        connect()
        watchedEpisode()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.SEMI_AUTOMATIC)
        every { consentRepository.grantedFor(any()) } returns true
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 95.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        // The user said yes before the playback, so the stop asks Simkl for the rewatch. The mocked
        // answer defaults to false and this would be recordRewatch = false without that answer.
        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = true,
                completionThresholdPercent = 80.0
            )
        }
    }

    @Test
    fun `a finished stop with a run open writes into it without asking`() = runBlocking {
        connect()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.SEMI_AUTOMATIC)
        every { syncRepository.state } returns MutableStateFlow(
            SimklSyncState(
                snapshot = SimklSyncSnapshot(
                    rewatchSessions = listOf(
                        SimklLibraryEntry(
                            mediaType = SimklMediaType.SHOWS,
                            isRewatch = true,
                            rewatchId = 7482L,
                            rewatchStatus = "active",
                            show = SimklMedia(
                                title = "Dark",
                                year = 2017,
                                ids = mapOf("imdb" to JsonPrimitive("tt5753856"))
                            )
                        )
                    )
                )
            )
        )
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 95.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        // The run is on the account, so the stop continues it, and no question stands in the way.
        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = true,
                completionThresholdPercent = 80.0
            )
        }
        verify(exactly = 0) { consentRepository.ask(any()) }
    }

    @Test
    fun `a stop the account says needs a plan corrects the plan and asks nothing`() = runBlocking {
        connect()
        watchedEpisode()
        settingsDataStore.setSimklRewatchMode(SimklRewatchMode.SEMI_AUTOMATIC)
        accountAnswers(
            scrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                progress = 95.0,
                rewatchStatus = SimklRewatchStatus.PRO_REQUIRED
            )
        )

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        // Simkl named the reason instead of dropping the flag silently, so the cached plan is corrected
        // where the answer is. The question is not asked on the strength of the plan the client had
        // cached, because the account would drop what the user then confirmed, and the watch itself is
        // unaffected by any of it.
        coVerify(exactly = 1) { authRepository.markPlanAsFree() }
        verify(exactly = 0) { consentRepository.ask(any()) }
        coVerify(exactly = 1) { syncRepository.commitScrobble(any()) }
    }

    /**
     * The account with the episode the event names already watched, two months back, so a finished
     * playback of it is a repeat viewing the app can ask about.
     */
    private fun watchedEpisode() {
        val watchedAt = "2026-08-01T20:00:00Z"
        every { syncRepository.state } returns MutableStateFlow(
            SimklSyncState(
                snapshot = SimklSyncSnapshot(
                    entries = listOf(
                        SimklLibraryEntry(
                            mediaType = SimklMediaType.SHOWS,
                            status = SimklListStatus.COMPLETED,
                            lastWatchedAt = watchedAt,
                            show = SimklMedia(
                                title = "Dark",
                                year = 2017,
                                ids = mapOf("imdb" to JsonPrimitive("tt5753856"))
                            ),
                            seasons = listOf(
                                SimklSeason(
                                    number = 2,
                                    episodes = listOf(SimklEpisode(number = 7, watchedAt = watchedAt))
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private fun connect(accountType: String? = "vip") {
        every { authRepository.state } returns MutableStateFlow(
            SimklAuthState(isAuthenticated = true, accountType = accountType)
        )
        every { syncRepository.state } returns MutableStateFlow(SimklSyncState())
        // mockk's relaxed answer ignores nullability and hands out a chained mock, so the empty
        // snapshot has to say the index is absent; otherwise enrichment reads a fake entry.
        every { syncRepository.currentEntryIndex() } returns null
    }

    private fun accountAnswers(result: SimklScrobbleResult) {
        coEvery {
            mutationService.scrobble(any(), any(), any(), any())
        } returns result
    }

    private fun event(
        progressPercent: Double,
        contentEndPercent: Double? = null,
        kind: TrackingMediaKind = TrackingMediaKind.SHOW,
        hasPlaybackSession: Boolean = true,
        playbackId: Long = 42L
    ) = TrackingScrobbleEvent(
        media = TrackingMediaReference(
            kind = kind,
            title = "Dark",
            year = 2017,
            ids = TrackingExternalIds(imdb = "tt5753856"),
            episode = if (kind == TrackingMediaKind.MOVIE) {
                null
            } else {
                TrackingEpisode(season = 2, number = 7)
            }
        ),
        progressPercent = progressPercent,
        contentEndPercent = contentEndPercent,
        hasPlaybackSession = hasPlaybackSession,
        playbackId = playbackId
    )

    private fun scrobbleResult(
        outcome: SimklScrobbleOutcome,
        progress: Double,
        watchedAt: String? = null,
        rewatchStatus: SimklRewatchStatus? = null,
        rewatchId: Long? = null
    ) = SimklScrobbleResult(
        outcome = outcome,
        playbackId = 42L,
        progress = progress,
        mediaType = SimklMediaType.SHOWS,
        media = SimklMedia(title = "Dark", year = 2017),
        episode = SimklPlaybackEpisode(season = 2, number = 7),
        watchedAt = watchedAt,
        rewatchId = rewatchId,
        rewatchStatus = rewatchStatus
    )
}

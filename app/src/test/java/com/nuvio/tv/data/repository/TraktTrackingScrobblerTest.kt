package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What Trakt is told, including the one rule it keeps to itself: a playback that resumes above the
 * completion bar does not open a viewing, so the start the player sends for every tracker is skipped
 * here, while the trackers that report a "watching now" state of their own take it.
 */
class TraktTrackingScrobblerTest {
    @Test
    fun `pause preserves trakt stop behavior`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = TraktTrackingScrobbler(
            service = service,
            episodeMappingService = mockk(relaxed = true)
        )

        scrobbler.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(
                media = TrackingMediaReference(
                    kind = TrackingMediaKind.MOVIE,
                    ids = TrackingExternalIds(imdb = "tt0111161")
                ),
                progressPercent = 45.0
            )
        )

        coVerify(exactly = 1) { service.scrobbleStop(any(), 45f) }
        coVerify(exactly = 0) { service.scrobblePause(any(), any()) }
    }

    @Test
    fun `one playback is recorded once when it completes after an interruption`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 85.0, playbackId = 42L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 100.0, playbackId = 42L))

        coVerify(exactly = 1) { service.scrobbleStop(any(), any()) }
    }

    @Test
    fun `a failed completion does not suppress the completion that ends the playback`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)
        coEvery { service.scrobbleStop(any(), 85f) } returns false
        coEvery { service.scrobbleStop(any(), 100f) } returns true

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(85.0, playbackId = 42L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(100.0, playbackId = 42L))

        coVerify(exactly = 2) { service.scrobbleStop(any(), any()) }
    }

    @Test
    fun `a later playback of the same item is recorded again`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 85.0, playbackId = 42L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 100.0, playbackId = 43L))

        coVerify(exactly = 2) { service.scrobbleStop(any(), any()) }
    }

    @Test
    fun `a start above the completion bar never reaches Trakt`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(
            action = TrackingScrobbleAction.START,
            event = event(progressPercent = 85.0)
        )

        coVerify(exactly = 0) { service.scrobbleStart(any(), any()) }
    }

    @Test
    fun `a start below the completion bar is sent with its progress`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(
            action = TrackingScrobbleAction.START,
            event = event(progressPercent = 45.0)
        )

        coVerify(exactly = 1) { service.scrobbleStart(any(), 45f) }
    }

    @Test
    fun `only the start is skipped above the bar`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        // A pause is Trakt's own stop, and it is how a playback that resumed above the bar is
        // reported at all: the start is the only event that would record the viewing twice.
        scrobbler.scrobble(TrackingScrobbleAction.PAUSE, event(progressPercent = 85.0))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 85.0))

        coVerify(exactly = 2) { service.scrobbleStop(any(), 85f) }
    }

    private fun scrobbler(service: TraktScrobbleService): TraktTrackingScrobbler {
        coEvery { service.scrobbleStart(any(), any()) } returns true
        coEvery { service.scrobbleStop(any(), any()) } returns true
        return TraktTrackingScrobbler(
            service = service,
            episodeMappingService = mockk(relaxed = true)
        )
    }

    private fun event(progressPercent: Double, playbackId: Long = 0L) = TrackingScrobbleEvent(
        media = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            ids = TrackingExternalIds(imdb = "tt0111161")
        ),
        progressPercent = progressPercent,
        playbackId = playbackId
    )
}

package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What MDBList hears: a stop at or above the bar records a watch, and one playback must not record
 * it twice when it is interrupted and resumed.
 */
class MdbListTrackingScrobblerTest {

    @Test
    fun `a repeated completion of the same playback reaches the service once`() = runTest {
        val service = mockk<MdbListScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(85.0, playbackId = 7L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(100.0, playbackId = 7L))

        coVerify(exactly = 1) { service.scrobble(any(), TrackingScrobbleAction.STOP, any()) }
    }

    @Test
    fun `a failed completion does not suppress the completion that ends the playback`() = runTest {
        val service = mockk<MdbListScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)
        coEvery { service.scrobble(any(), TrackingScrobbleAction.STOP, any()) } returnsMany listOf(false, true)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(85.0, playbackId = 7L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(100.0, playbackId = 7L))

        coVerify(exactly = 2) { service.scrobble(any(), TrackingScrobbleAction.STOP, any()) }
    }

    @Test
    fun `a later playback of the same item is recorded again`() = runTest {
        val service = mockk<MdbListScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(85.0, playbackId = 7L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(100.0, playbackId = 8L))

        coVerify(exactly = 2) { service.scrobble(any(), TrackingScrobbleAction.STOP, any()) }
    }

    @Test
    fun `a stop below the bar is not a completion and is not collapsed`() = runTest {
        val service = mockk<MdbListScrobbleService>(relaxed = true)
        val scrobbler = scrobbler(service)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(40.0, playbackId = 7L))
        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(45.0, playbackId = 7L))

        coVerify(exactly = 2) { service.scrobble(any(), TrackingScrobbleAction.STOP, any()) }
    }

    private fun scrobbler(service: MdbListScrobbleService): MdbListTrackingScrobbler {
        val sync = mockk<MdbListSyncRepository>()
        every { sync.currentScope() } returns MdbListAuthScope(profileId = 1, generation = 1L)
        coEvery { service.scrobble(any(), any(), any()) } returns true
        return MdbListTrackingScrobbler(sync = sync, service = service)
    }

    private fun event(progressPercent: Double, playbackId: Long) = TrackingScrobbleEvent(
        media = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            ids = TrackingExternalIds(imdb = "tt0111161")
        ),
        progressPercent = progressPercent,
        playbackId = playbackId
    )
}

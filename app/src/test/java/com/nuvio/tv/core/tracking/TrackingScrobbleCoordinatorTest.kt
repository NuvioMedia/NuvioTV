package com.nuvio.tv.core.tracking

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingScrobbleCoordinatorTest {
    @Test
    fun `fanout isolates a provider failure`() = runTest {
        val successful = FakeScrobbler(TrackingProviderId.TRAKT)
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, IllegalStateException("offline"))
        val mdblist = FakeScrobbler(TrackingProviderId.MDBLIST)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 42.5
        )

        val failures = dispatchTrackingScrobble(
            scrobblers = listOf(successful, failing, mdblist),
            action = TrackingScrobbleAction.PAUSE,
            event = event
        )

        assertEquals(1, successful.callCount)
        assertEquals(1, failing.callCount)
        assertEquals(listOf(TrackingScrobbleAction.PAUSE), mdblist.actions)
        assertEquals(listOf(TrackingScrobbleAction.PAUSE), successful.actions)
        assertEquals(listOf(TrackingScrobbleAction.PAUSE), failing.actions)
        assertEquals(listOf(TrackingProviderId.SIMKL), failures.map { it.providerId })
    }

    @Test
    fun `seek fanout targets only providers that restart sessions`() = runTest {
        val trakt = FakeScrobbler(
            providerId = TrackingProviderId.TRAKT,
            seekPolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART
        )
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)
        val mdblist = FakeScrobbler(TrackingProviderId.MDBLIST)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 55.0
        )

        dispatchTrackingSeekScrobble(
            scrobblers = listOf(trakt, simkl, mdblist),
            action = TrackingScrobbleAction.STOP,
            event = event
        )

        assertEquals(1, trakt.callCount)
        assertEquals(0, simkl.callCount)
        assertEquals(0, mdblist.callCount)
    }

    @Test
    fun `a sessionless scrobble reaches only the scrobblers that accept it`() = runTest {
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT)
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL, acceptsSessionless = true)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 92.0,
            sessionless = true
        )

        dispatchTrackingScrobble(
            scrobblers = listOf(trakt, simkl),
            action = TrackingScrobbleAction.PAUSE,
            event = event
        )

        assertEquals(0, trakt.callCount)
        assertEquals(1, simkl.callCount)
    }

    @Test
    fun `a sessioned scrobble still reaches every scrobbler`() = runTest {
        val trakt = FakeScrobbler(TrackingProviderId.TRAKT)
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL, acceptsSessionless = true)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 40.0
        )

        dispatchTrackingScrobble(
            scrobblers = listOf(trakt, simkl),
            action = TrackingScrobbleAction.PAUSE,
            event = event
        )

        assertEquals(1, trakt.callCount)
        assertEquals(1, simkl.callCount)
    }

    private class FakeScrobbler(
        override val providerId: TrackingProviderId,
        private val failure: Throwable? = null,
        private val seekPolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE,
        private val acceptsSessionless: Boolean = false
    ) : TrackingScrobbler {
        var callCount = 0
        val actions = mutableListOf<TrackingScrobbleAction>()
        override val seekScrobblePolicy = seekPolicy
        override val acceptsSessionlessScrobbles = acceptsSessionless

        override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
            callCount += 1
            actions += action
            failure?.let { throw it }
        }
    }
}

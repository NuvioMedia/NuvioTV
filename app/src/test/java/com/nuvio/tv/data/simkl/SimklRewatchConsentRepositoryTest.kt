package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answers the question before a playback can give, and how far each one reaches.
 *
 * The answers are kept for the app run only: a yes covers a series until the account runs a session
 * for it, a no keeps the series quiet, and a question left unanswered decides nothing and is asked
 * again. Nothing here waits for a write, because a question is not one.
 */
class SimklRewatchConsentRepositoryTest {

    private val repository = SimklRewatchConsentRepository()

    @Test
    fun `no question is open until one is asked`() {
        assertNull(repository.question.value)
        assertFalse(repository.grantedFor(dark(7)))
    }

    @Test
    fun `an unanswered question decides nothing and is asked again`() {
        repository.ask(dark(7))
        assertEquals(7, repository.question.value?.media?.episode?.number)

        repository.dismiss()

        assertNull(repository.question.value)
        assertFalse(repository.grantedFor(dark(7)))
        repository.ask(dark(7))
        assertEquals(7, repository.question.value?.media?.episode?.number)
    }

    @Test
    fun `a yes covers the episodes of the series until a session takes over`() {
        repository.ask(dark(7))
        repository.grant()

        assertNull(repository.question.value)
        assertTrue(repository.grantedFor(dark(7)))
        // The run the answer opens covers the episodes after the one that was asked about.
        assertTrue(repository.grantedFor(dark(8)))
        assertFalse(repository.grantedFor(breakingBad(1)))

        // The account runs the series now, so the answer has done its job and a later run asks again.
        repository.releaseGrant(dark(8))
        assertFalse(repository.grantedFor(dark(7)))
    }

    @Test
    fun `a no keeps the series quiet for the app run`() {
        repository.ask(dark(7))
        repository.decline()

        assertNull(repository.question.value)
        assertFalse(repository.grantedFor(dark(7)))
        // Asking again changes nothing while the answer stands.
        repository.ask(dark(8))
        assertNull(repository.question.value)
    }

    @Test
    fun `another series is asked about after an answer`() {
        repository.ask(dark(7))
        repository.decline()

        repository.ask(breakingBad(1))

        assertEquals("Breaking Bad", repository.question.value?.media?.title)
    }

    @Test
    fun `the question that is already open is not replaced`() {
        repository.ask(dark(7))
        repository.ask(dark(8))

        assertEquals(7, repository.question.value?.media?.episode?.number)
    }

    private fun dark(number: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = "tt5753856"),
        episode = TrackingEpisode(season = 1, number = number)
    )

    private fun breakingBad(number: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Breaking Bad",
        year = 2008,
        ids = TrackingExternalIds(imdb = "tt0903747"),
        episode = TrackingEpisode(season = 1, number = number)
    )
}

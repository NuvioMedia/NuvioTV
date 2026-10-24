package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answers the question before a playback can give, and how far each one reaches.
 *
 * The answers are kept for the app run only: a yes covers a series until the account runs a session
 * for it, a no keeps the series quiet, and a question left unanswered decides nothing — it is
 * dismissed only for the playback that raised it and is asked again once that playback ends.
 * Nothing here waits for a write, because a question is not one.
 */
class SimklRewatchConsentRepositoryTest {

    private val activeProfileState = MutableStateFlow(1)
    private val repository = SimklRewatchConsentRepository(
        mockk<ProfileManager> {
            every { activeProfileId } returns activeProfileState
        }
    )

    @Test
    fun `a profile switch drops what the previous profile answered`() {
        repository.ask(dark(7))
        repository.grant()
        assertTrue(repository.grantedFor(dark(7)))

        activeProfileState.value = 2

        // The yes belonged to the account of profile 1; profile 2 is asked on its own.
        assertFalse(repository.grantedFor(dark(7)))
        repository.ask(dark(7))
        assertEquals(7, repository.question.value?.media?.episode?.number)
    }

    @Test
    fun `an open question is dropped when the profile changes`() {
        repository.ask(dark(7))
        assertEquals(7, repository.question.value?.media?.episode?.number)

        activeProfileState.value = 2

        assertNull(repository.question.value)
    }

    @Test
    fun `no question is open until one is asked`() {
        assertNull(repository.question.value)
        assertFalse(repository.grantedFor(dark(7)))
    }

    @Test
    fun `a dismissed question is not asked again until the playback ends`() {
        repository.ask(dark(7))
        assertEquals(7, repository.question.value?.media?.episode?.number)

        repository.dismiss()

        assertNull(repository.question.value)
        assertFalse(repository.grantedFor(dark(7)))
        // A pause resets the scrobble flags and resuming sends a new START: the dismissal keeps the
        // question quiet for the rest of this playback.
        repository.ask(dark(7))
        assertNull(repository.question.value)

        // The playback of that item ended, so a later playback may ask again.
        repository.clearDismissal(dark(7))
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
    fun `a yes for one series is not forgotten when another series is granted`() {
        repository.ask(dark(7))
        repository.grant()
        repository.ask(breakingBad(1))
        repository.grant()

        // Both answers stand: a yes for one series must not erase the yes for the other.
        assertTrue(repository.grantedFor(dark(8)))
        assertTrue(repository.grantedFor(breakingBad(2)))
    }

    @Test
    fun `a no for one series is not forgotten when another series is declined`() {
        repository.ask(dark(7))
        repository.decline()
        repository.ask(breakingBad(1))
        repository.decline()

        repository.ask(dark(8))
        assertNull(repository.question.value)
        repository.ask(breakingBad(2))
        assertNull(repository.question.value)
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

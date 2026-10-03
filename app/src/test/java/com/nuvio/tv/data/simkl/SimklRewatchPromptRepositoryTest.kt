package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the rewatch question does with an answer.
 *
 * The write of a confirmed rewatch runs on the repository's own scope, not the one that asked the
 * question: clearing the prompt takes the overlay out of the composition and its scope dies with it.
 * The notice is read back as a flow here, which is also how the UI reads it.
 */
class SimklRewatchPromptRepositoryTest {

    private val writer = mockk<SimklRewatchWriter>(relaxed = true)
    private val repository = SimklRewatchPromptRepository(writer)

    @Test
    fun `a confirmed answer writes the rewatch the question holds`() = runBlocking {
        coEvery { writer.recordConfirmedRewatch(any(), any()) } returns true
        repository.request(prompt())

        repository.confirm()

        assertEquals(RewatchNoticeKind.RECORDED, awaitNotice().kind)
        val media = slot<TrackingMediaReference>()
        val watchedAt = slot<Long>()
        coVerify {
            writer.recordConfirmedRewatch(
                media = capture(media),
                watchedAtEpochMs = capture(watchedAt)
            )
        }
        // The prompt is unwrapped by this repository: the writer takes the media and the moment the
        // playback was watched, not the question.
        assertEquals(EPISODE, media.captured)
        assertEquals(WATCHED_AT, watchedAt.captured)
    }

    @Test
    fun `the question is closed before the write is answered`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        coEvery { writer.recordConfirmedRewatch(any(), any()) } coAnswers {
            gate.await()
            true
        }
        repository.request(prompt())

        repository.confirm()

        // Nothing is left to answer twice, and the notice comes with the write, not with the tap.
        assertNull(repository.prompt.value)
        assertNull(repository.notice.value)
        gate.complete(Unit)
        assertEquals(RewatchNoticeKind.RECORDED, awaitNotice().kind)
    }

    @Test
    fun `a write the account refused is reported as a failure`() = runBlocking {
        coEvery { writer.recordConfirmedRewatch(any(), any()) } returns false
        repository.request(prompt())

        repository.confirm()

        assertEquals(RewatchNoticeKind.FAILED, awaitNotice().kind)
    }

    @Test
    fun `a declined answer writes nothing and says so`() = runBlocking {
        repository.request(prompt())

        repository.decline()

        assertNull(repository.prompt.value)
        assertEquals(RewatchNoticeKind.NOT_RECORDED, repository.notice.value?.kind)
        coVerify(exactly = 0) { writer.recordConfirmedRewatch(any(), any()) }
    }

    @Test
    fun `a dismissed question leaves no answer behind`() = runBlocking {
        repository.request(prompt())

        repository.dismiss()

        assertNull(repository.prompt.value)
        assertNull(repository.notice.value)
        coVerify(exactly = 0) { writer.recordConfirmedRewatch(any(), any()) }
    }

    @Test
    fun `an answer with no question pending writes nothing`() = runBlocking {
        repository.confirm()

        coVerify(exactly = 0) { writer.recordConfirmedRewatch(any(), any()) }
        assertNull(repository.notice.value)
    }

    @Test
    fun `dismissing the notice hides the last answer`() {
        repository.decline()

        repository.dismissNotice()

        assertNull(repository.notice.value)
    }

    private suspend fun awaitNotice(): RewatchNotice =
        withTimeout(NOTICE_TIMEOUT_MS) { repository.notice.filterNotNull().first() }

    private fun prompt() = RewatchPrompt(media = EPISODE, watchedAtEpochMs = WATCHED_AT)

    private companion object {
        const val NOTICE_TIMEOUT_MS = 5_000L
        const val WATCHED_AT = 1_700_000_000_000L

        val EPISODE = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            title = "Dark",
            year = 2017,
            ids = TrackingExternalIds(imdb = "tt5753856"),
            episode = TrackingEpisode(season = 2, number = 7)
        )
    }
}

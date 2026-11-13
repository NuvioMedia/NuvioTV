package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPlaySourceFallbackTest {
    private val sources = (1..4).map { index -> stream("Source $index") }

    @Test
    fun `a stream that resolves plays without a fallback`() = runBlocking {
        val run = run(outcomes = mapOf(sources[0] to resolved(sources[0])))

        assertEquals("Source 1", run.resolved)
        assertEquals(0, run.attempts)
        assertEquals(setOf(sources[0].sourceFallbackKey()), run.triedKeys)
    }

    @Test
    fun `an expired debrid result falls back to the next source`() = runBlocking {
        val run = run(outcomes = mapOf(sources[0] to expired, sources[1] to resolved(sources[1])))

        assertEquals("Source 2", run.resolved)
        assertNull(run.failure)
        // The source that plays counts, the way the player counts the ones it falls back to.
        assertEquals(1, run.attempts)
        assertEquals(sources.take(2).map(Stream::sourceFallbackKey).toSet(), run.triedKeys)
    }

    @Test
    fun `the configured number of further sources is the limit`() = runBlocking {
        val run = run(outcomes = sources.associateWith { expired }, maxSources = 2)

        assertNull(run.resolved)
        assertEquals("expired", run.failure)
        assertEquals(2, run.attempts)
        assertEquals(3, run.triedKeys.size)
    }

    @Test
    fun `with no source left the last failure is the one to show`() = runBlocking {
        val run = run(
            outcomes = mapOf(sources[0] to expired, sources[1] to AutoPlayResolveAttempt.Failed("not cached")),
            available = sources.take(2)
        )

        assertNull(run.resolved)
        assertEquals("not cached", run.failure)
        assertEquals(1, run.attempts)
    }

    @Test
    fun `a failure already shown ends the auto-play`() = runBlocking {
        val run = run(outcomes = mapOf(sources[0] to AutoPlayResolveAttempt.Ended))

        assertNull(run.resolved)
        assertNull(run.failure)
        assertEquals(0, run.attempts)
    }

    private val expired = AutoPlayResolveAttempt.Failed("expired")

    private fun resolved(stream: Stream) = AutoPlayResolveAttempt.Resolved(stream.name.orEmpty())

    /** Picks the first untried source of [available], like the auto selection in first-stream mode. */
    private suspend fun run(
        outcomes: Map<Stream, AutoPlayResolveAttempt<String, String>>,
        maxSources: Int = 3,
        available: List<Stream> = sources
    ): AutoPlayFallbackRun<String, String> = resolveWithSourceFallback(
        first = sources[0],
        maxSources = maxSources,
        resolve = { stream -> outcomes[stream] ?: error("${stream.name} was not expected to be tried") },
        nextCandidate = { _, triedKeys -> available.firstOrNull { it.sourceFallbackKey() !in triedKeys } }
    )

    private fun stream(name: String) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.com/${name.replace(' ', '-')}.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Test",
        addonLogo = null
    )
}

package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamFallbackSessionTest {
    private fun stream(id: String) = Stream(
        name = id, title = null, description = null, url = "https://example.test/$id",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = "Addon", addonLogo = null
    )

    @Test fun `starts after selected stream and never wraps`() {
        val sources = listOf(stream("a"), stream("b"), stream("c"))
        val session = StreamFallbackSession(sources[1], sources)
        assertEquals(sources[2], session.next())
        assertNull(session.next())
        assertFalse(session.canAdvance)
        assertNull(session.next())
    }

    @Test fun `cached selection missing from results starts at first candidate`() {
        val next = stream("next")
        assertEquals(next, StreamFallbackSession(stream("cached"), listOf(next)).next())
    }

    @Test fun `selecting a duplicate in a later addon does not go backwards`() {
        val first = stream("same")
        val selected = first.copy(addonName = "Second addon")
        val next = stream("next")
        val session = StreamFallbackSession(selected, listOf(first, stream("earlier"), selected, next))
        assertEquals(next, session.next())
        assertNull(session.next())
    }

    @Test fun `duplicates across addons do not consume attempts`() {
        val first = stream("first")
        val second = stream("second")
        val session = StreamFallbackSession(first, listOf(
            first, first.copy(addonName = "Other", name = "Other title"), second,
            second.copy(addonName = "Third"), stream("third")
        ), maxAttempts = 2)
        assertEquals(second, session.next())
        assertEquals(stream("third"), session.next())
        assertEquals(2, session.attempts)
        assertNull(session.next())
    }

    @Test fun `attempt budget survives successful preparation and later playback failure`() = runTest {
        val first = stream("first")
        val second = stream("second")
        val session = StreamFallbackSession(first, listOf(first, second, stream("third")), maxAttempts = 1)
        assertEquals(second, session.resolveNext { candidate, _ -> candidate })
        assertNull(session.resolveNext { candidate, _ -> candidate })
    }

    @Test fun `five automatic attempts is the default even with hundreds of results`() {
        val first = stream("first")
        val session = StreamFallbackSession(first, (1..100).map { stream("$it") })
        repeat(5) { assertNotNull(session.next()) }
        assertNull(session.next())
    }

    @Test fun `external youtube and unusable entries are skipped`() {
        val first = stream("first")
        val valid = stream("valid")
        val session = StreamFallbackSession(first, listOf(
            first, stream("browser").copy(url = null, externalUrl = "https://example.test/page"),
            stream("youtube").copy(ytId = "abc"), stream("empty").copy(url = null),
            stream("intent").copy(url = "intent://other-app"), valid
        ))
        assertEquals(valid, session.next())
        assertEquals(1, session.attempts)
    }

    @Test fun `usenet identity includes file selection and survives local URL resolution`() {
        val first = stream("nzb").copy(url = null, nzbUrl = "https://example.test/release.nzb", fileIdx = 0)
        val next = first.copy(fileIdx = 1)
        val session = StreamFallbackSession(first, listOf(first, first.copy(url = "http://127.0.0.1/stream"), next))
        assertEquals(next, session.next())
        assertNull(session.next())
    }

    @Test fun `failed preparations advance until a working candidate is found`() = runTest {
        val sources = (0..3).map { stream("$it") }
        val attempted = mutableListOf<Stream>()
        val session = StreamFallbackSession(sources[0], sources)
        val result = session.resolveNext { candidate, attempt ->
            attempted += candidate
            if (attempt < 3) error("unavailable")
            candidate
        }
        assertEquals(sources[3], result)
        assertEquals(sources.drop(1), attempted)
        assertNull(session.next())
    }

    @Test fun `resolved URL aliases cannot be retried as another addon result`() = runTest {
        val selected = stream("selected")
        val candidate = stream("candidate")
        val resolved = stream("resolved")
        val last = stream("last")
        val session = StreamFallbackSession(selected, listOf(selected, candidate, resolved, last))
        assertEquals(resolved, session.resolveNext { _, _ -> resolved })
        assertEquals(last, session.next())
    }

    @Test fun `cancellation stops immediately without trying another stream`() = runTest {
        val sources = (0..3).map { stream("$it") }
        val session = StreamFallbackSession(sources[0], sources)
        var calls = 0
        try {
            session.resolveNext { _, _ -> calls++; throw CancellationException("user left") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
            assertEquals(1, session.attempts)
        }
    }

    @Test fun `a resolver returning after cancellation cannot start playback`() = runTest {
        val first = stream("first")
        val session = StreamFallbackSession(first, listOf(first, stream("second"), stream("third")))
        var startedPlayback = false
        val job = launch {
            session.resolveNext { candidate, _ ->
                coroutineContext.cancel()
                candidate
            }
            startedPlayback = true
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(startedPlayback)
        assertEquals(1, session.attempts)
    }

    @Test fun `an exhausted preparation chain stays exhausted`() = runTest {
        val first = stream("first")
        val session = StreamFallbackSession(first, listOf(first, stream("second")))
        assertNull(session.resolveNext { _, _ -> error("unavailable") })
        assertFalse(session.canAdvance)
        assertNull(session.resolveNext { _, _ -> fail("Must not retry"); first })
    }

    @Test fun `handoff keeps attempt history and is scoped to profile content and URL`() {
        val sources = (0..3).map { stream("handoff-$it") }
        val session = StreamFallbackSession(sources[0], sources)
        session.next()
        StreamFallbackHandoff.put("series|episode-1", 1, "local-url", session)
        assertNull(StreamFallbackHandoff.take("series|episode-2", 1, "local-url"))
        assertNull(StreamFallbackHandoff.take("series|episode-1", 2, "local-url"))
        assertNull(StreamFallbackHandoff.take("series|episode-1", 1, "other-url"))
        val restored = StreamFallbackHandoff.take("series|episode-1", 1, "local-url")!!
        assertSame(session, restored)
        assertEquals(sources[2], restored.next())
        assertNull(StreamFallbackHandoff.take("series|episode-1", 1, "local-url"))
    }
}

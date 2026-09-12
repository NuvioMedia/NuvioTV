package com.nuvio.tv.core.recommendations

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Capture public ContentValues writes while keeping Android framework stubs out of JVM tests. */
class TvProgramValuesTest {
    private val columns = mutableMapOf<String, Any?>()

    @Before fun captureProviderValues() {
        mockkConstructor(ContentValues::class, Intent::class)
        mockkStatic(Uri::class)
        every { anyConstructed<ContentValues>().put(any(), any<String>()) } answers {
            columns[firstArg()] = secondArg<String>(); Unit
        }
        every { anyConstructed<ContentValues>().put(any(), any<Int>()) } answers {
            columns[firstArg()] = secondArg<Int>(); Unit
        }
        every { anyConstructed<ContentValues>().put(any(), any<Long>()) } answers {
            columns[firstArg()] = secondArg<Long>(); Unit
        }
        every { anyConstructed<ContentValues>().putNull(any()) } answers {
            columns[firstArg()] = null; Unit
        }
        every { anyConstructed<Intent>().toUri(any()) } returns "intent:audit"
        every { Uri.parse(any()) } answers {
            val text = firstArg<String>()
            val parsed = mockk<Uri>()
            every { parsed.toString() } returns text
            parsed
        }
    }

    @After fun cleanup() = unmockkAll()

    private fun episode() = WatchProgress(
        contentId = "audit", contentType = "series", name = "Audit episode",
        poster = null, backdrop = null, logo = null, videoId = "audit:2:3",
        season = 2, episode = 3, episodeTitle = "Episode three",
        position = 2500, duration = 10000, lastWatched = 123456L
    )

    @Test fun `watch next preserves identity timing and episode columns`() {
        ProgramBuilder(mockk()).buildWatchNextProgram(episode())
        assertEquals("wn_audit_s2e3", columns["internal_provider_id"])
        assertEquals("Audit episode", columns["title"])
        assertEquals("intent:audit", columns["intent_uri"])
        assertEquals(123456L, columns["last_engagement_time_utc_millis"])
        assertEquals(2500, columns["last_playback_position_millis"])
        assertEquals(10000, columns["duration_millis"])
        assertEquals("2", columns["season_display_number"])
        assertEquals("3", columns["episode_display_number"])
        assertEquals("Episode three", columns["episode_title"])
        assertEquals(0, columns["watch_next_type"])
    }

    @Test fun `watch next preserves synthetic progress without inventing episode fields for movies`() {
        ProgramBuilder(mockk()).buildWatchNextProgram(
            episode().copy(contentType = "movie", season = null, episode = null,
                duration = 0, position = 0, progressPercent = 35f)
        )
        assertEquals("wn_audit", columns["internal_provider_id"])
        assertEquals(100000, columns["duration_millis"])
        assertEquals(35000, columns["last_playback_position_millis"])
        assertFalse(columns.containsKey("season_display_number"))
    }

    @Test fun `preview preserves channel order and explicitly clears unavailable artwork and progress`() {
        AndroidTvChannelManager(mockk(), mockk()).buildProgramValues(
            episode().copy(duration = 0, position = 0), 42L, 3, "audit_key"
        )
        assertEquals(42L, columns["channel_id"])
        assertEquals(Int.MAX_VALUE - 3, columns["weight"])
        assertEquals("audit_key", columns["internal_provider_id"])
        assertEquals("intent:audit", columns["intent_uri"])
        listOf("poster_art_uri", "logo_uri", "duration_millis", "last_playback_position_millis").forEach {
            assertTrue("Missing explicit clear for $it", columns.containsKey(it))
            assertNull(columns[it])
        }
    }

    @Test fun `preview preserves poster shape and progress fallback`() {
        AndroidTvChannelManager(mockk(), mockk()).buildProgramValues(
            episode().copy(poster = "https://example.invalid/audit.png", position = 0, progressPercent = 50f),
            42L, 0, "audit_key"
        )
        assertEquals("https://example.invalid/audit.png", columns["poster_art_uri"])
        assertEquals(4, columns["poster_art_aspect_ratio"])
        assertEquals(5000, columns["last_playback_position_millis"])
        assertEquals(10000, columns["duration_millis"])
    }
}

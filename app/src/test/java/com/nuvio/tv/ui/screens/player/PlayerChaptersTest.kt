package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChaptersTest {

    private val chapters = listOf(
        PlayerChapter(0L, "Intro"),
        PlayerChapter(90_000L, "Part 1"),
        PlayerChapter(600_000L, "Credits")
    )

    @Test
    fun `a lone chapter is no chapters`() {
        assertTrue(PlayerChapters.normalize(listOf(PlayerChapter(0L, "Chapter 1"))).isEmpty())
    }

    @Test
    fun `normalize sorts and drops repeated starts`() {
        val normalized = PlayerChapters.normalize(
            listOf(PlayerChapter(600_000L, "C"), PlayerChapter(0L, "A"), PlayerChapter(0L, "dup"), PlayerChapter(90_000L, "B"))
        )
        assertEquals(listOf(0L, 90_000L, 600_000L), normalized.map { it.startMs })
        assertEquals("A", normalized.first().title)
    }

    @Test
    fun `index is the chapter playing at the position`() {
        assertEquals(0, PlayerChapters.indexAt(chapters, 0L))
        assertEquals(1, PlayerChapters.indexAt(chapters, 90_000L))
        assertEquals(1, PlayerChapters.indexAt(chapters, 599_999L))
        assertEquals(2, PlayerChapters.indexAt(chapters, 700_000L))
    }

    @Test
    fun `index is -1 before the first chapter`() {
        assertEquals(-1, PlayerChapters.indexAt(listOf(PlayerChapter(5_000L, null), PlayerChapter(9_000L, null)), 1_000L))
    }

    @Test
    fun `next goes to the following chapter start`() {
        assertEquals(90_000L, PlayerChapters.nextStartMs(chapters, 0L))
        assertEquals(600_000L, PlayerChapters.nextStartMs(chapters, 90_000L))
        assertNull(PlayerChapters.nextStartMs(chapters, 600_000L))
    }

    @Test
    fun `previous restarts the chapter when well into it`() {
        assertEquals(90_000L, PlayerChapters.previousStartMs(chapters, 200_000L))
    }

    @Test
    fun `previous goes to the chapter before right after a start`() {
        assertEquals(0L, PlayerChapters.previousStartMs(chapters, 91_000L))
    }

    @Test
    fun `previous in the first chapter restarts it`() {
        assertEquals(0L, PlayerChapters.previousStartMs(chapters, 1_000L))
    }
}

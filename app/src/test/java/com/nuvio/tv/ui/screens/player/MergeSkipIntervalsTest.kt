package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeSkipIntervalsTest {

    private fun interval(type: String, start: Double, end: Double, provider: String) =
        SkipInterval(startTime = start, endTime = end, type = type, provider = provider)

    @Test
    fun `chapters take priority over API for same category`() {
        val api = listOf(interval("intro", 85.0, 175.0, "introdb"))
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(1, merged.size)
        assertEquals("chapters", merged[0].provider)
        assertEquals(90.0, merged[0].startTime, 0.01)
    }

    @Test
    fun `API fills categories missing from chapters`() {
        val api = listOf(
            interval("intro", 85.0, 175.0, "introdb"),
            interval("recap", 0.0, 60.0, "introdb")
        )
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(2, merged.size)
        val opening = merged.find { it.type == "intro" }!!
        val recap = merged.find { it.type == "recap" }!!
        assertEquals("chapters", opening.provider)
        assertEquals("introdb", recap.provider)
    }

    @Test
    fun `chapters fill categories missing from API`() {
        val api = listOf(interval("intro", 85.0, 175.0, "introdb"))
        val chapters = listOf(
            interval("intro", 90.0, 180.0, "chapters"),
            interval("outro", 1300.0, 1400.0, "chapters")
        )
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(2, merged.size)
        val opening = merged.find { it.type == "intro" }!!
        val ending = merged.find { it.type == "outro" }!!
        assertEquals("chapters", opening.provider)
        assertEquals("chapters", ending.provider)
    }

    @Test
    fun `empty chapters returns API as-is`() {
        val api = listOf(interval("intro", 85.0, 175.0, "introdb"))
        assertEquals(api, mergeSkipIntervals(api, emptyList()))
    }

    @Test
    fun `empty API returns chapters as-is`() {
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        assertEquals(chapters, mergeSkipIntervals(emptyList(), chapters))
    }

    @Test
    fun `uncategorized API intervals are preserved`() {
        val api = listOf(
            interval("intro", 85.0, 175.0, "introdb"),
            interval("post-credits", 1350.0, 1400.0, "introdb")
        )
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(2, merged.size)
        val postCredits = merged.find { it.type == "post-credits" }!!
        assertEquals("introdb", postCredits.provider)
    }

    @Test
    fun `both empty returns empty`() {
        assertEquals(emptyList<SkipInterval>(), mergeSkipIntervals(emptyList(), emptyList()))
    }

    @Test
    fun `movie mode prefers API entirely when it has data`() {
        val api = listOf(
            interval("movie-credits", 5400.0, 5700.0, "introdb"),
            interval("post-credits", 5700.0, 5800.0, "introdb")
        )
        val chapters = listOf(interval("outro", 5400.0, 6000.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters, isMovie = true)
        assertEquals(api, merged)
    }

    @Test
    fun `movie mode falls back to chapters when API is empty`() {
        val chapters = listOf(interval("outro", 5400.0, 6000.0, "chapters"))
        val merged = mergeSkipIntervals(emptyList(), chapters, isMovie = true)
        assertEquals(chapters, merged)
    }

    @Test
    fun `full merge with all categories`() {
        val api = listOf(
            interval("intro", 85.0, 175.0, "introdb"),
            interval("recap", 0.0, 60.0, "introdb"),
            interval("outro", 1280.0, 1380.0, "introdb"),
            interval("post-credits", 1380.0, 1440.0, "introdb")
        )
        val chapters = listOf(
            interval("recap", 0.0, 55.0, "chapters"),
            interval("intro", 90.0, 180.0, "chapters"),
            interval("outro", 1300.0, 1400.0, "chapters")
        )
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(4, merged.size)
        // All categorized come from chapters (priority)
        assertEquals("chapters", merged.find { it.type == "recap" }!!.provider)
        assertEquals("chapters", merged.find { it.type == "intro" }!!.provider)
        assertEquals("chapters", merged.find { it.type == "outro" }!!.provider)
        // post-credits only from API
        assertEquals("introdb", merged.find { it.type == "post-credits" }!!.provider)
    }
}

package com.nuvio.tv.data.trailer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppYouTubeExtractorCandidateFallbackTest {

    @Test
    fun `tries the next adaptive candidate when the first cannot be resolved`() = runTest {
        val attempted = mutableListOf<String>()

        val result = firstResolvedUrl(
            candidates = listOf("adaptive-2160p", "adaptive-1080p"),
            maxAttempts = 4
        ) { candidate ->
            attempted += candidate
            if (candidate == "adaptive-2160p") null else "resolved-$candidate"
        }

        assertEquals("resolved-adaptive-1080p", result)
        assertEquals(listOf("adaptive-2160p", "adaptive-1080p"), attempted)
    }

    @Test
    fun `stops probing after the first candidate resolves`() = runTest {
        val attempted = mutableListOf<String>()

        val result = firstResolvedUrl(
            candidates = listOf("adaptive-2160p", "adaptive-1080p"),
            maxAttempts = 4
        ) { candidate ->
            attempted += candidate
            "resolved-$candidate"
        }

        assertEquals("resolved-adaptive-2160p", result)
        assertEquals(listOf("adaptive-2160p"), attempted)
    }

    @Test
    fun `stops after the adaptive probe limit when all candidates fail`() = runTest {
        val candidates = (1..5).map { "adaptive-$it" }
        val attempted = mutableListOf<String>()

        val result = firstResolvedUrl(
            candidates = candidates,
            maxAttempts = 4
        ) { candidate ->
            attempted += candidate
            null
        }

        assertNull(result)
        assertEquals(candidates.take(4), attempted)
    }
}

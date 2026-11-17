package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingRewatchClosePolicyTest {

    @Test
    fun `a pending close holds the row when the next snapshot is empty`() {
        assertTrue(
            shouldHoldContinueWatchingDuringRewatchClose(
                currentMain = listOf(item("rewatch")),
                currentUpcoming = emptyList(),
                incomingMain = emptyList(),
                incomingUpcoming = emptyList(),
                rewatchClosePending = true
            )
        )
    }

    @Test
    fun `a pending close holds the row when the next snapshot is partial`() {
        assertTrue(
            shouldHoldContinueWatchingDuringRewatchClose(
                currentMain = listOf(item("rewatch"), item("other")),
                currentUpcoming = listOf(item("upcoming")),
                incomingMain = listOf(item("other")),
                incomingUpcoming = emptyList(),
                rewatchClosePending = true
            )
        )
    }

    @Test
    fun `a pending close holds a card that would leave at the same row size`() {
        assertTrue(
            shouldHoldContinueWatchingDuringRewatchClose(
                currentMain = listOf(item("rewatch"), item("other")),
                currentUpcoming = emptyList(),
                incomingMain = listOf(item("other"), item("new")),
                incomingUpcoming = emptyList(),
                rewatchClosePending = true
            )
        )
    }

    @Test
    fun `a pending close accepts a snapshot that keeps the row`() {
        assertFalse(
            shouldHoldContinueWatchingDuringRewatchClose(
                currentMain = listOf(item("rewatch")),
                currentUpcoming = emptyList(),
                incomingMain = listOf(item("rewatch")),
                incomingUpcoming = listOf(item("upcoming")),
                rewatchClosePending = true
            )
        )
    }

    @Test
    fun `a normal removal is not held after the close result is known`() {
        assertFalse(
            shouldHoldContinueWatchingDuringRewatchClose(
                currentMain = listOf(item("in-progress")),
                currentUpcoming = emptyList(),
                incomingMain = emptyList(),
                incomingUpcoming = emptyList(),
                rewatchClosePending = false
            )
        )
    }

    @Test
    fun `an empty row is not held while a close is pending`() {
        assertFalse(
            shouldHoldContinueWatchingDuringRewatchClose(
                currentMain = emptyList(),
                currentUpcoming = emptyList(),
                incomingMain = emptyList(),
                incomingUpcoming = emptyList(),
                rewatchClosePending = true
            )
        )
    }

    private fun item(contentId: String) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:1:1",
            season = 1,
            episode = 1,
            episodeTitle = null,
            position = 40L,
            duration = 100L,
            lastWatched = 100L
        )
    )
}

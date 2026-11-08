package com.nuvio.tv.data.local

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduces the watched-threshold write from #3918 against a large local history: several screens
 * observe the watched list while the player marks an episode watched and then records the push
 * timestamp. Checks decode work rather than timing, so the result does not depend on the machine.
 */
class WatchedItemsDecodeCostTest {

    @Test
    fun `marking an episode watched only decodes the new entry`() = runBlocking {
        withObservedHistory { preferences, collectors, decoded ->
            preferences.markAsWatched(item(HISTORY_SIZE), PROFILE_ID)

            val redecoded = collectors.sumOf { collector ->
                val items = collector.awaitEmission(2)
                assertEquals(HISTORY_SIZE + 1, items.size)
                items.count { item -> decoded[item.key()]?.let { it !== item } ?: false }
            }
            assertEquals(
                "history entries decoded again by $COLLECTORS collectors after one episode was marked",
                0,
                redecoded
            )
        }
    }

    @Test
    fun `push bookkeeping writes do not re-emit the watched list`() = runBlocking {
        withObservedHistory { preferences, collectors, _ ->
            preferences.advanceLastSuccessfulPushMs(1_000L, PROFILE_ID)
            delay(QUIET_WINDOW_MS)

            val extraEmissions = collectors.sumOf { it.emissions.value - 1 }
            assertEquals(
                "watched list emissions (each a full decode of $HISTORY_SIZE entries) after a push timestamp write",
                0,
                extraEmissions
            )
        }
    }

    private suspend fun withObservedHistory(
        block: suspend (WatchedItemsPreferences, List<Collector>, Map<Triple<String, Int?, Int?>, WatchedItem>) -> Unit
    ) {
        val store = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(PROFILE_ID)
        val preferences = WatchedItemsPreferences(factory, profileManager)

        preferences.markAsWatchedBatch((0 until HISTORY_SIZE).map(::item), PROFILE_ID)
        // The app has been running, so the history was decoded before playback reached the threshold.
        val decoded = preferences.getAllItems(PROFILE_ID).associateBy { it.key() }

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val collectors = List(COLLECTORS) { Collector(scope, preferences.observeAllItems(PROFILE_ID)) }
            collectors.forEach { assertEquals(HISTORY_SIZE, it.awaitEmission(1).size) }
            block(preferences, collectors, decoded)
        } finally {
            scope.cancel()
        }
    }

    private class Collector(scope: CoroutineScope, flow: Flow<List<WatchedItem>>) {
        val emissions = MutableStateFlow(0)

        @Volatile
        private var latest: List<WatchedItem> = emptyList()

        init {
            scope.launch {
                flow.collect { items ->
                    latest = items
                    emissions.update { it + 1 }
                }
            }
        }

        suspend fun awaitEmission(count: Int): List<WatchedItem> = withTimeout(AWAIT_TIMEOUT_MS) {
            emissions.first { it >= count }
            latest
        }
    }

    private fun WatchedItem.key() = Triple(contentId, season, episode)

    private fun item(index: Int) = WatchedItem(
        contentId = "tt${1_000_000 + index / EPISODES_PER_SHOW}",
        contentType = "series",
        title = "Show ${index / EPISODES_PER_SHOW}",
        season = 1,
        episode = index % EPISODES_PER_SHOW + 1,
        watchedAt = 1_700_000_000_000L + index,
        poster = "https://example.com/poster/${index / EPISODES_PER_SHOW}.jpg"
    )

    private companion object {
        const val PROFILE_ID = 1
        const val HISTORY_SIZE = 10_000
        const val EPISODES_PER_SHOW = 20
        // Roughly the number of live watched-list collectors while the player is open.
        const val COLLECTORS = 10
        const val QUIET_WINDOW_MS = 1_000L
        const val AWAIT_TIMEOUT_MS = 30_000L
    }
}

package com.nuvio.tv.data.local

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Single items marked watched while a tracking provider is the watch progress source are kept as
 * pending marks until the provider reports them, since it may never know them.
 */
class WatchedItemsPendingProviderMarksTest {

    @Test
    fun `a mark made with a provider as the source stays pending until it is unmarked`() = runTest {
        val preferences = harness()

        preferences.markAsWatched(item(VIDEO), profileId = 1, pendingProviderMark = true)
        assertEquals(setOf(VIDEO), preferences.observePendingProviderMarks(1).first())

        preferences.unmarkAsWatched(VIDEO, profileId = 1)
        assertEquals(emptySet<String>(), preferences.observePendingProviderMarks(1).first())
        assertEquals(emptyList<WatchedItem>(), preferences.getAllItems(1))
    }

    @Test
    fun `episodes and marks made without a provider are never pending`() = runTest {
        val preferences = harness()

        preferences.markAsWatched(item("tt_show", season = 1, episode = 2), profileId = 1, pendingProviderMark = true)
        preferences.markAsWatched(item("tt_movie"), profileId = 1)

        assertEquals(emptySet<String>(), preferences.observePendingProviderMarks(1).first())
    }

    @Test
    fun `an acknowledged mark stops being pending and stays marked`() = runTest {
        val preferences = harness()
        preferences.markAsWatched(item(MOVIE), profileId = 1, pendingProviderMark = true)

        preferences.acknowledgePendingProviderMarks(setOf(MOVIE), profileId = 1)

        assertEquals(emptySet<String>(), preferences.observePendingProviderMarks(1).first())
        assertEquals(listOf(MOVIE), preferences.getAllItems(1).map { it.contentId })
    }

    @Test
    fun `a source switch keeps pending marks and drops everything else`() = runTest {
        val preferences = harness()
        preferences.markAsWatched(item(VIDEO), profileId = 1, pendingProviderMark = true)
        preferences.markAsWatched(item(MOVIE), profileId = 1)
        preferences.markAsWatched(item("tt_show", season = 1, episode = 2), profileId = 1)

        preferences.clearAllKeepingPendingProviderMarks(profileId = 1)

        assertEquals(listOf(VIDEO), preferences.getAllItems(1).map { it.contentId })
        assertEquals(setOf(VIDEO), preferences.observePendingProviderMarks(1).first())
    }

    @Test
    fun `a pending mark whose item is gone is not reported`() = runTest {
        val preferences = harness()
        preferences.markAsWatched(item(VIDEO, watchedAt = 50L), profileId = 1, pendingProviderMark = true)

        // A Nuvio sync snapshot that no longer has it, taken after it was pushed.
        preferences.replaceWithRemoteItems(remoteItems = emptyList(), lastSuccessfulPushMs = 100L, profileId = 1)

        assertEquals(emptySet<String>(), preferences.observePendingProviderMarks(1).first())
    }

    @Test
    fun `clearing everything also forgets pending marks`() = runTest {
        val preferences = harness()
        preferences.markAsWatched(item(VIDEO), profileId = 1, pendingProviderMark = true)

        preferences.clearAll(profileId = 1)
        preferences.markAsWatched(item(VIDEO), profileId = 1)

        assertEquals(emptySet<String>(), preferences.observePendingProviderMarks(1).first())
    }

    private fun harness(): WatchedItemsPreferences {
        val store = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return WatchedItemsPreferences(factory, profileManager)
    }

    private fun item(contentId: String, season: Int? = null, episode: Int? = null, watchedAt: Long = 10L) =
        WatchedItem(
            contentId = contentId,
            contentType = if (season != null) "series" else "youtube",
            title = contentId,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

    private companion object {
        const val VIDEO = "yt_id:abc123"
        const val MOVIE = "tt0000001"
    }
}

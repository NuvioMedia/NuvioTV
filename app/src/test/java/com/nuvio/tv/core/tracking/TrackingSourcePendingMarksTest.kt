package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingSourcePendingMarksTest {

    @Test
    fun `switching to a provider keeps the marks still waiting for a provider`() = runTest {
        val settings = mockk<TraktSettingsDataStore>(relaxed = true)
        coEvery { settings.getWatchProgressSource(1) } returns WatchProgressSource.NUVIO_SYNC
        val provider = mockk<TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns TrackingProviderId.TRAKT
        val watchedItems = mockk<WatchedItemsPreferences>(relaxed = true)
        val controller = TrackingSourceController(
            settings, TrackingProgressProviderRegistry(setOf(provider)), TrackingLibraryProviderRegistry(emptySet()),
            mockk(relaxed = true), watchedItems, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true),
            mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        )

        controller.selectWatchProgressSource(WatchProgressSource.TRAKT)

        coVerify(exactly = 1) { watchedItems.clearAllKeepingPendingProviderMarks(1) }
        coVerify(exactly = 0) { watchedItems.clearAll(any()) }
    }
}

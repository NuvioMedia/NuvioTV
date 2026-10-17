package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingLibrarySourceControllerTest {
    @Test
    fun `each library source refreshes its registered provider after selection`() = runTest {
        for (mode in listOf(LibrarySourceMode.TRAKT, LibrarySourceMode.SIMKL, LibrarySourceMode.MDBLIST)) {
            val settings = mockk<TraktSettingsDataStore>(relaxed = true)
            coEvery { settings.getLibrarySourceMode(1) } returns LibrarySourceMode.LOCAL
            val provider = mockk<TrackingLibraryProvider>(relaxed = true) { every { providerId } returns requireNotNull(mode.providerId) }
            val profiles = MutableStateFlow(1)
            val controller = controller(settings, provider, profiles)
            controller.selectLibrarySourceMode(mode)
            coVerify(exactly = 1) { settings.setLibrarySourceMode(mode, 1) }
            coVerify(exactly = 1) { provider.refresh(TrackingRefreshIntent.USER_INITIATED) }
        }
    }

    @Test
    fun `changing profiles during source selection cannot refresh the new profile provider`() = runTest {
        val profiles = MutableStateFlow(1)
        val settings = mockk<TraktSettingsDataStore>(relaxed = true)
        coEvery { settings.getLibrarySourceMode(1) } returns LibrarySourceMode.LOCAL
        coEvery { settings.setLibrarySourceMode(LibrarySourceMode.MDBLIST, 1) } answers { profiles.value = 2 }
        val provider = mockk<TrackingLibraryProvider>(relaxed = true) { every { providerId } returns TrackingProviderId.MDBLIST }
        controller(settings, provider, profiles).selectLibrarySourceMode(LibrarySourceMode.MDBLIST)
        coVerify(exactly = 0) { provider.refresh(any()) }
    }

    private fun controller(settings: TraktSettingsDataStore, provider: TrackingLibraryProvider, profiles: MutableStateFlow<Int>) =
        TrackingSourceController(
            settings, TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(setOf(provider)),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk<ProfileManager> { every { activeProfileId } returns profiles }
        )
}

package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingSourceMemoryTest {
    private class Stored(
        var progress: WatchProgressSource,
        var library: LibrarySourceMode,
        var rememberedProgress: WatchProgressSource? = null,
        var rememberedLibrary: LibrarySourceMode? = null
    )

    @Test
    fun `logging out falls back and logging back in restores the choice`() = runTest {
        val stored = Stored(WatchProgressSource.TRAKT, LibrarySourceMode.TRAKT)
        val controller = controller(stored)

        controller.reconcileConnectedProviders(emptySet())
        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertEquals(WatchProgressSource.TRAKT, stored.rememberedProgress)
        assertEquals(LibrarySourceMode.TRAKT, stored.rememberedLibrary)

        controller.reconcileConnectedProviders(setOf(TrackingProviderId.TRAKT))
        assertEquals(WatchProgressSource.TRAKT, stored.progress)
        assertEquals(LibrarySourceMode.TRAKT, stored.library)
        assertNull(stored.rememberedProgress)
        assertNull(stored.rememberedLibrary)
    }

    @Test
    fun `another provider connecting keeps the fallback and the remembered choice`() = runTest {
        val stored = Stored(
            WatchProgressSource.NUVIO_SYNC,
            LibrarySourceMode.LOCAL,
            rememberedProgress = WatchProgressSource.TRAKT,
            rememberedLibrary = LibrarySourceMode.TRAKT
        )

        controller(stored).reconcileConnectedProviders(setOf(TrackingProviderId.SIMKL))

        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertEquals(WatchProgressSource.TRAKT, stored.rememberedProgress)
        assertEquals(LibrarySourceMode.TRAKT, stored.rememberedLibrary)
    }

    @Test
    fun `choosing a source by hand forgets the remembered one`() = runTest {
        val stored = Stored(
            WatchProgressSource.NUVIO_SYNC,
            LibrarySourceMode.LOCAL,
            rememberedProgress = WatchProgressSource.TRAKT,
            rememberedLibrary = LibrarySourceMode.TRAKT
        )
        val controller = controller(stored)

        controller.selectWatchProgressSource(WatchProgressSource.NUVIO_SYNC)
        controller.selectLibrarySourceMode(LibrarySourceMode.LOCAL)
        controller.reconcileConnectedProviders(setOf(TrackingProviderId.TRAKT))

        assertEquals(WatchProgressSource.NUVIO_SYNC, stored.progress)
        assertEquals(LibrarySourceMode.LOCAL, stored.library)
        assertNull(stored.rememberedProgress)
        assertNull(stored.rememberedLibrary)
    }

    private fun controller(stored: Stored): TrackingSourceController {
        val settings = mockk<TraktSettingsDataStore>(relaxed = true)
        coEvery { settings.getWatchProgressSource(1) } answers { stored.progress }
        coEvery { settings.getLibrarySourceMode(1) } answers { stored.library }
        coEvery { settings.getRememberedWatchProgressSource(1) } answers { stored.rememberedProgress }
        coEvery { settings.getRememberedLibrarySourceMode(1) } answers { stored.rememberedLibrary }
        coEvery { settings.setWatchProgressSource(any(), 1) } answers { stored.progress = firstArg() }
        coEvery { settings.setLibrarySourceMode(any(), 1) } answers { stored.library = firstArg() }
        coEvery { settings.setRememberedWatchProgressSource(any(), 1) } answers { stored.rememberedProgress = firstArg() }
        coEvery { settings.setRememberedLibrarySourceMode(any(), 1) } answers { stored.rememberedLibrary = firstArg() }
        return TrackingSourceController(
            settings, TrackingProgressProviderRegistry(emptySet()), TrackingLibraryProviderRegistry(emptySet()),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        )
    }
}

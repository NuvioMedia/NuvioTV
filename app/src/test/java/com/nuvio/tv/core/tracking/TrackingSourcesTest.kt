package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingSourcesTest {
    @Test
    fun `legacy source names retain their stored meaning`() {
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("TRAKT"))
        assertEquals(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.fromStorage("NUVIO_SYNC"))
        assertEquals(LibrarySourceMode.TRAKT, LibrarySourceMode.valueOf("TRAKT"))
    }

    @Test
    fun `remote watch source falls back to Nuvio Sync when disconnected`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { false }
        )
        assertEquals(
            WatchProgressSource.SIMKL,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { false }
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `local sources do not map to remote providers`() {
        assertNull(WatchProgressSource.NUVIO_SYNC.providerId)
        assertNull(LibrarySourceMode.LOCAL.providerId)
    }

    @Test
    fun `source reconciliation covers every provider connection combination`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.NUVIO_SYNC,
                LibrarySourceMode.LOCAL
            ),
            effectiveTrackingSourceSelection(requested, emptySet())
        )
        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.NUVIO_SYNC,
                LibrarySourceMode.TRAKT
            ),
            effectiveTrackingSourceSelection(requested, setOf(TrackingProviderId.TRAKT))
        )
        assertEquals(
            TrackingSourceSelection(
                WatchProgressSource.SIMKL,
                LibrarySourceMode.LOCAL
            ),
            effectiveTrackingSourceSelection(requested, setOf(TrackingProviderId.SIMKL))
        )
        assertEquals(
            requested,
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)
            )
        )
    }

    @Test
    fun `disconnecting an active provider resets only its selected source`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            TrackingSourceSelection(
                watchProgressSource = WatchProgressSource.NUVIO_SYNC,
                librarySourceMode = LibrarySourceMode.TRAKT
            ),
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT)
            )
        )
    }

    @Test
    fun `disconnecting an inactive provider preserves selected sources`() {
        val requested = TrackingSourceSelection(
            watchProgressSource = WatchProgressSource.TRAKT,
            librarySourceMode = LibrarySourceMode.TRAKT
        )

        assertEquals(
            requested,
            effectiveTrackingSourceSelection(
                requested,
                setOf(TrackingProviderId.TRAKT)
            )
        )
    }

    @Test
    fun `both connected pickers expose Nuvio Trakt and Simkl in stable order`() {
        val connected = setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)

        assertEquals(
            listOf(
                WatchProgressSource.NUVIO_SYNC,
                WatchProgressSource.TRAKT,
                WatchProgressSource.SIMKL
            ),
            availableWatchProgressSources(connected)
        )
        assertEquals(
            listOf(
                LibrarySourceMode.LOCAL,
                LibrarySourceMode.TRAKT,
                LibrarySourceMode.SIMKL
            ),
            availableLibrarySourceModes(connected)
        )
    }

    @Test
    fun `disconnected providers are excluded from source pickers`() {
        assertEquals(
            listOf(WatchProgressSource.NUVIO_SYNC),
            availableWatchProgressSources(emptySet())
        )
        assertEquals(
            listOf(LibrarySourceMode.LOCAL),
            availableLibrarySourceModes(emptySet())
        )
        assertEquals(
            listOf(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.SIMKL),
            availableWatchProgressSources(setOf(TrackingProviderId.SIMKL))
        )
        assertEquals(
            listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.TRAKT),
            availableLibrarySourceModes(setOf(TrackingProviderId.TRAKT))
        )
    }
}

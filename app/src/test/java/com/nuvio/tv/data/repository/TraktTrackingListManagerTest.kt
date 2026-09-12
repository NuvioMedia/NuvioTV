package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.domain.model.LibraryListPrivacy
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktTrackingListManagerTest {
    @Test
    fun `shared management preserves Trakt privacy descriptions and list ordering`() = runTest {
        val service = mockk<TraktLibraryService>(relaxed = true)
        val manager = TraktTrackingLibraryProvider(service, mockk<TraktAuthDataStore>(relaxed = true)).listManager
        assertTrue(manager.capabilities.supportsDescription)
        assertTrue(manager.capabilities.supportsReordering)
        assertEquals(LibraryListPrivacy.entries, manager.capabilities.privacyOptions)
        val prefix = TraktLibraryService.PERSONAL_KEY_PREFIX
        manager.createList("List", "Description", LibraryListPrivacy.FRIENDS)
        manager.updateList("${prefix}7", "Renamed", "Changed", LibraryListPrivacy.LINK)
        manager.reorderLists(listOf("${prefix}8", "${prefix}7"))
        manager.deleteList("${prefix}7")
        coVerify { service.createPersonalList("List", "Description", LibraryListPrivacy.FRIENDS) }
        coVerify { service.updatePersonalList("7", "Renamed", "Changed", LibraryListPrivacy.LINK) }
        coVerify { service.reorderPersonalLists(listOf("8", "7")) }
        coVerify { service.deletePersonalList("7") }
    }

    @Test
    fun `Trakt manager rejects MDBList keys before calling Trakt`() = runTest {
        val service = mockk<TraktLibraryService>(relaxed = true)
        val manager = TraktTrackingLibraryProvider(service, mockk<TraktAuthDataStore>(relaxed = true)).listManager
        assertTrue(runCatching { manager.deleteList("mdblist:list:7") }.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { service.deletePersonalList(any()) }
    }
}

package com.nuvio.tv.core.livetv

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvImportResult
import com.nuvio.tv.domain.model.LiveTvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveTvGuideCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun compressedGuideRoundTripsAndCanBeReadWhenWritesAreDisabled() = kotlinx.coroutines.runBlocking {
        val cacheDir = temporaryFolder.newFolder("epg")
        val cache = LiveTvGuideCache(cacheDir, 1024L * 1024L, writesEnabled = true)
        val expected = sampleResult()

        assertTrue(cache.write("source-a", expected, updatedEpochMillis = 1234L))
        val persistedBytes = cacheDir.listFiles()?.singleOrNull()?.length() ?: 0L
        assertTrue(persistedBytes in 1L..1024L * 1024L)

        val offlineReadOnlyCache = LiveTvGuideCache(cacheDir, 1024L * 1024L, writesEnabled = false)
        val actual = offlineReadOnlyCache.read("source-a")
        assertNotNull(actual)
        assertEquals(1234L, actual?.updatedEpochMillis)
        assertEquals(expected, actual?.result)
        assertFalse(offlineReadOnlyCache.write("source-b", expected))
    }

    @Test
    fun updatingSourceReplacesItsCacheEntryAtomically() = kotlinx.coroutines.runBlocking {
        val cacheDir = temporaryFolder.newFolder("replace-epg")
        val cache = LiveTvGuideCache(cacheDir, 1024L * 1024L, writesEnabled = true)
        val original = sampleResult()
        val updated = original.copy(programs = listOf(
            original.programs.single().copy(title = "Jornal atualizado")
        ))

        assertTrue(cache.write("source-a", original, updatedEpochMillis = 1L))
        assertTrue(cache.write("source-a", updated, updatedEpochMillis = 2L))

        assertEquals(updated, cache.read("source-a")?.result)
        assertEquals(2L, cache.read("source-a")?.updatedEpochMillis)
        assertEquals(1, cacheDir.listFiles()?.count { it.name.endsWith(".json.gz") })
    }

    @Test
    fun refusesPayloadLargerThanTheEpgShareAndIgnoresUnknownSourceIds() = kotlinx.coroutines.runBlocking {
        val cacheDir = temporaryFolder.newFolder("small-epg")
        val cache = LiveTvGuideCache(cacheDir, quotaBytes = 1L, writesEnabled = true)

        assertFalse(cache.write("source-a", sampleResult()))
        assertNull(cache.read("source-a"))
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun evictsOlderSourceCacheToStayWithinTheEpgShare() = kotlinx.coroutines.runBlocking {
        val cacheDir = temporaryFolder.newFolder("bounded-epg")
        val first = LiveTvGuideCache(cacheDir, 1024L * 1024L, writesEnabled = true)
        assertTrue(first.write("source-a", sampleResult(), updatedEpochMillis = 1L))
        val entrySize = cacheDir.listFiles()?.singleOrNull()?.length() ?: error("cache file missing")

        val singleEntryBudget = LiveTvGuideCache(cacheDir, entrySize, writesEnabled = true)
        assertTrue(singleEntryBudget.write("source-b", sampleResult(), updatedEpochMillis = 2L))

        assertNull(singleEntryBudget.read("source-a"))
        assertNotNull(singleEntryBudget.read("source-b"))
        assertTrue(cacheDir.listFiles().orEmpty().filter { it.name.endsWith(".json.gz") }.sumOf { it.length() } <= entrySize)
    }

    private fun sampleResult() = LiveTvImportResult(
        channels = listOf(LiveTvChannel("source-a:hash", "source-a", "Canal Brasil", "https://example.org/live.m3u8", tvgId = "br-1")),
        programs = listOf(LiveTvProgram("source-a", "br-1", "Jornal", 1000L, 2000L, description = "Notícias")),
    )
}

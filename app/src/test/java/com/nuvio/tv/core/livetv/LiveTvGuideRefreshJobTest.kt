package com.nuvio.tv.core.livetv

import com.nuvio.tv.data.local.LiveTvSourceStore
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvImportResult
import com.nuvio.tv.domain.model.LiveTvSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvGuideRefreshJobTest {
    @Test fun refreshesOnlyEnabledSourcesAndRetainsSuccessfulGuideWhenAnotherFails() = runTest {
        val sourceStore = mockk<LiveTvSourceStore>()
        val importer = mockk<LiveTvSourceImporter>()
        val guideCache = mockk<LiveTvGuideCache>()
        val successful = LiveTvSource("source-a", "Guide A", "https://example.org/a.m3u")
        val failed = LiveTvSource("source-b", "Guide B", "https://example.org/b.m3u")
        val disabled = LiveTvSource("source-c", "Guide C", "https://example.org/c.m3u", enabled = false)
        val result = LiveTvImportResult(listOf(LiveTvChannel("source-a:1", "source-a", "Canal A")))
        coEvery { sourceStore.list() } returns listOf(successful, failed, disabled)
        coEvery { importer.import(successful) } returns result
        coEvery { importer.import(failed) } throws IllegalStateException("Fonte indisponível")
        coEvery { guideCache.write(successful.id, result, any()) } returns true
        val job = LiveTvGuideRefreshJob(sourceStore, importer, guideCache)

        val summary = job.run()

        assertEquals(LiveTvGuideRefreshSummary(sourceCount = 2, refreshedCount = 1, failedCount = 1), summary)
        coVerify(exactly = 0) { importer.import(disabled) }
        coVerify(exactly = 1) { guideCache.write(successful.id, result, any()) }
        coVerify(exactly = 0) { guideCache.write(failed.id, any(), any()) }
    }

    @Test fun noSourcesIsAnIdleSuccessfulRefresh() = runTest {
        val sourceStore = mockk<LiveTvSourceStore>()
        val importer = mockk<LiveTvSourceImporter>()
        val guideCache = mockk<LiveTvGuideCache>()
        coEvery { sourceStore.list() } returns emptyList()

        assertEquals(LiveTvGuideRefreshSummary(0, 0, 0), LiveTvGuideRefreshJob(sourceStore, importer, guideCache).run())
        coVerify(exactly = 0) { importer.import(any()) }
    }
}

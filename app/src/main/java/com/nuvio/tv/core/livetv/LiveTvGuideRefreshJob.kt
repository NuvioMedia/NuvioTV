package com.nuvio.tv.core.livetv

import com.nuvio.tv.data.local.LiveTvSourceStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class LiveTvGuideRefreshSummary(val sourceCount: Int, val refreshedCount: Int, val failedCount: Int)

/** Executes one bounded-by-source refresh pass; failures keep the last cached guide intact. */
@Singleton
class LiveTvGuideRefreshJob @Inject constructor(
    private val sourceStore: LiveTvSourceStore,
    private val importer: LiveTvSourceImporter,
    private val guideCache: LiveTvGuideCache,
) {
    suspend fun run(): LiveTvGuideRefreshSummary {
        val sources = sourceStore.list().filter { it.enabled }
        var refreshed = 0
        var failed = 0
        for (source in sources) {
            try {
                val result = importer.import(source)
                guideCache.write(source.id, result)
                refreshed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not log source URLs or overwrite an existing offline guide on failure.
                failed++
            }
        }
        return LiveTvGuideRefreshSummary(sources.size, refreshed, failed)
    }
}

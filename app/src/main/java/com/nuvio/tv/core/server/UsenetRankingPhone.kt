package com.nuvio.tv.core.server

import com.nuvio.tv.core.streams.*
import com.nuvio.tv.core.usenet.UsenetSort
import kotlinx.serialization.Serializable

@Serializable internal data class PhoneRankingPreset(
    val id: StreamRankingPreset,
    val name: String,
    val description: String,
    val profile: StreamRankingProfile
)
@Serializable internal data class PhoneRanking(
    val settings: StreamRankingSettings,
    val profile: StreamRankingProfile,
    val presets: List<PhoneRankingPreset>,
    val languages: List<PhoneRankingLanguage> = emptyList()
)
@Serializable internal data class PhoneRankingLanguage(val code: String, val name: String)
@Serializable internal data class RankingPreviewSample(
    val title: String,
    val sizeGb: Double? = null,
    val ageDays: Double? = null,
    val indexer: String = ""
)
@Serializable internal data class RankingPreviewRequest(
    val settings: StreamRankingSettings,
    val samples: List<RankingPreviewSample>,
    val mediaType: String = "movie"
) {
    fun validate() {
        settings.validate()
        require(mediaType in listOf("movie", "series")) { "Choose movies or episodes" }
        require(samples.size in 1..50) { "Preview between 1 and 50 releases" }
        samples.forEach {
            require(it.title.isNotBlank() && it.title.length <= 2048 && it.indexer.length <= 80) { "A sample title is missing or too long" }
            require(it.sizeGb == null || it.sizeGb.isFinite() && it.sizeGb in 0.0..10000.0) { "Sample sizes must be between 0 and 10000 GB" }
            require(it.ageDays == null || it.ageDays.isFinite() && it.ageDays in 0.0..100000.0) { "Sample ages must be between 0 and 100000 days" }
        }
    }
}
@Serializable internal data class RankingPreviewRow(
    val title: String, val score: Int, val tier: String?, val matches: List<String>, val overflow: Boolean
)
@Serializable internal data class RankingPreview(val results: List<RankingPreviewRow>, val excluded: List<String>, val profile: StreamRankingProfile)

internal object UsenetRankingPhone {
    /** A native equivalent of the original comparator, for editing/previewing an existing setup. */
    fun original(sort: UsenetSort): StreamRankingProfile {
        val primary = when (sort) {
            UsenetSort.QUALITY -> StreamRankingSort(StreamRankingKey.RESOLUTION)
            UsenetSort.LARGEST -> StreamRankingSort(StreamRankingKey.SIZE)
            UsenetSort.SMALLEST -> StreamRankingSort(StreamRankingKey.SIZE, false)
            UsenetSort.NEWEST -> StreamRankingSort(StreamRankingKey.AGE)
            UsenetSort.INDEXER -> StreamRankingSort(StreamRankingKey.INDEXER)
        }
        return StreamRankingProfile(name = "Current sorting", topPerBucket = 0,
            sort = (listOf(primary, StreamRankingSort(StreamRankingKey.INDEXER), StreamRankingSort(StreamRankingKey.RESOLUTION),
                StreamRankingSort(StreamRankingKey.SIZE))).distinctBy { it.key })
    }
    fun preview(request: RankingPreviewRequest, original: StreamRankingProfile,
        indexerOrder: Map<String, Int> = emptyMap()): RankingPreview {
        request.validate()
        val now = System.currentTimeMillis()
        val candidates = request.samples.mapIndexed { index, sample ->
            val facts = StreamRankingFacts.parse(sample.title).copy(
                sizeBytes = sample.sizeGb?.takeIf { it > 0 }?.times(StreamRankingEngine.GIB)?.toLong(),
                publishedAt = sample.ageDays?.let { now - (it * 86_400_000).toLong() },
                indexer = sample.indexer, mediaType = request.mediaType)
            StreamRankingCandidate(index, facts, index.toString(),
                indexerOrder = indexerOrder[sample.indexer.lowercase(java.util.Locale.ROOT)] ?: Int.MAX_VALUE)
        }
        val profile = request.settings.profile() ?: original
        val results = StreamRankingEngines.get(profile).rank(candidates, now)
        val kept = results.map { it.candidate.value }.toSet()
        return RankingPreview(results.map { RankingPreviewRow(it.candidate.facts.title, it.score, it.tier, it.matches, it.overflow) },
            request.samples.mapIndexedNotNull { i, sample -> sample.title.takeIf { i !in kept } }, profile)
    }
}

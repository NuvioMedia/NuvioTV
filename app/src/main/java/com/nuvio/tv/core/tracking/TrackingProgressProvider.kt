package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface TrackingProgressProvider {
    val providerId: TrackingProviderId
    val isAuthenticated: Flow<Boolean>
    val allProgress: Flow<List<WatchProgress>>
    val remoteProgressLoaded: Flow<Boolean>
    val nextUpSeeds: Flow<List<WatchProgress>>
    val watchedMovieIds: Flow<Set<String>>

    /**
     * The ids in [watchedMovieIds] that the provider itself reports or has accepted, without
     * optimistic entries it has not confirmed. A single item marked watched while this provider is
     * the source stays a pending local mark until it shows up here.
     */
    val reportedWatchedMovieIds: Flow<Set<String>>
        get() = watchedMovieIds

    val ownsCompletedHistoryProjection: Boolean
        get() = false
    val clearsLocalProgressOnSelection: Boolean
        get() = false
    val watchedItems: Flow<List<WatchedItem>>
        get() = flowOf(emptyList())

    fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>>
    fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean>
    suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>
    suspend fun showIdSiblings(): Map<String, Set<String>>
    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? = null
    suspend fun refresh(intent: TrackingRefreshIntent)
    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?)
    fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean)
    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)
    fun clearOptimistic()
    fun retainsLocalProgress(contentId: String): Boolean = false
    fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = false
    fun isHiddenFromProgress(contentId: String): Boolean
    fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? = null
    fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        progress.isCompleted()
    fun normalizeParentContentId(parentContentId: String, videoId: String?): String = parentContentId
    suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress

    /**
     * The progress, in percent, from which this source counts a stop as a watch, for a playback whose
     * content ends at [contentEndPercent]. Null when the source has no bar of its own.
     *
     * While the provider is the progress source, what it reports is what the app shows as watched,
     * so the local completion follows its bar instead of deciding against it.
     */
    suspend fun completionThresholdPercent(contentEndPercent: Double?): Double? = null
}

/**
 * The completion threshold a local progress row carries, as the fraction [WatchProgress] reads, when
 * the progress source counts a watch only from a higher point than the app's own bar.
 *
 * It only ever raises the bar: below it the row and the source already agree, and a source that
 * counts a watch earlier shows it as watched from its own data anyway.
 */
internal fun localCompletionThresholdFor(sourceThresholdPercent: Double?): Float? {
    val fraction = sourceThresholdPercent
        ?.takeIf { percent -> percent.isFinite() }
        ?.let { percent -> (percent / 100.0).toFloat() }
        ?: return null
    if (fraction <= WatchProgress.COMPLETED_THRESHOLD) return null
    return fraction.coerceAtMost(1f)
}

@Singleton
class TrackingProgressProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingProgressProvider>
) {
    private val providersById = providers.associateBy(TrackingProgressProvider::providerId)

    init {
        require(providersById.size == providers.size)
    }

    fun providers(): List<TrackingProgressProvider> =
        providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingProgressProvider? = providersById[id]
}

@Singleton
class TrackingHistoryWriterRegistry @Inject constructor(
    writers: Set<@JvmSuppressWildcards TrackingHistoryWriter>
) {
    private val writersById = writers.associateBy(TrackingHistoryWriter::providerId)

    init {
        require(writersById.size == writers.size)
    }

    fun writers(): List<TrackingHistoryWriter> =
        writersById.values.sortedBy { it.providerId.ordinal }

    fun writer(id: TrackingProviderId): TrackingHistoryWriter? = writersById[id]
}

package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal const val WETRAKR_WATCHLIST_KEY = "wetrakr:watchlist"

@Singleton
class WeTrakrTrackingLibraryProvider @Inject constructor(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    auth: WeTrakrAuthStore,
    profiles: ProfileManager,
    tracking: WeTrakrTrackingProvider
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.WETRAKR
    override val isAuthenticated = tracking.isAuthenticated
    private val watchlist: Flow<List<WeTrakrWatchlistItem>?> =
        combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
            state.snapshot?.takeIf {
                authorization.isAuthenticated && state.scope == authorization.scope &&
                    authorization.scope.profileId == profileId && it.isInitialized
            }?.watchlist
        }.distinctUntilChanged()
    override val isRefreshing = sync.state.map { it.isLoading }.distinctUntilChanged()
    override val items = watchlist.map { items -> weTrakrLibraryEntries(items.orEmpty()) }.loadOnStart()
    override val tabs = watchlist.map { items -> if (items == null) emptyList() else listOf(WATCHLIST_TAB) }.loadOnStart()

    override fun recognizesListKey(key: String): Boolean = key == WETRAKR_WATCHLIST_KEY

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> = watchlist.map { items ->
        val type = weTrakrLibraryType(itemType)
        if (items.orEmpty().any { it.type == type && itemId in it.media.ids.aliases() }) setOf(WETRAKR_WATCHLIST_KEY) else emptySet()
    }.distinctUntilChanged()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership + (WETRAKR_WATCHLIST_KEY to (currentMembership[WETRAKR_WATCHLIST_KEY] != true))

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        sync.ensureLoaded()
        val target = item.weTrakrLibraryTarget()
        val listed = sync.currentSnapshot()?.watchlist.orEmpty().any { listed ->
            target != null && listed.type == target.type && listed.media.ids.matches(target.media.ids)
        }
        return ListMembershipSnapshot(mapOf(WETRAKR_WATCHLIST_KEY to listed))
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        val desired = changes.desiredMembership[WETRAKR_WATCHLIST_KEY] ?: return
        val target = requireNotNull(item.weTrakrLibraryTarget()) { "WeTrakr needs an IMDb, TMDB or TVDB id" }
        val scope = sync.currentScope()
        sync.write(scope, setOf(WeTrakrSyncBucket.WATCHLIST)) { snapshot ->
            val listed = snapshot.watchlist.any { it.type == target.type && it.media.ids.matches(target.media.ids) }
            if (listed == desired) return@write snapshot to Unit
            api.post(
                if (desired) "/sync/tracking" else "/sync/tracking/remove",
                weTrakrTrackingBody(listOf(WeTrakrTrackingChange(target, null)), "planning"),
                scope
            )
            val remaining = snapshot.watchlist.filterNot { it.type == target.type && it.media.ids.matches(target.media.ids) }
            val next = if (desired) {
                remaining + WeTrakrWatchlistItem(target.type, target.media, Instant.now().toString())
            } else remaining
            snapshot.copy(watchlist = next) to Unit
        }
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()

    private companion object {
        val WATCHLIST_TAB = LibraryListTab(
            WETRAKR_WATCHLIST_KEY, "Watchlist", LibraryListTab.Type.WATCHLIST,
            trackingProviderId = WeTrakrProgressProjection.PROVIDER_ID,
            supportedContentTypes = setOf("movie", "series")
        )
    }
}

internal fun weTrakrLibraryType(itemType: String): WeTrakrItemType =
    if (itemType.trim().lowercase() in setOf("movie", "film")) WeTrakrItemType.MOVIE else WeTrakrItemType.SHOW

internal fun LibraryEntryInput.weTrakrLibraryTarget(): WeTrakrTarget? {
    val fromItemId = when {
        itemId.startsWith("tt") -> WeTrakrIds(imdb = itemId.substringBefore(':'))
        itemId.startsWith("imdb:") -> WeTrakrIds(imdb = itemId.removePrefix("imdb:").substringBefore(':'))
        itemId.startsWith("tmdb:") -> WeTrakrIds(tmdb = itemId.removePrefix("tmdb:").substringBefore(':').toLongOrNull())
        itemId.startsWith("tvdb:") -> WeTrakrIds(tvdb = itemId.removePrefix("tvdb:").substringBefore(':').toLongOrNull())
        else -> WeTrakrIds()
    }
    val ids = WeTrakrIds(
        imdb = (imdbId ?: fromItemId.imdb)?.takeIf { it.matches(Regex("tt[0-9]+")) },
        tmdb = (tmdbId?.toLong() ?: fromItemId.tmdb)?.takeIf { it > 0L },
        tvdb = fromItemId.tvdb?.takeIf { it > 0L }
    ).takeUnless { it.isEmpty } ?: return null
    return WeTrakrTarget(
        weTrakrLibraryType(itemType),
        WeTrakrMedia(ids, title = title, year = year, poster = poster, backdrop = background, genres = genres)
    )
}

internal fun weTrakrLibraryEntries(items: List<WeTrakrWatchlistItem>): List<LibraryEntry> =
    items.sortedByDescending { item -> item.addedAt?.let { runCatching { weTrakrTimestamp(it) }.getOrNull() } ?: 0L }
        .mapIndexedNotNull { index, item ->
            val contentId = item.media.ids.contentId ?: return@mapIndexedNotNull null
            val isMovie = item.type == WeTrakrItemType.MOVIE
            LibraryEntry(
                id = contentId,
                type = if (isMovie) "movie" else "series",
                name = item.media.title ?: contentId,
                poster = item.media.poster,
                background = item.media.backdrop,
                logo = null,
                description = null,
                releaseInfo = item.media.year?.toString(),
                imdbRating = null,
                genres = item.media.genres,
                addonBaseUrl = null,
                listKeys = setOf(WETRAKR_WATCHLIST_KEY),
                listRanks = mapOf(WETRAKR_WATCHLIST_KEY to index),
                listedAt = item.addedAt?.let { runCatching { weTrakrTimestamp(it) }.getOrNull() } ?: 0L,
                imdbId = item.media.ids.imdb,
                tmdbId = item.media.ids.tmdb?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                trackingProviderId = WeTrakrProgressProjection.PROVIDER_ID,
                trackingProviderItemId = item.media.id?.toString(),
                rawPosterUrl = item.media.poster
            )
        }.distinctBy { "${it.type}:${it.id}" }

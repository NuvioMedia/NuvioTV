package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class AniListTrackingLibraryProvider @Inject constructor(
    private val sync: AniListSyncRepository,
    private val writes: AniListTrackingWrites,
    auth: AniListAuthStore,
    profiles: ProfileManager
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.ANILIST
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.projectionState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId &&
            state.scope == authorization.scope
        ) state.projection else AniListProjection.Empty
    }.distinctUntilChanged()
    override val isRefreshing = combine(sync.state, auth.state) { state, authorization ->
        state.isLoading && state.scope == authorization.scope
    }.distinctUntilChanged()
    override val items = projection.map { it.libraryItems }.loadOnStart()
    override val tabs = isAuthenticated.map { authenticated ->
        if (!authenticated) return@map emptyList()
        aniListStatusDefinitions.map { definition ->
            LibraryListTab(
                key = definition.key,
                title = definition.title,
                type = if (definition.status == AniListListStatus.PLANNING) {
                    LibraryListTab.Type.WATCHLIST
                } else {
                    LibraryListTab.Type.STATUS
                },
                trackingProviderId = TrackingProviderId.ANILIST.storageId,
                selectionGroup = ANILIST_STATUS_SELECTION_GROUP,
                supportedContentTypes = setOf("movie", "series", "anime"),
                isMembershipDestination = definition.isMembershipDestination
            )
        }
    }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = aniListStatusDefinition(key) != null

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        projection.map { it.membershipKeys(itemId) }.loadOnStart()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership.mapValues { false }.toMutableMap().apply {
            if (currentMembership.values.none { it }) {
                this[aniListStatusDefinitions.single { it.status == AniListListStatus.PLANNING }.key] = true
            }
        }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        sync.ensureLoaded()
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        val keys = sync.currentProjection().membershipKeys(item.itemId)
        return ListMembershipSnapshot(aniListStatusDefinitions.associate { definition -> definition.key to (definition.key in keys) })
    }

    override suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? {
        sync.ensureLoaded()
        val projection = sync.currentProjection()
        val currentKeys = projection.membershipKeys(item.itemId)
        val desiredKeys = desiredKeys(changes.desiredMembership)
        if ((desiredKeys - currentKeys).isNotEmpty()) return null
        val removedKeys = currentKeys - desiredKeys
        if (removedKeys.isEmpty()) return null
        val affected = removedEntries(projection, item.itemId, removedKeys)
        if (affected.none { it.progress > 0 || it.score > 0.0 }) return null
        return TrackingMembershipRemovalConfirmation(
            providerId = TrackingProviderId.ANILIST,
            impacts = setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY, TrackingMembershipRemovalImpact.RATING)
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        sync.ensureLoaded()
        val scope = sync.currentScope()
        val projection = sync.currentProjection()
        val currentKeys = projection.membershipKeys(item.itemId)
        val desiredKeys = desiredKeys(changes.desiredMembership)
        val addedKeys = desiredKeys - currentKeys
        require(addedKeys.size <= 1) { "An AniList entry can have only one list status" }
        val added = addedKeys.singleOrNull()?.let(::aniListStatusDefinition)
        if (added != null) {
            val mediaId = projection.mediaIdFor(item.itemId) ?: writes.resolve(
                buildTrackingMediaReference(
                    contentType = item.itemType,
                    parentMetaId = item.itemId,
                    title = item.title,
                    releaseInfo = item.releaseInfo ?: item.year?.toString()
                ).let { reference ->
                    item.imdbId?.takeIf(String::isNotBlank)?.let { imdb ->
                        reference.copy(ids = reference.ids.copy(imdb = reference.ids.imdb ?: imdb))
                    } ?: reference
                }
            )?.mediaId ?: throw IllegalStateException("This title could not be found on AniList")
            if (sync.currentScope() != scope) throw CancellationException("AniList account changed")
            writes.setStatus(scope, mediaId, added.status)
            return
        }
        val removedKeys = currentKeys - desiredKeys
        if (removedKeys.isEmpty()) return
        val removal = membershipRemovalConfirmation(item, changes)
        require(removal == null || destructiveRemovalConfirmed) {
            "Removing this title from AniList would also clear its progress and score"
        }
        removedEntries(projection, item.itemId, removedKeys).forEach { entry ->
            writes.deleteEntry(scope, entry.mediaId)
        }
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    private fun desiredKeys(desiredMembership: Map<String, Boolean>): Set<String> =
        aniListStatusDefinitions.filter { desiredMembership[it.key] == true }.mapTo(linkedSetOf()) { it.key }

    private fun removedEntries(projection: AniListProjection, contentId: String, removedKeys: Set<String>): List<AniListEntry> =
        projection.franchiseMediaIds(contentId)
            .mapNotNull(projection.entryByMediaId::get)
            .filter { entry -> aniListStatusDefinitionFor(entry.status)?.key in removedKeys }

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}

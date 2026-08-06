package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class SimklLibraryService @Inject constructor(
    private val syncRepository: SimklSyncRepository,
    private val mutationService: SimklMutationService,
    private val authStorage: SimklAuthStorage
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val isRefreshing = syncRepository.state.map { state -> state.isLoading }
        .distinctUntilChanged()
    override val items = syncRepository.projection.map { projection ->
        projection.library.items
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val tabs = isAuthenticated.map { authenticated ->
        if (authenticated) {
            syncRepository.projection.value.library.tabs
        } else {
            emptyList()
        }
    }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = simklLibraryStatusDefinition(key) != null

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        syncRepository.projection.map { projection ->
            projection.membershipKey(itemId, itemType.normalizedContentType())
                ?.let(::setOf)
                .orEmpty()
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun toggledDefaultMembership(
        currentMembership: Map<String, Boolean>
    ): Map<String, Boolean> = currentMembership.mapValues { false }.toMutableMap().apply {
        if (currentMembership.values.none { selected -> selected }) {
            this[simklLibraryStatusDefinitions.single {
                it.status == SimklListStatus.PLAN_TO_WATCH
            }.key] = true
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        syncRepository.ensureLoaded()
        val entry = syncRepository.state.value.snapshot.entries
            .firstOrNull { candidate -> candidate.matchesSimklContentId(item.itemId) }
        val selectedKey = entry?.status?.let { status ->
            simklLibraryStatusDefinitions.firstOrNull { it.status == status }?.key
        }
        return ListMembershipSnapshot(
            simklLibraryStatusDefinitions.associate { definition ->
                definition.key to (definition.key == selectedKey)
            }
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        require(authStorage.state.value.isAuthenticated) { "Simkl authentication is required" }
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val currentEntry = snapshot.entries.firstOrNull { candidate ->
            candidate.matchesSimklContentId(item.itemId)
        }
        val currentDefinition = currentEntry?.status?.let { status ->
            simklLibraryStatusDefinitions.firstOrNull { it.status == status }
        }
        val desiredDefinitions = simklLibraryStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        require(desiredDefinitions.size <= 1) { "A Simkl item can have only one list status" }
        val desired = desiredDefinitions.singleOrNull()
        require(desired == null || desired.supportedContentTypes.any { supported ->
            supported.equals(item.itemType.normalizedContentType(), ignoreCase = true)
        }) { "${desired?.title} does not support ${item.itemType}" }
        val mutation = resolveSimklLibraryMutation(
            currentEntry = currentEntry,
            currentDefinition = currentDefinition,
            desiredDefinition = desired,
            destructiveRemovalConfirmed = destructiveRemovalConfirmed
        ) ?: return

        val reference = snapshot.mediaReference(
            contentId = item.itemId,
            contentType = item.itemType,
            title = item.title,
            releaseInfo = item.releaseInfo ?: item.year?.toString(),
            posterUrl = item.poster
        ).let { reference ->
            reference.copy(
                ids = reference.ids.mergeMissing(
                    TrackingExternalIds(
                        simkl = item.simklId,
                        imdb = item.imdbId,
                        tmdb = item.tmdbId?.toLong(),
                        trakt = item.traktId?.toLong()
                    )
                )
            )
        }
        val result = when (mutation) {
            is SimklLibraryMutation.Move -> mutationService.moveToList(
                listOf(reference),
                mutation.destination
            )
            SimklLibraryMutation.Remove -> mutationService.removeFromList(listOf(reference))
        }
        check(result.isComplete) {
            "Simkl could not match ${result.notFoundCount} of ${result.attemptedCount} library items"
        }
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? {
        syncRepository.ensureLoaded()
        val desired = simklLibraryStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        if (desired.isNotEmpty()) return null
        val currentEntry = syncRepository.state.value.snapshot.entries.firstOrNull { candidate ->
            candidate.matchesSimklContentId(item.itemId)
        } ?: return null
        val impacts = currentEntry.destructiveRemovalImpacts()
        return impacts.takeIf { values -> values.isNotEmpty() }?.let {
            TrackingMembershipRemovalConfirmation(TrackingProviderId.SIMKL, it)
        }
    }

    private fun String.normalizedContentType(): String = when (trim().lowercase()) {
        "tv", "show", "anime" -> "series"
        else -> trim().lowercase()
    }

}

internal fun SimklLibraryEntry.destructiveRemovalImpacts(): Set<TrackingMembershipRemovalImpact> = buildSet {
    if (
        status != SimklListStatus.PLAN_TO_WATCH ||
        lastWatchedAt != null ||
        lastWatched != null ||
        watchedEpisodesCount > 0 ||
        seasons.any { season -> season.episodes.any { episode -> episode.watchedAt != null } }
    ) {
        add(TrackingMembershipRemovalImpact.WATCHED_HISTORY)
    }
    if (userRating != null || userRatedAt != null) add(TrackingMembershipRemovalImpact.RATING)
}

internal sealed interface SimklLibraryMutation {
    data class Move(val destination: TrackingListStatus) : SimklLibraryMutation
    data object Remove : SimklLibraryMutation
}

internal fun resolveSimklLibraryMutation(
    currentEntry: SimklLibraryEntry?,
    currentDefinition: SimklLibraryStatusDefinition?,
    desiredDefinition: SimklLibraryStatusDefinition?,
    destructiveRemovalConfirmed: Boolean
): SimklLibraryMutation? {
    if (desiredDefinition == currentDefinition) return null
    if (desiredDefinition != null) return SimklLibraryMutation.Move(desiredDefinition.trackingStatus)
    if (
        currentEntry?.destructiveRemovalImpacts().orEmpty().isNotEmpty() &&
        !destructiveRemovalConfirmed
    ) {
        throw SimklDestructiveRemovalRequiredException()
    }
    return SimklLibraryMutation.Remove
}

class SimklDestructiveRemovalRequiredException : IllegalStateException(
    "Removing this Simkl status would also clear watched history or a rating"
)

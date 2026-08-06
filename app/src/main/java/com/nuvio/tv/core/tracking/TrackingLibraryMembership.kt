package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

const val LOCAL_LIBRARY_LIST_KEY = "local"

enum class TrackingMembershipRemovalImpact {
    WATCHED_HISTORY,
    RATING
}

data class TrackingMembershipRemovalConfirmation(
    val providerId: TrackingProviderId,
    val impacts: Set<TrackingMembershipRemovalImpact>
)

data class TrackingMembershipApplyResult(
    val requiredRemovalConfirmations: List<TrackingMembershipRemovalConfirmation> = emptyList()
) {
    val requiresRemovalConfirmation: Boolean
        get() = requiredRemovalConfirmations.isNotEmpty()
}

data class TrackingMembershipMutationFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

class TrackingMembershipMutationException(
    val failures: List<TrackingMembershipMutationFailure>
) : IllegalStateException(
    failures.joinToString(", ", prefix = "Tracking membership update failed for ") { failure ->
        failure.providerId.storageId
    },
    failures.firstOrNull()?.cause
)

suspend fun dispatchTrackingMembershipChanges(
    item: LibraryEntryInput,
    providerChanges: Collection<Pair<TrackingLibraryProvider, ListMembershipChanges>>,
    confirmedRemovalProviders: Set<TrackingProviderId>
): List<TrackingMembershipMutationFailure> = supervisorScope {
    providerChanges.map { (provider, changes) ->
        async {
            try {
                provider.applyMembershipChanges(
                    item = item,
                    changes = changes,
                    destructiveRemovalConfirmed = provider.providerId in confirmedRemovalProviders
                )
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TrackingMembershipMutationFailure(provider.providerId, error)
            }
        }
    }.awaitAll().filterNotNull()
}

fun LibraryListTab.supportsMembershipFor(contentType: String): Boolean {
    if (!isMembershipDestination) return false
    return supportedContentTypes?.any { supported ->
        supported.equals(contentType, ignoreCase = true) ||
            supported.equals("series", ignoreCase = true) &&
            contentType.lowercase() in setOf("tv", "show", "anime")
    } != false
}

fun toggleTrackingMembershipSelection(
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    listKey: String,
    contentType: String?
): Map<String, Boolean>? {
    if (listKey == LOCAL_LIBRARY_LIST_KEY) {
        return membership + (listKey to (membership[listKey] != true))
    }
    val tab = tabs.firstOrNull { it.key == listKey } ?: return null
    if (contentType != null && !tab.supportsMembershipFor(contentType)) return null
    val selecting = membership[listKey] != true
    return membership.toMutableMap().apply {
        if (selecting && tab.selectionGroup != null) {
            tabs.filter { candidate ->
                candidate.trackingProviderId == tab.trackingProviderId &&
                    candidate.selectionGroup == tab.selectionGroup
            }.forEach { candidate -> this[candidate.key] = false }
        }
        this[listKey] = selecting
    }
}

fun mergeTrackingMembershipWithTabs(
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>
): Map<String, Boolean> {
    return buildMap {
        put(LOCAL_LIBRARY_LIST_KEY, membership[LOCAL_LIBRARY_LIST_KEY] == true)
        tabs.forEach { tab -> put(tab.key, membership[tab.key] == true) }
    }
}

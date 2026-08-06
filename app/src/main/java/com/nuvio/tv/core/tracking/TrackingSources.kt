package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode

val WatchProgressSource.providerId: TrackingProviderId?
    get() = when (this) {
        WatchProgressSource.TRAKT -> TrackingProviderId.TRAKT
        WatchProgressSource.SIMKL -> TrackingProviderId.SIMKL
        WatchProgressSource.NUVIO_SYNC -> null
    }

val LibrarySourceMode.providerId: TrackingProviderId?
    get() = when (this) {
        LibrarySourceMode.LOCAL -> null
        LibrarySourceMode.TRAKT -> TrackingProviderId.TRAKT
        LibrarySourceMode.SIMKL -> TrackingProviderId.SIMKL
    }

fun effectiveWatchProgressSource(
    requestedSource: WatchProgressSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean
): WatchProgressSource {
    val providerId = requestedSource.providerId ?: return WatchProgressSource.NUVIO_SYNC
    return requestedSource.takeIf { isProviderAuthenticated(providerId) } ?: WatchProgressSource.NUVIO_SYNC
}

fun effectiveLibrarySourceMode(
    requestedSource: LibrarySourceMode,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean
): LibrarySourceMode {
    val providerId = requestedSource.providerId ?: return LibrarySourceMode.LOCAL
    return requestedSource.takeIf { isProviderAuthenticated(providerId) } ?: LibrarySourceMode.LOCAL
}

data class TrackingSourceSelection(
    val watchProgressSource: WatchProgressSource,
    val librarySourceMode: LibrarySourceMode
)

fun effectiveTrackingSourceSelection(
    requested: TrackingSourceSelection,
    connectedProviderIds: Set<TrackingProviderId>
): TrackingSourceSelection {
    return TrackingSourceSelection(
        watchProgressSource = effectiveWatchProgressSource(requested.watchProgressSource) {
            it in connectedProviderIds
        },
        librarySourceMode = effectiveLibrarySourceMode(requested.librarySourceMode) {
            it in connectedProviderIds
        }
    )
}

fun availableWatchProgressSources(
    connectedProviderIds: Set<TrackingProviderId>
): List<WatchProgressSource> = buildList {
    add(WatchProgressSource.NUVIO_SYNC)
    if (TrackingProviderId.TRAKT in connectedProviderIds) add(WatchProgressSource.TRAKT)
    if (TrackingProviderId.SIMKL in connectedProviderIds) add(WatchProgressSource.SIMKL)
}

fun availableLibrarySourceModes(
    connectedProviderIds: Set<TrackingProviderId>
): List<LibrarySourceMode> = buildList {
    add(LibrarySourceMode.LOCAL)
    if (TrackingProviderId.TRAKT in connectedProviderIds) add(LibrarySourceMode.TRAKT)
    if (TrackingProviderId.SIMKL in connectedProviderIds) add(LibrarySourceMode.SIMKL)
}

package com.nuvio.tv.ui.screens.home

internal fun shouldRestoreCachedInProgress(
    hasLiveInProgress: Boolean,
    hasActiveTrackingProvider: Boolean,
    hasCachedInProgress: Boolean,
    hasProviderItems: Boolean,
    hasLoadedRemoteProgress: Boolean
): Boolean =
    !hasLiveInProgress &&
        hasActiveTrackingProvider &&
        hasCachedInProgress &&
        !hasProviderItems &&
        !hasLoadedRemoteProgress

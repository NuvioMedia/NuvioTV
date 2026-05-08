package com.nuvio.tv.core.server

internal fun sanitizePendingAddonChange(
    mode: AddonWebConfigMode,
    proposedChange: PendingAddonChange,
    currentState: PageState
): PendingAddonChange {
    if (
        mode.allowAddonManagement &&
        mode.allowCatalogManagement &&
        mode.allowCollectionManagement
    ) {
        return proposedChange
    }

    return proposedChange.copy(
        proposedUrls = if (mode.allowAddonManagement) {
            proposedChange.proposedUrls
        } else {
            currentState.addons.map { it.url }
        },
        proposedCatalogOrderKeys = if (mode.allowCatalogManagement) {
            proposedChange.proposedCatalogOrderKeys
        } else {
            currentState.catalogs.map { it.key }
        },
        proposedDisabledCatalogKeys = if (mode.allowCatalogManagement) {
            proposedChange.proposedDisabledCatalogKeys
        } else {
            currentState.catalogs
                .filter { it.isDisabled }
                .map { it.disableKey }
        },
        proposedCustomCatalogTitles = if (mode.allowCatalogManagement) {
            proposedChange.proposedCustomCatalogTitles
        } else {
            currentState.catalogs
                .mapNotNull { catalog ->
                    catalog.customTitle?.takeIf { it.isNotBlank() }?.let { catalog.key to it }
                }
                .toMap()
        },
        proposedCollectionsJson = if (mode.allowCollectionManagement) {
            proposedChange.proposedCollectionsJson
        } else {
            null
        },
        proposedDisabledCollectionKeys = if (mode.allowCollectionManagement) {
            proposedChange.proposedDisabledCollectionKeys
        } else {
            currentState.disabledCollectionKeys
        }
    )
}

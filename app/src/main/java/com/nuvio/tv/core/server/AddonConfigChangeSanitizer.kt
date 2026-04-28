package com.nuvio.tv.core.server

internal fun sanitizePendingAddonChange(
    mode: AddonWebConfigMode,
    proposedChange: PendingAddonChange,
    currentState: PageState
): PendingAddonChange {
    if (mode.allowAddonManagement && mode.allowCatalogManagement) {
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
        }
    )
}

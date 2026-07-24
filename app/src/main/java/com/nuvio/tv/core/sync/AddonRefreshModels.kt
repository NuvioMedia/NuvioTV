package com.nuvio.tv.core.sync

data class RemoteAddonEntry(
    val url: String,
    val name: String?,
    val enabled: Boolean
)

data class RemoteAddonSnapshot(
    val profileId: Int,
    val addons: List<RemoteAddonEntry>
)

data class AddonRefreshSummary(
    val profileId: Int,
    val addonCount: Int,
    val refreshedManifestCount: Int,
    val failedManifestCount: Int
) {
    val completedFully: Boolean
        get() = failedManifestCount == 0
}

class AddonRefreshConflictException :
    IllegalStateException("Addons changed while the refresh was running")

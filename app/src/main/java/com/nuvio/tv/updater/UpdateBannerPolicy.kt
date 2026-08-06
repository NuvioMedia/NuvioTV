package com.nuvio.tv.updater

internal object UpdateBannerPolicy {
    fun shouldShow(
        isRemoteNewer: Boolean,
        force: Boolean,
        bannerEnabled: Boolean,
        dismissedTag: String?,
        updateTag: String
    ): Boolean {
        if (!isRemoteNewer) return false
        if (force) return true
        return bannerEnabled && dismissedTag != updateTag
    }
}

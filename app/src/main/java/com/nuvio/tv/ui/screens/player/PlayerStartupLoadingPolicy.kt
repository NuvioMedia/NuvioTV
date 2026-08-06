package com.nuvio.tv.ui.screens.player

/**
 * Loading overlay / playback-issue report decisions during startup buffering.
 */
internal object PlayerStartupLoadingPolicy {

    fun loadingStallReportReason(
        showLoadingOverlay: Boolean,
        hasRenderedFirstFrame: Boolean,
        error: String?,
    ): String {
        return if (isLoadingStallReportable(showLoadingOverlay, hasRenderedFirstFrame, error)) {
            "loading_stall"
        } else {
            "playback_error"
        }
    }

    fun isLoadingStallReportable(
        showLoadingOverlay: Boolean,
        hasRenderedFirstFrame: Boolean,
        error: String?,
    ): Boolean {
        return error == null && showLoadingOverlay && !hasRenderedFirstFrame
    }

    fun shouldReopenLoadingOverlayOnStartupBuffering(
        loadingOverlayEnabled: Boolean,
        showLoadingOverlay: Boolean,
        hasRenderedFirstFrame: Boolean,
        isBuffering: Boolean,
    ): Boolean {
        return isBuffering &&
            !hasRenderedFirstFrame &&
            loadingOverlayEnabled &&
            !showLoadingOverlay
    }

    fun shouldUpdateBufferingMessageBeforeFirstFrame(
        hasRenderedFirstFrame: Boolean,
        isBuffering: Boolean,
    ): Boolean {
        return isBuffering && !hasRenderedFirstFrame
    }
}

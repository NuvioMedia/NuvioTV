package com.nuvio.tv.ui.navigation

internal const val SOURCE_SELECTION_RESTORE_STATE_KEY = "restoreSourceSelection"

internal fun shouldArmSourceSelectionRestore(
    autoPlayNavigation: Boolean,
    previousRoute: String?
): Boolean {
    return !autoPlayNavigation && previousRoute.orEmpty().startsWith("stream/")
}

internal fun sourceSelectionRestoreTarget(
    focusedStreamIndex: Int,
    streamCount: Int
): Int? {
    if (streamCount <= 0) return null
    return focusedStreamIndex.coerceIn(0, streamCount - 1)
}

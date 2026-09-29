package com.nuvio.tv.core.poster

/**
 * Returns the pattern only if [screen] is enabled in [enabledScreens], empty string otherwise.
 * This avoids unnecessary poster URL resolution for disabled screens.
 */
fun patternForScreen(
    pattern: String,
    screen: CustomPosterScreen,
    enabledScreens: Set<CustomPosterScreen>
): String = if (screen in enabledScreens) pattern else ""

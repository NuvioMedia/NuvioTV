package com.nuvio.tv.core.streams

data class StreamBadgeSettings(
    val rules: StreamBadgeRules = StreamBadgeRules(),
    val showFileSizeBadges: Boolean = true,
    val showAddonLogo: Boolean = true,
    val badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    val showInPlayer: Boolean = false,
    val playerBadgeSource: String = ""
)

enum class StreamBadgePlacement {
    TOP,
    BOTTOM
}

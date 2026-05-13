package com.nuvio.tv.domain.model

enum class DiscoverLocation {
    OFF,
    IN_SEARCH,
    IN_SIDEBAR;

    companion object {
        fun fromLegacySearchDiscoverEnabled(enabled: Boolean): DiscoverLocation =
            if (enabled) IN_SEARCH else OFF
    }
}

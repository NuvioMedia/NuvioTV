package com.omnio.tv.domain.model

enum class FolderViewMode {
    TABBED_GRID,
    ROWS,
    FOLLOW_LAYOUT;

    companion object {
        fun fromString(value: String?): FolderViewMode = when (value?.lowercase()) {
            "rows" -> ROWS
            // Accept "follow_home" as an alias: some Collection JSONs in the wild
            // were exported with an older upstream enum value.
            "follow_layout", "follow_home" -> FOLLOW_LAYOUT
            else -> TABBED_GRID
        }
    }
}

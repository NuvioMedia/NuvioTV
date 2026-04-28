package com.nuvio.tv.domain.model

enum class FolderViewMode {
    TABBED_GRID,
    ROWS,
    FOLLOW_LAYOUT;

    companion object {
        fun fromString(value: String?): FolderViewMode = when (value?.lowercase()) {
            "rows" -> ROWS
            "follow_layout" -> FOLLOW_LAYOUT
            else -> TABBED_GRID
        }
    }
}

package com.nuvio.tv.core.poster

enum class CustomPosterScreen(val key: String) {
    /** Home catalog rows */
    HOME("home"),
    /** Continue Watching and Upcoming sections */
    CONTINUE_WATCHING("continue_watching"),
    /** Collection / folder detail screens */
    COLLECTIONS("collections"),
    /** Library screen */
    LIBRARY("library"),
    /** Search results */
    SEARCH("search"),
    /** Detail screen (recommendations, collection items, person/cast credits) */
    DETAILS("details"),
    /** Android TV Watch Next / launcher recommendations */
    TV_RECOMMENDATIONS("tv_recommendations");

    companion object {
        val ALL: Set<CustomPosterScreen> = entries.toSet()

        /** Persisted value meaning explicitly no screens are enabled (distinct from unset → ALL). */
        const val NONE_KEY = "none"

        fun fromKeys(keys: Set<String>?): Set<CustomPosterScreen> {
            if (keys == null) return ALL
            if (keys.isEmpty() || keys == setOf(NONE_KEY)) return emptySet()
            return keys.mapNotNull { key -> entries.find { it.key == key } }.toSet()
        }

        fun toKeys(screens: Set<CustomPosterScreen>): Set<String> =
            screens.map { it.key }.toSet()

        fun toStorageValue(screens: Set<CustomPosterScreen>): String? = when {
            screens == ALL -> null
            screens.isEmpty() -> NONE_KEY
            else -> toKeys(screens).joinToString(",")
        }

        fun fromStorageValue(raw: String?): Set<CustomPosterScreen> = when {
            raw == null -> ALL
            raw.isBlank() || raw == NONE_KEY -> emptySet()
            else -> fromKeys(raw.split(",").toSet())
        }
    }
}

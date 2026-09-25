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
    DETAILS("details");

    companion object {
        val ALL: Set<CustomPosterScreen> = entries.toSet()

        fun fromKeys(keys: Set<String>?): Set<CustomPosterScreen> {
            if (keys.isNullOrEmpty()) return ALL
            return keys.mapNotNull { key -> entries.find { it.key == key } }.toSet()
        }

        fun toKeys(screens: Set<CustomPosterScreen>): Set<String> =
            screens.map { it.key }.toSet()
    }
}

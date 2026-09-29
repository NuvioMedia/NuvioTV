package com.nuvio.tv.domain.model

enum class HomeImdbRatingsVisibility {
    SHOW_ALL,
    HIDE_ALL;

    val showRatings: Boolean
        get() = this == SHOW_ALL

    fun showStandardDetailRatings(isMdbListActive: Boolean): Boolean =
        showRatings && !isMdbListActive
}

enum class DetailImdbRatingsVisibility {
    SHOW_ALL,
    HIDE_UNWATCHED_EPISODES,
    HIDE_EPISODES,
    HIDE_ALL;

    val showEpisodeRatings: Boolean
        get() = this == SHOW_ALL || this == HIDE_UNWATCHED_EPISODES

    fun asEpisodeVisibility(): DetailImdbRatingsVisibility =
        if (this == HIDE_ALL) HIDE_EPISODES else this

    fun showEpisodeRating(isWatched: Boolean): Boolean =
        when (this) {
            SHOW_ALL -> true
            HIDE_UNWATCHED_EPISODES -> isWatched
            HIDE_EPISODES,
            HIDE_ALL -> false
        }
}

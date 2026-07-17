package com.nuvio.tv.domain.model

const val DEFAULT_CARD_DEPTH_EDGE_STRENGTH = 28
const val DEFAULT_CARD_DEPTH_SHEEN_STRENGTH = 10
const val DEFAULT_CARD_DEPTH_EDGE_COVERAGE = 0

enum class CardDepthSurface {
    POSTERS,
    CONTINUE_WATCHING,
    EPISODE_CARDS,
    CAST,
    TRAILERS
}

data class CardDepthStyle(
    val enabled: Boolean = false,
    val edgeStrength: Int = DEFAULT_CARD_DEPTH_EDGE_STRENGTH,
    val sheenStrength: Int = DEFAULT_CARD_DEPTH_SHEEN_STRENGTH,
    val edgeCoverage: Int = DEFAULT_CARD_DEPTH_EDGE_COVERAGE,
    val postersEnabled: Boolean = true,
    val continueWatchingEnabled: Boolean = true,
    val episodeCardsEnabled: Boolean = true,
    val castEnabled: Boolean = true,
    val trailersEnabled: Boolean = true
) {
    fun isEnabledFor(surface: CardDepthSurface): Boolean = enabled && isSurfaceEnabled(surface)

    fun isSurfaceEnabled(surface: CardDepthSurface): Boolean = when (surface) {
        CardDepthSurface.POSTERS -> postersEnabled
        CardDepthSurface.CONTINUE_WATCHING -> continueWatchingEnabled
        CardDepthSurface.EPISODE_CARDS -> episodeCardsEnabled
        CardDepthSurface.CAST -> castEnabled
        CardDepthSurface.TRAILERS -> trailersEnabled
    }
}

package com.nuvio.tv.ui.navigation

internal fun postPlayRecommendationPopUpRoute(previousRoute: String?): String {
    return if (previousRoute.orEmpty().startsWith("stream/")) {
        Screen.Stream.route
    } else {
        Screen.Player.route
    }
}

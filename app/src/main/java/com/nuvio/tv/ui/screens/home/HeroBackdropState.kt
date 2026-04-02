package com.nuvio.tv.ui.screens.home

object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    fun update(url: String?) {
        currentHeroBackdropUrl = url
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        return url
    }
}

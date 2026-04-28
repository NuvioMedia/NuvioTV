package com.nuvio.tv.ui.screens.home

import coil3.transition.CrossfadeDrawable
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget

internal class AlwaysCrossfadeTransitionFactory @JvmOverloads constructor(
    private val durationMillis: Int = CrossfadeDrawable.DEFAULT_DURATION,
    private val preferExactIntrinsicSize: Boolean = false
) : Transition.Factory {

    @Volatile
    private var lastUrl: Any? = null

    init {
        require(durationMillis > 0) { "durationMillis must be > 0." }
    }

    override fun create(target: TransitionTarget, result: ImageResult): Transition {
        if (result !is SuccessResult) {
            return Transition.Factory.NONE.create(target, result)
        }
        val url = result.request.data
        val previousUrl = lastUrl
        lastUrl = url
        if (previousUrl != null && previousUrl == url) {
            return Transition.Factory.NONE.create(target, result)
        }
        return CrossfadeTransition(
            target = target,
            result = result,
            durationMillis = durationMillis,
            preferExactIntrinsicSize = preferExactIntrinsicSize
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is AlwaysCrossfadeTransitionFactory &&
            durationMillis == other.durationMillis &&
            preferExactIntrinsicSize == other.preferExactIntrinsicSize
    }

    override fun hashCode(): Int {
        var result = durationMillis
        result = 31 * result + preferExactIntrinsicSize.hashCode()
        return result
    }
}

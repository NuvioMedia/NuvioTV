package com.nuvio.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

@OptIn(ExperimentalFoundationApi::class)
object NuvioScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = 0.95f,
            stiffness = 180f
        )

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            if (containerSize <= 0f || size <= 0f) return 0f
            val itemCenter = offset + size / 2f
            val viewportTarget = containerSize * 0.42f
            return itemCenter - viewportTarget
        }
    }
}

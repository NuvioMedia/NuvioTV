package com.nuvio.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

@Immutable
data class NuvioMotionDurations(
    val instant: Int,
    val quick: Int,
    val fast: Int,
    val medium: Int,
    val slow: Int,
    val overlay: Int,
    val sidebarLabelIn: Int,
    val sidebarLabelOut: Int,
    val sidebarPanelIn: Int,
    val sidebarPanelOut: Int,
    val sidebarBloomOut: Int,
    val sidebarEnter: Int,
    val sidebarExit: Int,
    val hero: Int,
    val shimmer: Int
)

@Immutable
data class NuvioMotionEasings(
    val standard: Easing,
    val emphasized: Easing,
    val decelerate: Easing,
    val accelerate: Easing
)

@Immutable
data class NuvioMotionTokens(
    val durations: NuvioMotionDurations,
    val easings: NuvioMotionEasings,
    val focusScale: Float,
    val selectedScale: Float,
    val pressedScale: Float,
    val reducedMotionScale: Float
)

@Immutable
data class NuvioFocusTokens(
    val scale: Float,
    val subtleScale: Float,
    val pressedScale: Float,
    val disabledScale: Float,
    val scrollViewportTarget: Float,
    val longPressInitialDelayMillis: Long,
    val longPressRepeatMillis: Long
)

object NuvioMotion {
    val tokens = NuvioMotionTokens(
        durations = NuvioMotionDurations(
            instant = 0,
            quick = 125,
            fast = 180,
            medium = 350,
            slow = 450,
            overlay = 400,
            sidebarLabelIn = 125,
            sidebarLabelOut = 145,
            sidebarPanelIn = 345,
            sidebarPanelOut = 385,
            sidebarBloomOut = 395,
            sidebarEnter = 385,
            sidebarExit = 145,
            hero = 450,
            shimmer = 1200
        ),
        easings = NuvioMotionEasings(
            standard = FastOutSlowInEasing,
            emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f),
            decelerate = LinearOutSlowInEasing,
            accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)
        ),
        focusScale = 1.02f,
        selectedScale = 1.01f,
        pressedScale = 0.98f,
        reducedMotionScale = 1f
    )

    fun <T> quickTween(): TweenSpec<T> = tween(tokens.durations.quick, easing = tokens.easings.standard)

    fun <T> focusTween(): TweenSpec<T> = tween(tokens.durations.fast, easing = tokens.easings.standard)

    fun <T> mediumTween(): TweenSpec<T> = tween(tokens.durations.medium, easing = tokens.easings.standard)

    fun <T> slowTween(): TweenSpec<T> = tween(tokens.durations.slow, easing = tokens.easings.decelerate)

    fun <T> focusSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

object NuvioFocus {
    val tokens = NuvioFocusTokens(
        scale = 1.02f,
        subtleScale = 1.01f,
        pressedScale = 0.98f,
        disabledScale = 1f,
        scrollViewportTarget = 0.42f,
        longPressInitialDelayMillis = 360L,
        longPressRepeatMillis = 72L
    )
}

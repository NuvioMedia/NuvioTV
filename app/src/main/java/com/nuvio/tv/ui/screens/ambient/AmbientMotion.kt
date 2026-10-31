package com.nuvio.tv.ui.screens.ambient

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.util.lerp

internal data class AmbientMotion(
    val startScale: Float,
    val endScale: Float,
    val startX: Float = 0f,
    val endX: Float = 0f,
    val startY: Float = 0f,
    val endY: Float = 0f
)

internal val ambientMotions = listOf(
    AmbientMotion(startScale = 1.0f, endScale = 1.06f),
    AmbientMotion(startScale = 1.07f, endScale = 1.01f),
    AmbientMotion(startScale = 1.06f, endScale = 1.06f, startX = -0.025f, endX = 0.025f),
    AmbientMotion(startScale = 1.06f, endScale = 1.06f, startX = 0.025f, endX = -0.025f),
    AmbientMotion(startScale = 1.06f, endScale = 1.06f, startY = 0.02f, endY = -0.02f),
    AmbientMotion(startScale = 1.02f, endScale = 1.08f, endX = -0.025f, endY = -0.02f),
    AmbientMotion(startScale = 1.02f, endScale = 1.08f, endX = 0.025f, endY = 0.02f)
)

internal fun nextAmbientMotion(previous: AmbientMotion?): AmbientMotion =
    ambientMotions.filter { it != previous }.random()

internal fun GraphicsLayerScope.applyAmbientMotion(motion: AmbientMotion, progress: Float) {
    val scale = lerp(motion.startScale, motion.endScale, progress)
    scaleX = scale
    scaleY = scale
    translationX = lerp(motion.startX, motion.endX, progress) * size.width
    translationY = lerp(motion.startY, motion.endY, progress) * size.height
}

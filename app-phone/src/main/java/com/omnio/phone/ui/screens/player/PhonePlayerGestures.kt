package com.omnio.phone.ui.screens.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Phone Player gesture handler:
 *  - single tap → onSingleTap (toggles overlay)
 *  - horizontal drag → onHorizontalSeek(deltaPx, totalWidthPx)
 *  - vertical drag on left half → brightness adjust
 *  - vertical drag on right half → volume adjust
 *  - pinch (two-finger zoom gesture) → onPinch (used to cycle aspect mode)
 */
internal fun Modifier.phonePlayerGestures(
    enabled: Boolean,
    onSingleTap: () -> Unit,
    onSeekDelta: (deltaPx: Float, totalWidthPx: Float) -> Unit,
    onSeekCommit: () -> Unit,
    onShowBrightness: (level01: Float) -> Unit,
    onShowVolume: (level01: Float) -> Unit,
    onPinch: () -> Unit
): Modifier = composed {
    val context = LocalContext.current
    if (!enabled) return@composed this
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val startPos = down.position
            val totalSize: IntSize = size
            val widthPx = totalSize.width.toFloat().coerceAtLeast(1f)
            val heightPx = totalSize.height.toFloat().coerceAtLeast(1f)

            var isDragging = false
            var draggingHorizontal = false
            var draggingVertical = false
            var didPinch = false
            var seekDeltaX = 0f
            val brightnessAccum = floatArrayOf(0f)
            val volumeAccum = floatArrayOf(0f)
            val window = (context as? android.app.Activity)?.window
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            val initialBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                ?: runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                }.getOrDefault(0.5f)
            var brightnessLevel = initialBrightness.coerceIn(0f, 1f)

            val maxVol = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            var volumeLevel = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC)
                ?: 0).toFloat() / maxVol.toFloat()

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.size >= 2) {
                    val zoom = event.calculateZoom()
                    if (abs(zoom - 1f) > 0.01f) {
                        if (!didPinch) {
                            didPinch = true
                            onPinch()
                        }
                    }
                    event.changes.forEach { it.consume() }
                    if (event.changes.all { !it.pressed }) break
                    continue
                }
                val change = event.changes.firstOrNull() ?: break
                val pos = change.position
                val dPos = change.positionChange()
                val cumulativeDx = pos.x - startPos.x
                val cumulativeDy = pos.y - startPos.y

                if (!isDragging) {
                    if (abs(cumulativeDx) > 24f || abs(cumulativeDy) > 24f) {
                        isDragging = true
                        draggingHorizontal = abs(cumulativeDx) > abs(cumulativeDy)
                        draggingVertical = !draggingHorizontal
                    }
                }

                if (draggingHorizontal) {
                    seekDeltaX += dPos.x
                    onSeekDelta(seekDeltaX, widthPx)
                    change.consume()
                } else if (draggingVertical) {
                    val isLeftHalf = startPos.x < widthPx / 2f
                    val deltaFraction = -dPos.y / heightPx
                    if (isLeftHalf) {
                        brightnessLevel = (brightnessLevel + deltaFraction).coerceIn(0f, 1f)
                        window?.let {
                            val attrs = it.attributes
                            attrs.screenBrightness = brightnessLevel
                            it.attributes = attrs
                        }
                        brightnessAccum[0] = brightnessLevel
                        onShowBrightness(brightnessLevel)
                    } else {
                        volumeLevel = (volumeLevel + deltaFraction).coerceIn(0f, 1f)
                        val target = (volumeLevel * maxVol).toInt().coerceIn(0, maxVol)
                        audio?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        volumeAccum[0] = volumeLevel
                        onShowVolume(volumeLevel)
                    }
                    change.consume()
                }

                if (event.changes.all { !it.pressed }) break
            }
            if (draggingHorizontal && abs(seekDeltaX) > 0f) {
                onSeekCommit()
            } else if (!isDragging && !didPinch) {
                onSingleTap()
            }
            // Else: vertical drag (brightness/volume) — no commit action needed.
            @Suppress("UNUSED_VARIABLE")
            val unusedStart: Offset = startPos
        }
    }
}

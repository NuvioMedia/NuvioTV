package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class HoldForSpeedHandler(
    private val scope: CoroutineScope,
    private val holdThresholdMs: Long = 350L,
    private val heldSpeed: Float = 2f,
) {
    private var holdJob: Job? = null
    private var keyIsDown = false
    private var holdActivated = false
    private var speedBeforeHold = 1f
    var isHoldingSpeed by mutableStateOf(false)
    private set

    val speedLabel: String
        get() = if (heldSpeed % 1f == 0f) {
            "${heldSpeed.toInt()}×"
        } else {
            "${heldSpeed}×"
        }

    fun handle(
        event: KeyEvent,
        currentSpeed: () -> Float,
        onTap: () -> Unit,
        onSpeedChange: (Float) -> Unit,
    ): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP) {
            return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                handleKeyDown(
                    event = event,
                    currentSpeed = currentSpeed,
                    onSpeedChange = onSpeedChange,
                )
                true
            }

            KeyEvent.ACTION_UP -> {
                handleKeyUp(
                    onTap = onTap,
                    onSpeedChange = onSpeedChange,
                )
                true
            }

            else -> false
        }
    }

    private fun handleKeyDown(
        event: KeyEvent,
        currentSpeed: () -> Float,
        onSpeedChange: (Float) -> Unit,
    ) {
        if (keyIsDown || event.repeatCount > 0) {
            return
        }

        keyIsDown = true
        holdActivated = false

        holdJob?.cancel()
        holdJob = scope.launch {
            delay(holdThresholdMs)

            if (!keyIsDown) {
                return@launch
            }

            speedBeforeHold = currentSpeed()
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f

            holdActivated = true
            isHoldingSpeed = true
            onSpeedChange(heldSpeed)
        }
    }

    private fun handleKeyUp(
        onTap: () -> Unit,
        onSpeedChange: (Float) -> Unit,
    ) {
        if (!keyIsDown) {
            return
        }

        keyIsDown = false
        holdJob?.cancel()
        holdJob = null

        if (holdActivated) {
            holdActivated = false
            isHoldingSpeed = false
            onSpeedChange(speedBeforeHold)
        } else {
            onTap()
        }
    }

    fun cancel(onSpeedChange: (Float) -> Unit) {
        holdJob?.cancel()
        holdJob = null
        keyIsDown = false

        if (holdActivated) {
            holdActivated = false
            isHoldingSpeed = false
            onSpeedChange(speedBeforeHold)
        }
    }
}
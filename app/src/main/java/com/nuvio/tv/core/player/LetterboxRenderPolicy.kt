package com.nuvio.tv.core.player

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object LetterboxRenderPolicy {

    fun defaultTransparentLetterbox(
        manufacturer: String? = Build.MANUFACTURER,
        model: String? = Build.MODEL,
        hardware: String? = Build.HARDWARE,
        device: String? = Build.DEVICE,
        socModel: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
    ): Boolean {
        // MediaTek MT8696 SoC devices (Amazon Fire TV Stick 4K Max, Fire TV Cube 3rd gen,
        // and Google TV Streamer 4K):
        // Kodi PR 22561 and Nuvio PR 3598 document that a transparent backdrop in Dolby Vision
        // on MT8696 renders with an elevated black floor (~0.1 nits glowing grey letterbox).
        // Using an opaque black backdrop renders true reference black (0 nits / OLED pixels off).
        if (manufacturer.orEmpty().trim().equals("Amazon", ignoreCase = true)) {
            return false
        }
        val isMtk8696 = socModel.orEmpty().contains("8696", ignoreCase = true) ||
            hardware.orEmpty().contains("mt8696", ignoreCase = true) ||
            device.orEmpty().contains("kirkwood", ignoreCase = true) ||
            model.orEmpty().contains("Streamer", ignoreCase = true)
        if (isMtk8696) {
            return false
        }
        return true
    }
}

object PlayerWindowBackdrop {

    private var transparentRequests by mutableIntStateOf(0)

    val isTransparentRequested: Boolean
        get() = transparentRequests > 0

    fun acquireTransparent() {
        transparentRequests++
    }

    fun releaseTransparent() {
        transparentRequests = (transparentRequests - 1).coerceAtLeast(0)
    }
}

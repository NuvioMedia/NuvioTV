package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun PlayerRuntimeController.applyStoredAudioDelayForCurrentRouteIfEnabled() {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: return
    currentAudioOutputRoute = route

    val storedDelayMs = audioDelayRouteDataStore.loadDelayMs(route.key) ?: 0
    applyAudioDelay(storedDelayMs, persistForCurrentRoute = false)
    Log.d(
        PlayerRuntimeController.TAG,
        "Applied audio delay ${storedDelayMs}ms for route=${route.key}"
    )
}

internal fun PlayerRuntimeController.persistAudioDelayForCurrentRoute(delayMs: Int) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: currentAudioOutputRoute ?: return
    currentAudioOutputRoute = route
    scope.launch {
        audioDelayRouteDataStore.saveDelayMs(route.key, delayMs)
    }
}

internal fun PlayerRuntimeController.registerAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioOutputRouteCallback != null) return

    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged()
        }
    }

    runCatching {
        audioManager.registerAudioDeviceCallback(callback, null)
        audioOutputRouteCallback = callback
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to register audio route callback", it)
    }
}

internal fun PlayerRuntimeController.unregisterAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val callback = audioOutputRouteCallback ?: return
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        audioManager.unregisterAudioDeviceCallback(callback)
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to unregister audio route callback", it)
    }
    audioOutputRouteCallback = null
}

private fun PlayerRuntimeController.onAudioOutputRouteMaybeChanged() {
    if (!rememberAudioDelayPerDeviceEnabled) return
    scope.launch {
        delay(250)
        applyStoredAudioDelayForCurrentRouteIfEnabled()
    }
}

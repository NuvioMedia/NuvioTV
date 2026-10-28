package com.nuvio.tv.ui.screens.player.mpvass

import android.util.Log

internal object MpvAss {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("nuvio_mpv_ass")
            nativeAvailable()
        }.onFailure { Log.w("ass", "libass from libmpv can't be loaded", it) }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeAvailable(): Boolean
}

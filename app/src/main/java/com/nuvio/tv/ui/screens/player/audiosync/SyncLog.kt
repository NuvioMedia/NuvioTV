package com.nuvio.tv.ui.screens.player.audiosync

import android.util.Log
import com.nuvio.tv.BuildConfig

/** Debug-only logcat, with stream/subtitle URLs redacted before logging. */
internal object SyncLog {
    private const val TAG = "NuvioAudioSync"
    private val url = Regex("""https?://[^\s]+""")
    private fun write(level: Int, message: String) {
        if (BuildConfig.DEBUG) Log.println(level, TAG, url.replace(message, "<url>"))
    }
    fun i(message: String) = write(Log.INFO, message)
    fun w(message: String) = write(Log.WARN, message)
    fun d(message: String) = write(Log.DEBUG, message)
}

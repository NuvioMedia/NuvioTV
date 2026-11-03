package com.nuvio.tv.reshaped.livetv

import android.app.ActivityManager
import android.content.Context

/** What Live TV scales down on TVs with little memory (the channel preview, how much guide is kept). */
internal object LiveTvDevice {
    /** Boxes that report under this much memory (2 GB models report less than 2 GB) count as low memory. */
    private const val LOW_MEMORY_BYTES = 2_560L * 1024 * 1024

    @Volatile private var lowMemory: Boolean? = null

    fun isLowMemory(context: Context): Boolean = lowMemory ?: run {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val low = when {
            manager == null -> true
            manager.isLowRamDevice -> true
            else -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem in 1 until LOW_MEMORY_BYTES
        }
        low.also { lowMemory = it }
    }
}

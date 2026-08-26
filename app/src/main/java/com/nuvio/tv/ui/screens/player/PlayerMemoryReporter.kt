package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The native memory tiers are derived from total device RAM and have never been checked against
// what the process actually resides at, which is what the kernel kills on.
@UnstableApi
object PlayerMemoryReporter {

    private const val TAG = "PlayerMemory"
    private const val SAMPLE_INTERVAL_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var samplerJob: Job? = null

    @Volatile
    private var peakRssMb: Int = 0

    // Read on the main thread by the sampler, since ExoPlayer state is not thread safe.
    @Volatile
    var bufferedAheadProvider: (() -> Long)? = null

    fun snapshot(context: Context): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val rssMb = info.totalPss / 1024
        if (rssMb > peakRssMb) peakRssMb = rssMb

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val system = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(system)

        return "rssMb=$rssMb peakMb=$peakRssMb " +
            "javaMb=${info.dalvikPrivateDirty / 1024} nativeMb=${info.nativePrivateDirty / 1024} " +
            "deviceAvailMb=${system.availMem / (1024L * 1024L)} deviceLow=${system.lowMemory}"
    }

    // Peak is what matters for calibration, and a single reading at playback start misses it.
    fun startSampling(context: Context) {
        if (samplerJob?.isActive == true) return
        peakRssMb = 0
        val appContext = context.applicationContext
        samplerJob = scope.launch {
            while (isActive) {
                val aheadMs = withContext(Dispatchers.Main) {
                    runCatching { bufferedAheadProvider?.invoke() }.getOrNull() ?: -1L
                }
                Log.i(TAG, "MEM_SAMPLE: aheadMs=$aheadMs ${snapshot(appContext)}")
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stopSampling(context: Context) {
        samplerJob?.cancel()
        samplerJob = null
        Log.i(TAG, "MEM_FINAL: ${snapshot(context.applicationContext)}")
    }
}

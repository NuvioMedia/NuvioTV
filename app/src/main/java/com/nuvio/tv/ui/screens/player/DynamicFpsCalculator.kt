package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.nuvio.tv.core.player.FrameRateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class DynamicFpsCalculator(
    private val exoPlayer: ExoPlayer,
    private val scope: CoroutineScope,
    private val onFpsCalculated: (Float) -> Unit,
    private val onTimeout: () -> Unit = {}
) : VideoFrameMetadataListener {
    private val timestamps = ConcurrentLinkedQueue<Long>()
    private val framesToIgnore = 5
    private val framesToCollect = 30
    private val targetSize = framesToIgnore + framesToCollect
    private val isCompleted = AtomicBoolean(false)
    private val timeoutJob: Job?
    private val timeoutMs = 5000L

    companion object {
        private const val TAG = "DynamicFpsCalculator"
    }

    init {
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (!isCompleted.get()) {
                Log.w(TAG, "[DYNAMIC] Timeout after ${timeoutMs}ms, cancelling")
                cancel()
                onTimeout()
            }
        }
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: androidx.media3.common.Format,
        mediaFormat: android.media.MediaFormat?
    ) {
        if (isCompleted.get()) return

        timestamps.add(presentationTimeUs)

        // Loggare ogni frame intasa il logcat, lo facciamo solo se necessario al debug
        // Log.d(TAG, "[DYNAMIC] Collected frame ${timestamps.size}/$targetSize, time=$presentationTimeUs")

        if (timestamps.size >= targetSize) {
            // Launch calculation on background thread to avoid blocking rendering thread
            scope.launch(Dispatchers.Default) {
                calculateAndTrigger()
            }
        }
    }

    private fun calculateAndTrigger() {
        if (isCompleted.compareAndSet(false, true)) {
            timeoutJob?.cancel()
            Log.d(TAG, "[DYNAMIC] Collected enough frames, calculating FPS")

            // CRITICO: Sganciamo il listener dal player per non consumare CPU per il resto del film.
            // Le API di ExoPlayer richiedono che queste operazioni avvengano sul Main Thread.
            scope.launch(Dispatchers.Main) {
                exoPlayer.clearVideoFrameMetadataListener(this@DynamicFpsCalculator)
            }

            scope.launch(Dispatchers.Default) {
                // Ordiniamo i timestamp per evitare sbalzi negativi causati dai B-Frames
                val validTimestamps = timestamps.toList().takeLast(framesToCollect).sorted()
                val deltas = mutableListOf<Long>()

                for (i in 1 until validTimestamps.size) {
                    val delta = validTimestamps[i] - validTimestamps[i - 1]
                    if (delta > 0) {
                        deltas.add(delta)
                    }
                }

                if (deltas.isNotEmpty()) {
                    val averageDeltaUs = deltas.average().toLong()
                    val measuredFps = 1_000_000f / averageDeltaUs
                    Log.d(TAG, "[DYNAMIC] Average delta: ${averageDeltaUs}us, Measured FPS: $measuredFps")

                    val snappedFps = FrameRateUtils.snapToStandardRate(measuredFps)
                    Log.d(TAG, "[DYNAMIC] Snapped FPS: $snappedFps")

                    scope.launch(Dispatchers.Main) {
                        onFpsCalculated(snappedFps)
                    }
                } else {
                    Log.w(TAG, "[DYNAMIC] No valid deltas calculated")
                }
            }
        }
    }

    fun cancel() {
        if (isCompleted.compareAndSet(false, true)) {
            Log.d(TAG, "[DYNAMIC] Cancelled")
            timeoutJob?.cancel()
            scope.launch(Dispatchers.Main) {
                exoPlayer.clearVideoFrameMetadataListener(this@DynamicFpsCalculator)
            }
        }
    }
}

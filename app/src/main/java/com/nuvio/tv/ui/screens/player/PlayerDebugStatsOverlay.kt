package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.nuvio.tv.core.network.StreamSpeedTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal data class DebugStat(val label: String, val value: String, val warn: Boolean = false)

internal data class PlayerSnapshot(
    val aheadMs: Long,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val durationMs: Long,
    val droppedFrames: Int,
    val fileSizeBytes: Long?
)

@OptIn(UnstableApi::class)
@Composable
internal fun PlayerDebugStatsOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sampler = remember { DebugStatsSampler(context) }
    var stats by remember { mutableStateOf(emptyList<DebugStat>()) }
    var probedFileSize by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        // Hold off until playback is running so the first samples are not startup noise, but give up
        // waiting on the mpv engine, which never exposes an ExoPlayer.
        var waited = 0L
        while (viewModel.exoPlayer?.playbackState != Player.STATE_READY && waited < 30_000L) {
            delay(250L)
            waited += 250L
        }
        delay(2000L)

        // Only some addons send videoSize, so ask the server once when nothing upstream knew it.
        if (viewModel.getCurrentFileSizeBytes() == null) {
            val url = viewModel.getCurrentStreamUrl()
            if (url.isNotBlank()) {
                probedFileSize = StreamSpeedTester
                    .getStreamContentLength(url, viewModel.getCurrentHeaders())
                    .takeIf { it > 0L }
            }
        }

        while (true) {
            // Player fields must be read on this thread; everything else blocks, so it goes to IO.
            val player = viewModel.exoPlayer
            val snapshot = player?.let {
                PlayerSnapshot(
                    aheadMs = (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L),
                    videoBitrate = runCatching { it.videoFormat?.bitrate }.getOrNull() ?: -1,
                    audioBitrate = runCatching { it.audioFormat?.bitrate }.getOrNull() ?: -1,
                    durationMs = runCatching { it.duration }.getOrNull() ?: -1L,
                    droppedFrames = runCatching { it.videoDecoderCounters?.droppedBufferCount }.getOrNull() ?: 0,
                    fileSizeBytes = viewModel.getCurrentFileSizeBytes() ?: probedFileSize
                )
            }
            stats = withContext(Dispatchers.IO) { sampler.sample(snapshot) }
            delay(1000L)
        }
    }

    if (stats.isEmpty()) return

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        stats.forEach { stat ->
            Row {
                Text(
                    text = stat.label,
                    modifier = Modifier.width(64.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8A8A8A)
                )
                Text(
                    text = stat.value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = if (stat.warn) FontWeight.Bold else FontWeight.Normal,
                    color = if (stat.warn) Color(0xFFFFB300) else Color(0xFFF0F0F0)
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
private class DebugStatsSampler(context: Context) {

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val clockTicks = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L).coerceAtLeast(1L)

    private var lastCpuTicks = -1L
    private var lastMajorFaults = -1L
    private var lastProcAtMs = 0L
    private var lastRxBytes = -1L
    private var lastRxAtMs = 0L
    private var bufferTotal = 0.0
    private var bufferSamples = 0
    private var networkTotal = 0.0
    private var networkSamples = 0
    private var cpuTotal = 0.0
    private var cpuSamples = 0
    private var faultTotal = 0.0
    private var faultSamples = 0

    fun sample(snapshot: PlayerSnapshot?): List<DebugStat> = buildList {
        val proc = readProcTimes()
        val now = System.currentTimeMillis()
        val elapsedSeconds = if (lastProcAtMs > 0L) (now - lastProcAtMs) / 1000.0 else 0.0

        add(cpuStat(proc, elapsedSeconds))
        add(majorFaultStat(proc, elapsedSeconds))
        if (proc != null) {
            lastCpuTicks = proc.cpuTicks
            lastMajorFaults = proc.majorFaults
            lastProcAtMs = now
        }

        add(memoryStat())
        add(bufferStat(snapshot))
        add(bitrateStat(snapshot))
        add(networkStat())
        add(droppedStat(snapshot))
        addAll(thermalStats())
    }.filterNot { it.value == UNAVAILABLE }

    private fun cpuStat(proc: ProcTimes?, elapsedSeconds: Double): DebugStat {
        if (proc == null) return DebugStat("cpu", UNAVAILABLE)
        if (lastCpuTicks < 0L || elapsedSeconds <= 0.0) return DebugStat("cpu", "...")
        val cpuSeconds = (proc.cpuTicks - lastCpuTicks).toDouble() / clockTicks
        val percent = cpuSeconds / elapsedSeconds / cores * 100.0
        cpuTotal += percent
        cpuSamples++
        return DebugStat(
            label = "cpu",
            value = String.format(Locale.US, "%.0f%%   avg %.0f%%", percent, cpuTotal / cpuSamples),
            warn = percent >= 60.0
        )
    }

    // Faults that had to hit storage, so a high rate means the device is paging rather than playing.
    private fun majorFaultStat(proc: ProcTimes?, elapsedSeconds: Double): DebugStat {
        if (proc == null) return DebugStat("paging", UNAVAILABLE)
        if (lastMajorFaults < 0L || elapsedSeconds <= 0.0) return DebugStat("paging", "...")
        val perSecond = (proc.majorFaults - lastMajorFaults).toDouble() / elapsedSeconds
        faultTotal += perSecond
        faultSamples++
        return DebugStat(
            label = "paging",
            value = String.format(Locale.US, "%.0f/s   avg %.0f/s", perSecond, faultTotal / faultSamples),
            warn = perSecond >= 100.0
        )
    }

    // majflt, utime and stime are fields 12, 14 and 15, counted after a comm field that can itself
    // contain spaces.
    private fun readProcTimes(): ProcTimes? = runCatching {
        val stat = File("/proc/self/stat").readText()
        val f = stat.substring(stat.lastIndexOf(')') + 1).trim().split(" ")
        ProcTimes(
            cpuTicks = f[11].toLong() + f[12].toLong(),
            majorFaults = f[9].toLong()
        )
    }.getOrNull()

    // Performance mode puts the player buffers in native memory, so the java heap alone hides them.
    private fun memoryStat(): DebugStat {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val maxMb = runtime.maxMemory() / MB
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / MB }.getOrDefault(-1L)
        val nativeText = if (nativeMb < 0L) "" else "   native $nativeMb MB"
        return DebugStat(
            label = "memory",
            value = "$usedMb / $maxMb MB$nativeText",
            warn = maxMb > 0L && usedMb.toDouble() / maxMb >= 0.85
        )
    }

    private fun bufferStat(snapshot: PlayerSnapshot?): DebugStat {
        if (snapshot == null) return DebugStat("buffer", UNAVAILABLE)
        val ahead = snapshot.aheadMs / 1000.0
        bufferTotal += ahead
        bufferSamples++
        return DebugStat(
            label = "buffer",
            value = String.format(Locale.US, "%.1fs   avg %.1fs", ahead, bufferTotal / bufferSamples),
            warn = ahead < 5.0
        )
    }

    // The file rate covers every track plus container overhead, so it reads higher than the video
    // track alone and the two are labelled apart rather than mixed.
    private fun bitrateStat(snapshot: PlayerSnapshot?): DebugStat {
        val fileBps = PlayerBitrateEstimator.fileBitrateBps(
            snapshot?.fileSizeBytes,
            snapshot?.durationMs ?: -1L
        )
        if (fileBps != null) {
            val sizeGb = (snapshot?.fileSizeBytes ?: 0L) / (1024.0 * 1024.0 * 1024.0)
            return DebugStat(
                "bitrate",
                String.format(Locale.US, "%.1f Mbps file   %.1f GB", fileBps / 1_000_000.0, sizeGb)
            )
        }
        val video = snapshot?.videoBitrate ?: -1
        val audio = snapshot?.audioBitrate ?: -1
        if (video <= 0 && audio <= 0) return DebugStat("bitrate", UNAVAILABLE)
        val totalMbps = (video.coerceAtLeast(0) + audio.coerceAtLeast(0)) / 1_000_000.0
        return DebugStat("bitrate", String.format(Locale.US, "%.1f Mbps tracks", totalMbps))
    }

    private fun networkStat(): DebugStat {
        val rx = runCatching { TrafficStats.getUidRxBytes(Process.myUid()) }.getOrDefault(-1L)
        if (rx < 0L) return DebugStat("network", UNAVAILABLE)
        val now = System.currentTimeMillis()
        val previousRx = lastRxBytes
        val previousAt = lastRxAtMs
        lastRxBytes = rx
        lastRxAtMs = now
        if (previousRx < 0L || now <= previousAt) return DebugStat("network", "...")
        val mbps = (rx - previousRx).toDouble() / ((now - previousAt) / 1000.0) * 8.0 / 1_000_000.0
        networkTotal += mbps
        networkSamples++
        return DebugStat(
            label = "network",
            value = String.format(
                Locale.US,
                "%.1f Mbps   avg %.1f",
                mbps,
                networkTotal / networkSamples
            )
        )
    }

    private fun droppedStat(snapshot: PlayerSnapshot?): DebugStat {
        val dropped = snapshot?.droppedFrames ?: 0
        return DebugStat("dropped", "$dropped frames", warn = dropped > 0)
    }

    // Headroom is normalised so 1.00 is the throttling point; the status line only matters once it trips.
    private fun thermalStats(): List<DebugStat> {
        val pm = powerManager ?: return listOf(DebugStat("thermal", UNAVAILABLE))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOf(DebugStat("thermal", UNAVAILABLE))
        }
        val headroom = runCatching { pm.getThermalHeadroom(0) }.getOrDefault(Float.NaN)
        val status = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val headroomStat = DebugStat(
            label = "thermal",
            value = if (headroom.isNaN()) UNAVAILABLE else String.format(Locale.US, "%.2f / 1.00", headroom),
            warn = !headroom.isNaN() && headroom >= 0.9f
        )
        val throttleName = throttleName(status) ?: return listOf(headroomStat)
        return listOf(headroomStat, DebugStat("throttle", throttleName, warn = true))
    }

    private fun throttleName(status: Int): String? = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> null
    }

    private data class ProcTimes(val cpuTicks: Long, val majorFaults: Long)

    private companion object {
        const val MB = 1024L * 1024L

        // A row carrying this is one the device will never fill in, so it is dropped rather than
        // taking a line; the warmup placeholder is left alone so no row appears a second later.
        const val UNAVAILABLE = "n/a"
    }
}

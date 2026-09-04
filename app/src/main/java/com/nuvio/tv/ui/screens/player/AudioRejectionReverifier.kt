package com.nuvio.tv.ui.screens.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nuvio.tv.core.player.AudioPassthroughPolicy

// Held while any direct (compressed) AudioTrack is opened for probing. On the HALs measured
// so far a single open direct stream makes every other direct open fail, so the IEC 61937
// probe and the re-verification probe must never overlap.
internal object DirectOpenProbeLock

// Process-scoped view of learned passthrough denials. The persisted set means "denied until
// re-verified": entries are added only after a real open failed and the same title's
// fallback then opened audio, and they are removed as soon as a background open succeeds.
// Pure Kotlin so the bookkeeping is unit-testable; the probe itself is in the object below.
internal class AudioRejectionLedger {

    private val verified = HashSet<String>()
    private val probedRoutes = HashSet<String>()
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun learnedFor(routeKey: String?, persisted: Set<String>): Set<AudioPassthroughPolicy.Group> {
        if (routeKey == null) return emptySet()
        return persisted.mapNotNull { entry ->
            if (entry in verified) return@mapNotNull null
            val separator = entry.lastIndexOf("::")
            if (separator <= 0) return@mapNotNull null
            if (entry.substring(0, separator) != routeKey) return@mapNotNull null
            AudioPassthroughPolicy.Group.entries.firstOrNull { it.name == entry.substring(separator + 2) }
        }.toSet()
    }

    @Synchronized
    fun entriesToProbe(routeKey: String, persisted: Set<String>): List<String> {
        if (!probedRoutes.add(routeKey)) return emptyList()
        return persisted.filter { it.startsWith("$routeKey::") && it !in verified }
    }

    @Synchronized
    fun markVerified(entry: String) {
        verified.add(entry)
    }

    @Synchronized
    fun invalidate() {
        verified.clear()
        probedRoutes.clear()
    }

    @Synchronized
    fun stashPending(streamUrl: String, entry: String) {
        pending = streamUrl to entry
    }

    // Returns the pending entry if it belongs to this stream, and clears it either way.
    @Synchronized
    fun takePendingFor(streamUrl: String): String? {
        val current = pending ?: return null
        pending = null
        return if (current.first == streamUrl) current.second else null
    }

    @Synchronized
    fun dropPending() {
        pending = null
    }

    companion object {
        fun entry(routeKey: String, group: AudioPassthroughPolicy.Group): String = "$routeKey::${group.name}"

        fun groupOf(entry: String): AudioPassthroughPolicy.Group? {
            val separator = entry.lastIndexOf("::")
            if (separator <= 0) return null
            return AudioPassthroughPolicy.Group.entries.firstOrNull { it.name == entry.substring(separator + 2) }
        }

        fun routeOf(entry: String): String? {
            val separator = entry.lastIndexOf("::")
            return if (separator <= 0) null else entry.substring(0, separator)
        }
    }
}

internal object AudioRejectionReverifier {

    private const val TAG = "AudioRejection"

    val ledger = AudioRejectionLedger()

    // Opens and releases a real direct track for every learned denial on this route that has
    // not been probed in this process. Runs once per route per process (until invalidate),
    // off the calling thread, and reports each entry that opened through onVerified, which
    // is invoked on the probe thread.
    fun start(routeKey: String, persisted: Set<String>, onVerified: (String) -> Unit) {
        val entries = ledger.entriesToProbe(routeKey, persisted)
        if (entries.isEmpty()) return
        Log.i(TAG, "re-verifying learned denials on $routeKey: $entries")
        Thread({
            for (entry in entries) {
                val group = AudioRejectionLedger.groupOf(entry) ?: continue
                val opened = synchronized(DirectOpenProbeLock) { openAndRelease(group) }
                if (opened) {
                    ledger.markVerified(entry)
                    Log.i(TAG, "re-verify $entry: opened, denial cleared")
                    onVerified(entry)
                } else {
                    Log.i(TAG, "re-verify $entry: still refused")
                }
            }
        }, "audio-rejection-reverify").apply { isDaemon = true }.start()
    }

    private fun openAndRelease(group: AudioPassthroughPolicy.Group): Boolean {
        val encoding = when (group) {
            AudioPassthroughPolicy.Group.AC3 -> AudioFormat.ENCODING_AC3
            AudioPassthroughPolicy.Group.EAC3 -> AudioFormat.ENCODING_E_AC3
            AudioPassthroughPolicy.Group.TRUEHD -> AudioFormat.ENCODING_DOLBY_TRUEHD
            AudioPassthroughPolicy.Group.DTS -> AudioFormat.ENCODING_DTS
            AudioPassthroughPolicy.Group.DTS_HD -> AudioFormat.ENCODING_DTS_HD
        }
        val channelMask = AudioFormat.CHANNEL_OUT_5POINT1
        val minBuffer = AudioTrack.getMinBufferSize(PROBE_SAMPLE_RATE, channelMask, encoding)
        if (minBuffer <= 0) return false
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(PROBE_SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            initialized
        } catch (_: Exception) {
            false
        }
    }

    private const val PROBE_SAMPLE_RATE = 48_000
}

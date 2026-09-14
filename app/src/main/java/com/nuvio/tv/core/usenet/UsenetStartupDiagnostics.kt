package com.nuvio.tv.core.usenet

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Device-local, bounded startup measurements. No media URLs or provider data
 * enter the report. Marks are elapsed times; overlapping stages are not summed. */
object UsenetStartupDiagnostics {
    class Trace(val fast: Boolean, val fastNzb: Boolean = true) {
        val start = SystemClock.elapsedRealtime()
        val recordedAt = System.currentTimeMillis()
        val marks = JSONObject()
        var engine = JSONObject()
        var finished = false
        @Synchronized fun mark(name: String) {
            if (!finished && !marks.has(name)) marks.put(name, SystemClock.elapsedRealtime() - start)
        }
    }

    private val traces = LinkedHashMap<String, Trace>()
    private val state = MutableStateFlow("")
    val latest = state.asStateFlow()
    private var preferences: android.content.SharedPreferences? = null

    @Synchronized fun initialize(context: android.content.Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences("usenet_startup_diagnostics", android.content.Context.MODE_PRIVATE)
        state.value = preferences?.getString("lastReport", "").orEmpty()
    }

    @Synchronized fun bind(url: String, trace: Trace, engine: JSONObject?) {
        trace.engine = engine ?: JSONObject()
        traces[url] = trace
        while (traces.size > 4) traces.remove(traces.keys.first())
    }

    @Synchronized fun mark(url: String?, name: String) { traces[url]?.mark(name) }

    @Synchronized fun complete(url: String?, engine: JSONObject? = null): String? {
        val trace = traces[url] ?: return null
        synchronized(trace) {
            if (!trace.marks.has("first_frame")) return null
            if (engine != null) trace.engine = engine
            trace.finished = true
            val report = JSONObject().put("fastMkvStartup", trace.fast)
                .put("fastNzbFetch", trace.fastNzb)
                .put("recordedAtMs", trace.recordedAt)
                .put("marksMs", trace.marks).put("engine", trace.engine).toString()
            state.value = report
            preferences?.edit()?.putString("lastReport", report)?.apply()
            // Split the bounded range list into individual lines to stay below
            // Android's log-entry limit. The in-memory report remains complete.
            Log.i("UsenetStartup", JSONObject().put("fastMkvStartup", trace.fast)
                .put("marksMs", trace.marks).put("engineMarksMs", trace.engine.optJSONObject("marksMs")).toString())
            trace.engine.optJSONArray("ranges")?.let { ranges ->
                for (i in 0 until ranges.length()) Log.i("UsenetStartupRange", ranges.getJSONObject(i).toString())
            }
            return report
        }
    }

    fun attach(player: ExoPlayer, onFirstFrame: (String) -> Unit) {
        player.addAnalyticsListener(object : AnalyticsListener {
            private fun url(event: AnalyticsListener.EventTime): String? =
                if (!event.timeline.isEmpty && event.windowIndex < event.timeline.windowCount)
                    event.timeline.getWindow(event.windowIndex, androidx.media3.common.Timeline.Window())
                        .mediaItem.localConfiguration?.uri?.toString()
                else null

            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                mark(url(eventTime), "first_load")
            }
            override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                if (!tracks.isEmpty) mark(url(eventTime), "tracks_known")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                val stream = url(eventTime)
                mark(stream, "decoder_ready")
                synchronized(this@UsenetStartupDiagnostics) {
                    traces[stream]?.let { trace -> synchronized(trace) {
                        if (!trace.finished && !trace.marks.has("decoder_init_duration")) trace.marks.put("decoder_init_duration", initializationDurationMs)
                    } }
                }
            }
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_READY) mark(url(eventTime), "player_ready")
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                val stream = url(eventTime) ?: return
                val first = synchronized(this@UsenetStartupDiagnostics) {
                    traces[stream]?.let { trace -> synchronized(trace) { !trace.marks.has("first_frame") } } ?: false
                }
                if (first) {
                    mark(stream, "first_frame")
                    complete(stream)
                    onFirstFrame(stream)
                }
            }
        })
    }

    fun attach(player: ExoPlayer, context: android.content.Context) {
        val sidecar = UsenetSidecar.get(context)
        attach(player, sidecar::captureStartup)
    }
}

package com.nuvio.tv.core.usenet

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.Stream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged Go engine + ExoPlayer extractor + Android video decoder.
 * A generated H.264 test pattern is served by controlled TCP NNTP providers.
 * This is an emulator measurement, not a physical TV or the full player UI. */
@RunWith(AndroidJUnit4::class)
class UsenetStartupBenchmarkTest {
    @Test fun measureColdSessionsToRenderedFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("startupBenchmark") == "true")
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("usenet_performance", android.content.Context.MODE_PRIVATE)
        val previous = prefs.all
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val sidecar = UsenetSidecar.get(context)
        val output = File(context.getExternalFilesDir(null), "startup-benchmark.jsonl")
        output.writeText("")
        val bootstrap = File(context.getExternalFilesDir(null), "startup-bootstrap.jsonl")
        bootstrap.writeText("")
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        try {
            prefs.edit().putString("profile", "balanced").putInt("readAhead", 0)
                .putInt("maxConnections", 0).putBoolean("prewarmOnLaunch", true).commit()
            UsenetSidecar.onAppForegrounded()
            for (trial in 1..6) {
                prefs.edit().putBoolean("prewarmOnLaunch", false).commit()
                sidecar.prewarm().join()
                prefs.edit().putBoolean("prewarmOnLaunch", true).commit()
                val cold = SystemClock.elapsedRealtime()
                sidecar.prewarm().join()
                val coldMs = SystemClock.elapsedRealtime() - cold
                val warm = SystemClock.elapsedRealtime()
                sidecar.prewarm().join()
                bootstrap.appendText(JSONObject().put("trial", trial).put("cold_engine_ms", coldMs)
                    .put("warm_engine_ms", SystemClock.elapsedRealtime() - warm).toString() + "\n")
            }
            sidecar.prewarm().join()
            val cases = client.newCall(Request.Builder().url("http://10.0.2.2:28765/cases").build()).execute().use {
                assertEquals(200, it.code); JSONObject(it.body!!.string()).getJSONArray("cases")
            }
            val trials = InstrumentationRegistry.getArguments().getString("startupTrials")?.toIntOrNull()?.coerceIn(1, 20) ?: 6
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                for (trial in 0 until trials) for (order in 0..1) {
                    val fast = (trial + order) % 2 == 1
                    prefs.edit().putBoolean("fastMkvStartup", fast).commit()
                    val stream = Stream(name = "Startup fixture", title = null, description = null, url = null,
                        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
                        addonName = "Local benchmark", addonLogo = null, nzbUrl = case.getString("nzbUrl"),
                        servers = listOf(case.getString("provider")))
                    val resolved = sidecar.resolve(stream, null, null)
                    val url = requireNotNull(resolved.url)
                    val frame = CountDownLatch(1)
                    val error = AtomicReference<PlaybackException?>()
                    var player: ExoPlayer? = null
                    try {
                        instrumentation.runOnMainSync {
                            texture = SurfaceTexture(false)
                            surface = Surface(texture)
                            player = ExoPlayer.Builder(context).build().apply {
                                setVideoSurface(surface)
                                UsenetStartupDiagnostics.attach(this) { streamUrl ->
                                    sidecar.captureStartup(streamUrl)
                                    frame.countDown()
                                }
                                addListener(object : Player.Listener {
                                    override fun onPlayerError(exception: PlaybackException) { error.set(exception); frame.countDown() }
                                })
                                setMediaItem(MediaItem.fromUri(url))
                                UsenetStartupDiagnostics.mark(url, "prepare")
                                prepare()
                                play()
                            }
                        }
                        assertTrue("first frame timed out: ${case.getString("name")}", frame.await(35, TimeUnit.SECONDS))
                        assertNull("ExoPlayer failed", error.get())
                        val deadline = SystemClock.elapsedRealtime() + 2500
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val report = runCatching { JSONObject(UsenetStartupDiagnostics.latest.value) }.getOrNull()
                            if (report?.optJSONObject("engine")?.has("store") == true) break
                            SystemClock.sleep(20)
                        }
                        val report = JSONObject(UsenetStartupDiagnostics.latest.value)
                            .put("network", case.getString("network")).put("archive", case.getBoolean("archive"))
                            .put("trial", trial + 1).put("device", android.os.Build.MODEL)
                            .put("abis", android.os.Build.SUPPORTED_ABIS.joinToString(","))
                            .put("androidApi", android.os.Build.VERSION.SDK_INT)
                        assertEquals(fast, report.getBoolean("fastMkvStartup"))
                        assertTrue(report.getJSONObject("engine").has("store"))
                        assertEquals("the APK must pass the toggle to the native engine", fast,
                            report.getJSONObject("engine").getJSONObject("marksMs").has("warmup_started"))
                        output.appendText(report.toString() + "\n")
                        android.util.Log.i("UsenetBenchmark", "${case.getString("name")} trial=${trial+1} fast=$fast firstFrameMs=${report.getJSONObject("marksMs").getLong("first_frame")}")
                    } finally {
                        instrumentation.runOnMainSync { player?.release(); surface?.release(); texture?.release() }
                        sidecar.release(url)
                        val deadline = SystemClock.elapsedRealtime() + 6000
                        var deleted = false
                        while (!deleted && SystemClock.elapsedRealtime() < deadline) {
                            deleted = client.newCall(Request.Builder().url(url).head().build()).execute().use { it.code == 404 }
                            if (!deleted) SystemClock.sleep(20)
                        }
                        assertTrue("session cleanup did not finish", deleted)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { surface?.release(); texture?.release() }
            val edit = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
                is String -> edit.putString(key,value)
            } }
            edit.commit()
            UsenetSidecar.stopIdleOnBackground()
            sidecar.prewarm().join()
            UsenetSidecar.onAppForegrounded()
        }
    }
}

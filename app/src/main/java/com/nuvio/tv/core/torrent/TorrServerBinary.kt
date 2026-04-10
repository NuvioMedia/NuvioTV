package com.nuvio.tv.core.torrent

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the TorrServer binary lifecycle.
 * The binary is bundled in jniLibs/ as libtorrserver.so and installed
 * to nativeLibraryDir by the Android package manager.
 */
@Singleton
class TorrServerBinary @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TorrServerBinary"
        const val PORT = 8091
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 200L
    }

    private var process: Process? = null
    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    private val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libtorrserver.so")

    private val configDir: File
        get() = File(context.filesDir, "torrserver").also { it.mkdirs() }

    val isBinaryAvailable: Boolean
        get() = binaryFile.exists()

    fun isRunning(): Boolean {
        return try {
            val request = Request.Builder().url("$baseUrl/echo").build()
            healthClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning()) {
            Log.d(TAG, "TorrServer already running")
            return@withContext
        }

        if (!isBinaryAvailable) {
            throw TorrentException("TorrServer binary not found at ${binaryFile.absolutePath}")
        }

        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true)
        }

        val pb = ProcessBuilder(
            binaryFile.absolutePath,
            "--port", PORT.toString(),
            "--path", configDir.absolutePath
        )
        pb.directory(configDir)
        pb.redirectErrorStream(true)

        Log.d(TAG, "Starting TorrServer on port $PORT from ${binaryFile.absolutePath}")
        process = pb.start()

        // Read stdout in daemon thread for debugging
        val proc = process!!
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "[server] $line")
                }
            } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            start()
        }

        // Wait for health check
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                Log.d(TAG, "TorrServer started successfully")
                return@withContext
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }

        stop()
        throw TorrentException("TorrServer failed to start within ${STARTUP_TIMEOUT_MS / 1000}s")
    }

    fun stop() {
        // Try graceful shutdown
        try {
            val request = Request.Builder().url("$baseUrl/shutdown").build()
            healthClient.newCall(request).execute().close()
        } catch (_: Exception) {}

        // Force kill after 3 seconds
        process?.let { proc ->
            try {
                Thread.sleep(3000)
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
        process = null
        Log.d(TAG, "TorrServer stopped")
    }
}

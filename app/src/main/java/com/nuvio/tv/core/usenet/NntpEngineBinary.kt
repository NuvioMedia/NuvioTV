package com.nuvio.tv.core.usenet

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.network.IPv4FirstDns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NntpEngineBinary @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NntpEngineBinary"
        const val PORT = 8191
        const val MANAGEMENT_TOKEN_HEADER = "X-Nuvio-Token"
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 200L
        private const val PREFS_NAME = "nntp_engine"
        private const val TOKEN_KEY = "management_token"
    }

    private val lifecycleMutex = Mutex()
    private var process: Process? = null
    val managementToken: String = loadOrCreateManagementToken()
    private val healthClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    private val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libnuvionntp.so")

    val isBinaryAvailable: Boolean
        get() = binaryFile.exists()

    fun isRunning(): Boolean = try {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .header(MANAGEMENT_TOKEN_HEADER, managementToken)
            .build()
        healthClient.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    suspend fun start() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isRunning()) return@withContext
            stopOrphanedProcess()

            if (!isBinaryAvailable) {
                throw NntpException(
                    context.getString(R.string.nntp_error_binary_missing, binaryFile.absolutePath)
                )
            }
            if (!binaryFile.canExecute()) binaryFile.setExecutable(true)

            val newProcess = ProcessBuilder(
                binaryFile.absolutePath,
                "--port",
                PORT.toString(),
                "--token",
                managementToken
            )
                .redirectErrorStream(true)
                .start()
            process = newProcess
            drainOutput(newProcess)

            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (isRunning()) {
                    Log.d(TAG, "NNTP engine started on loopback")
                    return@withContext
                }
                if (!isProcessAlive(newProcess)) {
                    process = null
                    throw NntpException(context.getString(R.string.nntp_error_process_died))
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }

            stopProcess()
            throw NntpException(
                context.getString(
                    R.string.nntp_error_start_timeout,
                    (STARTUP_TIMEOUT_MS / 1000).toInt()
                )
            )
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) { stopProcess() }
    }

    private fun stopOrphanedProcess() {
        try {
            val request = Request.Builder()
                .url("$baseUrl/shutdown")
                .header(MANAGEMENT_TOKEN_HEADER, managementToken)
                .post(ByteArray(0).toRequestBody())
                .build()
            healthClient.newCall(request).execute().close()
            Thread.sleep(500)
        } catch (_: Exception) {
            // No previous loopback process is responding.
        }
    }

    private fun stopProcess() {
        try {
            val request = Request.Builder()
                .url("$baseUrl/shutdown")
                .header(MANAGEMENT_TOKEN_HEADER, managementToken)
                .post(ByteArray(0).toRequestBody())
                .build()
            healthClient.newCall(request).execute().close()
        } catch (_: Exception) {
            // The process may already be gone.
        }

        process?.let { current ->
            try {
                if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly()
            } catch (_: Exception) {
                current.destroyForcibly()
            }
        }
        process = null
    }

    private fun drainOutput(current: Process) {
        Thread {
            try {
                current.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.i(TAG, line)
                    }
                }
            } catch (_: Exception) {
                // Process shutdown closes the stream.
            }
        }.apply {
            name = "nuvio-nntp-output"
            isDaemon = true
            start()
        }
    }

    private fun isProcessAlive(current: Process?): Boolean = try {
        current?.isAlive == true
    } catch (_: Exception) {
        false
    }

    private fun loadOrCreateManagementToken(): String {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences.getString(TOKEN_KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        preferences.edit().putString(TOKEN_KEY, token).apply()
        return token
    }
}

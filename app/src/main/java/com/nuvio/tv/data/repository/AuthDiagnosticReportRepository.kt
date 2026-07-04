package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.AuthDiagnosticReportApi
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthDiagnosticReportRepository @Inject constructor(
    private val authDiagnosticReportApi: AuthDiagnosticReportApi,
    moshi: Moshi,
    @ApplicationContext context: Context
) {
    private val mutex = Mutex()
    private val queue = AuthDiagnosticReportQueue(
        queueFile = File(context.filesDir, "auth_diagnostics_queue.jsonl"),
        moshi = moshi
    )

    suspend fun submit(report: AuthDiagnosticReportRequestDto): Result<String> = mutex.withLock {
        drainQueuedLocked()
        return@withLock try {
            Result.success(upload(report))
        } catch (e: Exception) {
            enqueueLocked(report)
            Result.failure(e)
        }
    }

    private suspend fun drainQueuedLocked() {
        val queued = queue.read()
        if (queued.isEmpty()) return
        val remaining = mutableListOf<AuthDiagnosticReportRequestDto>()
        var blocked = false
        queued.forEach { report ->
            if (blocked) {
                remaining += report
            } else {
                try {
                    upload(report)
                } catch (_: Exception) {
                    remaining += report
                    blocked = true
                }
            }
        }
        queue.write(remaining)
    }

    private suspend fun upload(report: AuthDiagnosticReportRequestDto): String {
        if (BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank()) {
            error("Auth diagnostics endpoint is not configured")
        }
        val response = authDiagnosticReportApi.createAuthDiagnosticReport(report)
        if (!response.isSuccessful) {
            error("Auth diagnostics upload failed: HTTP ${response.code()}")
        }
        val body = response.body()
        return body?.reportId?.trim()?.takeIf { it.isNotBlank() }
            ?: body?.id?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Auth diagnostics upload failed: missing report id")
    }

    private suspend fun enqueueLocked(report: AuthDiagnosticReportRequestDto) {
        queue.enqueue(report)
    }
}

class AuthDiagnosticReportQueue(
    private val queueFile: File,
    moshi: Moshi,
    private val maxReports: Int = 50
) {
    private val adapter = moshi.adapter(AuthDiagnosticReportRequestDto::class.java)

    suspend fun enqueue(report: AuthDiagnosticReportRequestDto) {
        write(read() + report)
    }

    suspend fun read(): List<AuthDiagnosticReportRequestDto> = withContext(Dispatchers.IO) {
        if (!queueFile.exists()) return@withContext emptyList()
        queueFile.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { adapter.fromJson(line) }.getOrNull() }
            .toList()
    }

    suspend fun write(reports: List<AuthDiagnosticReportRequestDto>) {
        withContext(Dispatchers.IO) {
            val bounded = reports.takeLast(maxReports)
            if (bounded.isEmpty()) {
                if (queueFile.exists()) queueFile.delete()
                return@withContext
            }
            queueFile.parentFile?.mkdirs()
            queueFile.writeText(bounded.joinToString(separator = "\n") { adapter.toJson(it) } + "\n")
        }
    }
}

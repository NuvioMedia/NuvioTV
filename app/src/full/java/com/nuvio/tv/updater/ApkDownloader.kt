package com.nuvio.tv.updater

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(okHttpClient: OkHttpClient) {
    // APKs can legitimately take minutes. Bound a stalled read, not the whole transfer.
    private val client = okHttpClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun download(
        url: String,
        destinationFile: File,
        expectedSizeBytes: Long? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> = suspendCancellableCoroutine { continuation ->
        val call = try {
            client.newCall(Request.Builder().url(url).build())
        } catch (error: IllegalArgumentException) {
            continuation.resume(Result.failure(error)) { }
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(Result.failure(e)) { }
            }

            override fun onResponse(call: Call, response: Response) {
                var partial: File? = null
                var promoted = false
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Download failed: HTTP ${it.code}")
                        val body = it.body ?: throw IOException("Empty download body")
                        val headerSize = body.contentLength().takeIf { size -> size > 0 }
                        val expected = expectedSizeBytes?.takeIf { size -> size > 0 }
                        val limit = expected ?: MAX_APK_BYTES
                        if (limit > MAX_APK_BYTES || (headerSize != null && headerSize > limit)) {
                            throw IOException("APK exceeds download size limit")
                        }
                        if (expected != null && headerSize != null && expected != headerSize) {
                            throw IOException("Download size does not match the release asset")
                        }
                        val parent = destinationFile.absoluteFile.parentFile!!
                        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create update directory")
                        partial = File.createTempFile("apk-", ".part", parent)
                        var downloaded = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partial!!).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Download cancelled")
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    downloaded += read
                                    if (downloaded > limit) throw IOException("APK exceeds download size limit")
                                    output.write(buffer, 0, read)
                                    onProgress(downloaded, expected ?: headerSize)
                                }
                                output.fd.sync()
                            }
                        }
                        if (downloaded == 0L || (expected != null && downloaded != expected) ||
                            (headerSize != null && downloaded != headerSize)) throw IOException("Incomplete APK download")
                        if (!continuation.isActive) throw IOException("Download cancelled")
                        // Unique destination per attempt; preserve any already completed file.
                        if (destinationFile.exists() || !partial!!.renameTo(destinationFile)) {
                            throw IOException("Cannot publish downloaded APK")
                        }
                        promoted = true
                        destinationFile
                    }
                }
                partial?.delete()
                if (!continuation.isActive) {
                    if (promoted) destinationFile.delete()
                    return
                }
                // Cancellation can win after promotion but before the caller resumes.
                continuation.resume(result) { result.getOrNull()?.delete() }
            }
        })
    }

    private companion object {
        const val MAX_APK_BYTES = 1024L * 1024L * 1024L
    }
}

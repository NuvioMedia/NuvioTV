package com.nuvio.tv.ui.screens.player.audiosync.asr

import android.content.Context
import android.net.ConnectivityManager
import com.nuvio.tv.ui.screens.player.audiosync.SpeechModelActions
import com.nuvio.tv.ui.screens.player.audiosync.SpeechModelState
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncStatus
import com.nuvio.tv.ui.screens.player.audiosync.SyncLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The English recognition model (about 74 MB), kept in app storage. It can be downloaded from
 * Settings, and is fetched automatically on an unmetered connection the first time it is needed.
 * Progress and errors are published to [SubtitleSyncStatus.speechModel].
 */
internal object AsrModel {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val DOWNLOAD_MB = 74

    private const val VERSION = "gigaspeech-2023-12-12"

    /** The upstream model repository, pinned to a commit so its files cannot change under us. */
    private const val UPSTREAM =
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/f7515a672ffb69232794aaaa44a0a360f7f25cd1/"

    private class ModelFile(val local: String, val upstream: String, val size: Long, val sha256: String)

    private val FILES = listOf(
        ModelFile(ENCODER, "encoder-epoch-30-avg-1.int8.onnx", 72_850_738L, "d3eff4b1bd747bd781a47966795988539227d05638524b0313f26d3a166962d7"),
        ModelFile(DECODER, "decoder-epoch-30-avg-1.int8.onnx", 540_688L, "2b0580feb757696fa929922670b7126aaee609630349a1809fd5a2aee926d56e"),
        ModelFile(JOINER, "joiner-epoch-30-avg-1.int8.onnx", 259_417L, "80160e45cca71dd52f6b0a6d3d12be18126f5308b2d4ba03f001300fea377c64"),
        ModelFile(TOKENS, "tokens.txt", 5_020L, "0ef7d736bf4de3ef947292e4b119ef13f6808cd5f3aec225a843a7135ac1c2ce"),
    )
    private val TOTAL_BYTES = FILES.sumOf { it.size }

    private val downloading = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /** Registers the Settings actions and publishes the current state. Call once at app start. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        SubtitleSyncStatus.modelActions = object : SpeechModelActions {
            override fun download() {
                val ctx = appContext ?: return
                Thread({ download(ctx) }, "NuvioAsrModelDownload").apply { isDaemon = true }.start()
            }

            override fun delete() {
                val ctx = appContext ?: return
                if (downloading.get()) return
                directory(ctx).deleteRecursively()
                publish(ctx)
            }
        }
        publish(context)
    }

    fun directory(context: Context): File = File(context.filesDir, "audiosync/asr/$VERSION")

    fun isReady(context: Context): Boolean {
        val dir = directory(context)
        return FILES.all { File(dir, it.local).length() == it.size }
    }

    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return !manager.isActiveNetworkMetered
    }

    private fun publish(context: Context, progress: Float = 0f, error: String? = null) {
        SubtitleSyncStatus.publishSpeechModel(
            SpeechModelState(
                supported = true,
                sizeMb = DOWNLOAD_MB,
                downloaded = isReady(context),
                downloading = downloading.get(),
                progress = progress,
                error = error,
            ),
        )
    }

    /**
     * Downloads missing files. Blocking; call from a background thread. Returns true when the model
     * is ready afterwards.
     */
    fun download(context: Context): Boolean {
        if (isReady(context)) {
            publish(context)
            return true
        }
        if (!downloading.compareAndSet(false, true)) return false
        var error: String? = null
        try {
            publish(context)
            val dir = directory(context).apply { mkdirs() }
            var doneBytes = 0L
            for (model in FILES) {
                val file = File(dir, model.local)
                if (file.length() != model.size) {
                    val onBytes: (Long) -> Unit = { bytes ->
                        publish(context, progress = (doneBytes + bytes).toFloat() / TOTAL_BYTES)
                    }
                    val failure = fetch(UPSTREAM + model.upstream, file, model, onBytes)
                    if (failure != null) {
                        error = failure
                        return false
                    }
                }
                doneBytes += model.size
            }
            return isReady(context).also { if (!it) error = "downloaded files are incomplete" }
        } finally {
            downloading.set(false)
            publish(context, error = error)
        }
    }

    /** Returns null on success, otherwise a short reason. */
    private fun fetch(url: String, target: File, expected: ModelFile, onBytes: (Long) -> Unit): String? {
        val partial = File(target.path + ".part")
        return try {
            var connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            var redirects = 0
            // HttpURLConnection does not follow cross-host redirects (GitHub release -> CDN).
            while (connection.responseCode in 300..399 && redirects < 5) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(URL(url), location).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                redirects++
            }
            if (connection.responseCode != 200) {
                val code = connection.responseCode
                connection.disconnect()
                SyncLog.w("model download HTTP $code for $url")
                return "HTTP $code"
            }
            var written = 0L
            var lastReport = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (written - lastReport >= 512 * 1024) {
                            lastReport = written
                            onBytes(written)
                        }
                    }
                }
            }
            connection.disconnect()
            if (partial.length() != expected.size) {
                partial.delete()
                return "size ${partial.length()} != ${expected.size}"
            }
            // Verified before the file is moved into place, where the native runtime loads it.
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256 != expected.sha256) {
                partial.delete()
                SyncLog.w("model checksum mismatch for $url")
                return "checksum mismatch"
            }
            if (!partial.renameTo(target)) return "could not save file"
            null
        } catch (failure: Exception) {
            SyncLog.w("model download failed for $url: ${failure.message}")
            partial.delete()
            failure.message ?: failure.javaClass.simpleName
        }
    }
}

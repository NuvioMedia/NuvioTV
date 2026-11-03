package com.nuvio.tv.reshaped.livetv

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Live TV's own small HTTP client: playlists, provider APIs and guides, never playback. */
internal object LiveTvHttp {
    /** Also carries the list's channel previews, so they stay out of Nuvio's playback networking and speed learning. */
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Opens [url] and hands [block] the body as a stream, un-gzipped when the server sent a
     * gzip file (common for guides) rather than gzip transfer encoding. Runs on the IO pool; cancelling interrupts the read.
     */
    suspend fun <T> stream(
        url: String,
        headers: Map<String, String>,
        readTimeoutSeconds: Long = 0L,
        block: (InputStream) -> T,
    ): T =
        // The call is cancelled with the caller, so a timeout around it returns even while a stalled read waits.
        interruptibleCall { calling ->
            val http = if (readTimeoutSeconds > 0) client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build() else client
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            calling(http.newCall(request)).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                BufferedInputStream(body.byteStream(), BUFFER_BYTES).use { buffered ->
                    block(if (buffered.startsWithGzipMagic()) GZIPInputStream(buffered, BUFFER_BYTES) else buffered)
                }
            }
        }

    /**
     * Saves [url] to [target] gzip-compressed (as sent when the server already gzipped it, else
     * compressed quickly on the way), so a 100+ MB guide takes a few MB on the TV's storage.
     * The old file stays until the new one is complete, and, with [expectXml], until the new one
     * is XML: a panel answering with an HTML login or error page keeps the guide it had.
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        readTimeoutSeconds: Long = 0L,
        expectXml: Boolean = false,
    ) {
        interruptibleCall { calling ->
            // Some panels build their guide on request and send nothing for a minute or more.
            val http = if (readTimeoutSeconds > 0) client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build() else client
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            target.parentFile?.mkdirs()
            // Its own name: a download still stopping must not write into or delete this one.
            val temp = File.createTempFile(target.name, ".part", target.parentFile)
            try {
                calling(http.newCall(request)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    BufferedInputStream(body.byteStream(), BUFFER_BYTES).use { buffered ->
                        if (buffered.startsWithGzipMagic()) {
                            temp.outputStream().use { buffered.copyTo(it, BUFFER_BYTES) }
                        } else {
                            FastGzipOutputStream(temp.outputStream()).use { buffered.copyTo(it, BUFFER_BYTES) }
                        }
                    }
                }
                if (expectXml && !temp.startsLikeXml()) throw IOException("Not a guide")
                if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * Downloads [url] into [target] (gzip, as [download] saves it) while [read] parses the same
     * bytes as they arrive, so a guide is fetched and read in one pass instead of saved first and
     * then read again: on a weak TV that roughly halves the wait. [target] is replaced only when
     * [keep] accepts what was read (an HTML error page keeps the guide saved before).
     */
    suspend fun <T> downloadReading(
        url: String,
        headers: Map<String, String>,
        target: File,
        readTimeoutSeconds: Long,
        read: (InputStream) -> T,
        keep: (T) -> Boolean,
    ): T =
        interruptibleCall { calling ->
            val http = if (readTimeoutSeconds > 0) client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build() else client
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            target.parentFile?.mkdirs()
            // Its own name: a download still stopping must not write into or delete this one.
            val temp = File.createTempFile(target.name, ".part", target.parentFile)
            try {
                val result = calling(http.newCall(request)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    BufferedInputStream(body.byteStream(), BUFFER_BYTES).use { buffered ->
                        val gzipped = buffered.startsWithGzipMagic()
                        val file = java.io.BufferedOutputStream(temp.outputStream(), BUFFER_BYTES)
                        (if (gzipped) file else FastGzipOutputStream(file)).use { sink ->
                            val tee = TeeInputStream(buffered, sink)
                            val parsed = read(if (gzipped) GZIPInputStream(tee, BUFFER_BYTES) else tee)
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            // What the reader left (after </tv>) completes the saved copy.
                            tee.drain()
                            parsed
                        }
                    }
                }
                if (keep(result) && !temp.renameTo(target)) throw IOException("Could not save ${target.name}")
                result
            } finally {
                temp.delete()
            }
        }

    /**
     * [block] on the IO threads, interruptible; the call it passes through `calling` is also
     * cancelled with the coroutine, since a socket read stalled on a slow panel ignores the
     * interrupt and would carry on for the whole read timeout.
     */
    private suspend fun <T> interruptibleCall(block: (calling: (Call) -> Call) -> T): T = coroutineScope {
        val current = java.util.concurrent.atomic.AtomicReference<Call?>()
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                current.get()?.cancel()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) { block { call -> call.also(current::set) } }
        } finally {
            closer.cancel()
        }
    }

    /** Passes every byte read on to [copy]. */
    private class TeeInputStream(private val source: InputStream, private val copy: java.io.OutputStream) : InputStream() {
        override fun read(): Int = source.read().also { if (it >= 0) copy.write(it) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { if (it > 0) copy.write(b, off, it) }

        /**
         * Never 0: GZIPInputStream looks for a further gzip member only when bytes are available,
         * and a download between two packets has none, so a guide made of several members (as
         * some are) would end after the first. At the real end, its look finds nothing and stops.
         */
        override fun available(): Int = maxOf(source.available(), 1)
        fun drain() {
            val buffer = ByteArray(BUFFER_BYTES)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    /** [input], un-gzipped once more when it is itself a gzip file (a .gz guide sent gzipped again). */
    fun gunzipIfNeeded(input: InputStream): InputStream {
        val buffered = input as? BufferedInputStream ?: BufferedInputStream(input, BUFFER_BYTES)
        return if (buffered.startsWithGzipMagic()) GZIPInputStream(buffered, BUFFER_BYTES) else buffered
    }

    /** Whether this gzip file's text starts with `<` (after a byte order mark and spaces). */
    private fun File.startsLikeXml(): Boolean = runCatching {
        GZIPInputStream(inputStream(), 512).use { input ->
            val head = ByteArray(512)
            // One read can return only a few bytes: fill the head first.
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read <= 0) return false
            // A .gz guide sent gzipped again holds a second gzip file: the reader opens it too.
            if (read >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return true
            var index = 0
            if (read >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) index = 3
            while (index < read && head[index].toInt().toChar().isWhitespace()) index++
            index < read && head[index] == '<'.code.toByte()
        }
    }.getOrDefault(false)

    /**
     * Writes [target] gzip-compressed through [write] (blocking; call on the IO pool). The old
     * file stays until the new one is complete, and when [write] returns false.
     */
    fun writeGzip(target: File, write: (java.io.OutputStream) -> Boolean): Boolean {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".part")
        try {
            val keep = FastGzipOutputStream(temp.outputStream()).use(write)
            if (!keep) return false
            if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
            return true
        } finally {
            temp.delete()
        }
    }

    /**
     * Channel logos. IPTV panels often serve them slowly and refuse many connections at once, so
     * they get longer timeouts than Nuvio's posters and a few requests per host at a time.
     */
    internal val logoClient: OkHttpClient by lazy {
        client.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 6
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Reads a file saved by [download]. Runs on the IO pool; cancelling interrupts the read. */
    suspend fun <T> readFile(file: File, block: (InputStream) -> T): T =
        runInterruptible(Dispatchers.IO) {
            BufferedInputStream(file.inputStream(), BUFFER_BYTES).use { buffered ->
                block(if (buffered.startsWithGzipMagic()) GZIPInputStream(buffered, BUFFER_BYTES) else buffered)
            }
        }

    /** A small response (provider API calls) as text. */
    suspend fun text(url: String, headers: Map<String, String>): String =
        stream(url, headers) { it.bufferedReader().readText().removePrefix("\uFEFF") }

    private fun BufferedInputStream.startsWithGzipMagic(): Boolean {
        mark(2)
        val first = read()
        val second = read()
        reset()
        return first == 0x1f && second == 0x8b
    }

    private const val BUFFER_BYTES = 64 * 1024

    /** How long a guide download may wait for data (see [download]). */
    const val GUIDE_READ_TIMEOUT_S = 120L
    /** Channel lists a panel builds on request may send nothing for a minute or more. */
    const val LIST_READ_TIMEOUT_S = 120L

    /** Lowest compression: XML still shrinks about tenfold, at little CPU on a weak TV. */
    private class FastGzipOutputStream(out: java.io.OutputStream) : GZIPOutputStream(out, BUFFER_BYTES) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }
}

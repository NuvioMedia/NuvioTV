package com.nuvio.tv.core.subtitlefont

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class CustomSubtitleFont(
    val file: File,
    /** Family name from the font's `name` table; libass (mpv) matches fonts by it. */
    val familyName: String,
    val typeface: Typeface,
)

internal enum class SubtitleFontImportResult {
    IMPORTED,
    TOO_LARGE,
    INVALID,
    DOWNLOAD_FAILED,
}

/**
 * Keeps at most one user-imported subtitle font in app storage. The font file itself is the
 * persisted setting: no file means the default system font. Every import is validated (size,
 * TrueType/OpenType signature, Android can load it, the family name can be read) before it
 * replaces the current font, and a font that stops loading falls back to the default.
 */
internal object SubtitleFontStore {
    private const val TAG = "SubtitleFontStore"
    const val MAX_FONT_BYTES = 20L * 1024 * 1024
    private const val FONTS_DIR = "subtitle_fonts"
    private const val STAGING_PREFIX = ".import"

    private val _font = MutableStateFlow<CustomSubtitleFont?>(null)
    val font: StateFlow<CustomSubtitleFont?> = _font.asStateFlow()

    @Volatile
    private var loaded = false

    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun fontsDir(context: Context): File =
        File(context.applicationContext.filesDir, FONTS_DIR).apply { mkdirs() }

    /** The imported font, loaded from disk on first use; null for the default font. */
    fun current(context: Context): CustomSubtitleFont? {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    val files = fontsDir(context).listFiles().orEmpty()
                    // Staging files left behind by an import that was killed mid-copy.
                    files.filter { it.isFile && it.name.startsWith(STAGING_PREFIX) }.forEach { it.delete() }
                    val file = files.firstOrNull { it.isFile && !it.name.startsWith(".") }
                    val font = file?.let(::loadFont)
                    if (file != null && font == null) {
                        // A font that no longer loads: drop it so playback uses the default font.
                        file.delete()
                    }
                    _font.value = font
                    loaded = true
                }
            }
        }
        return _font.value
    }

    /** At launch: loads the font off the main thread so playback never parses it there. */
    fun warmUp(context: Context) {
        if (loaded) return
        val appContext = context.applicationContext
        warmUpScope.launch { runCatching { current(appContext) } }
    }

    /** The font if already loaded; never touches disk (starts the load instead). */
    private fun cached(context: Context): CustomSubtitleFont? {
        if (!loaded) warmUp(context)
        return _font.value
    }

    /**
     * The ExoPlayer subtitle typeface, keeping the bold setting; null for the default font.
     * Non-blocking: the player re-applies its style when [font] emits.
     */
    fun exoTypeface(context: Context, bold: Boolean): Typeface? {
        val custom = runCatching { cached(context)?.typeface }.getOrNull() ?: return null
        return if (bold) Typeface.create(custom, Typeface.BOLD) else custom
    }

    /**
     * libmpv options that make libass use the imported font; empty for the default font.
     * Non-blocking: relies on [warmUp] having run at app start.
     */
    fun mpvOptions(context: Context): List<Pair<String, String>> {
        val custom = runCatching { cached(context) }.getOrNull() ?: return emptyList()
        return listOf(
            "sub-fonts-dir" to fontsDir(context).path,
            "sub-font" to custom.familyName,
        )
    }

    suspend fun importFromUri(context: Context, uri: Uri): SubtitleFontImportResult =
        withContext(Dispatchers.IO) {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return@withContext SubtitleFontImportResult.INVALID
            input.use { importFromStream(context, it, declaredLength = null, coroutineContext = coroutineContext) }
        }

    suspend fun importFromUrl(context: Context, url: String): SubtitleFontImportResult =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return@withContext SubtitleFontImportResult.DOWNLOAD_FAILED
            }
            val callContext = coroutineContext
            try {
                val request = Request.Builder().url(trimmed).get().build()
                val call = httpClient.newCall(request)
                // Closing the dialog cancels the coroutine: abort the socket so nothing imports later.
                val cancelHandle = callContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) return@withContext SubtitleFontImportResult.DOWNLOAD_FAILED
                        val body = response.body ?: return@withContext SubtitleFontImportResult.DOWNLOAD_FAILED
                        val length = body.contentLength()
                        if (length > MAX_FONT_BYTES) return@withContext SubtitleFontImportResult.TOO_LARGE
                        body.byteStream().use {
                            importFromStream(context, it, declaredLength = null, coroutineContext = callContext)
                        }
                    }
                } finally {
                    cancelHandle?.dispose()
                }
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Bad font URL", error)
                SubtitleFontImportResult.DOWNLOAD_FAILED
            } catch (error: IOException) {
                Log.w(TAG, "Font download failed", error)
                SubtitleFontImportResult.DOWNLOAD_FAILED
            }
        }

    /**
     * Copies [input] to a staging file, validates it and makes it the subtitle font. Blocking.
     * With [declaredLength], exactly that many bytes are read (an HTTP request body).
     */
    fun importFromStream(
        context: Context,
        input: InputStream,
        declaredLength: Long?,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): SubtitleFontImportResult {
        if (declaredLength != null && declaredLength > MAX_FONT_BYTES) {
            return SubtitleFontImportResult.TOO_LARGE
        }
        val dir = fontsDir(context)
        val staging = try {
            File.createTempFile(STAGING_PREFIX, ".tmp", dir)
        } catch (error: IOException) {
            Log.w(TAG, "Font staging failed", error)
            return SubtitleFontImportResult.INVALID
        }
        try {
            val copied = try {
                copyLimited(input, staging, declaredLength, coroutineContext)
            } catch (error: IOException) {
                Log.w(TAG, "Font copy failed", error)
                return SubtitleFontImportResult.DOWNLOAD_FAILED
            }
            if (copied == null) return SubtitleFontImportResult.TOO_LARGE
            val extension = fontExtension(staging) ?: return SubtitleFontImportResult.INVALID
            loadFont(staging) ?: return SubtitleFontImportResult.INVALID
            coroutineContext.ensureActive()
            synchronized(this) {
                dir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") }
                    ?.forEach { it.delete() }
                val target = File(dir, "subtitle_font.$extension")
                if (!staging.renameTo(target)) return SubtitleFontImportResult.INVALID
                val font = loadFont(target)
                if (font == null) target.delete()
                _font.value = font
                loaded = true
                return if (font != null) SubtitleFontImportResult.IMPORTED else SubtitleFontImportResult.INVALID
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            fontsDir(context).listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.forEach { it.delete() }
            _font.value = null
            loaded = true
        }
    }

    /** Copies at most [MAX_FONT_BYTES]; returns the byte count, or null when the file is too large. */
    private fun copyLimited(
        input: InputStream,
        target: File,
        declaredLength: Long?,
        coroutineContext: CoroutineContext,
    ): Long? {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (declaredLength == null || total < declaredLength) {
                coroutineContext.ensureActive()
                val wanted = if (declaredLength == null) {
                    buffer.size
                } else {
                    minOf(buffer.size.toLong(), declaredLength - total).toInt()
                }
                val read = input.read(buffer, 0, wanted)
                if (read < 0) break
                total += read
                if (total > MAX_FONT_BYTES) return null
                output.write(buffer, 0, read)
            }
        }
        if (declaredLength != null && total < declaredLength) throw IOException("Upload ended early")
        return total
    }

    private fun loadFont(file: File): CustomSubtitleFont? = runCatching {
        if (fontExtension(file) == null) return@runCatching null
        val typeface = Typeface.createFromFile(file)
        if (typeface == null || typeface == Typeface.DEFAULT) return@runCatching null
        val family = readFontFamilyName(file) ?: return@runCatching null
        CustomSubtitleFont(file = file, familyName = family, typeface = typeface)
    }.getOrElse {
        Log.w(TAG, "Unusable font ${file.name}", it)
        null
    }
}

/** "ttf" or "otf" from the file's sfnt signature; null when it is not a single TrueType/OpenType font. */
private fun fontExtension(file: File): String? {
    if (file.length() < 12) return null
    val signature = RandomAccessFile(file, "r").use { it.readInt() }
    return when (signature) {
        0x00010000, 0x74727565 -> "ttf" // TrueType, "true"
        0x4F54544F -> "otf" // "OTTO"
        else -> null
    }
}

/** Reads name ID 1 (font family) from a TrueType/OpenType `name` table. */
private fun readFontFamilyName(file: File): String? = RandomAccessFile(file, "r").use { raf ->
    val fileLength = raf.length()
    val version = raf.readInt()
    if (version != 0x00010000 && version != 0x4F54544F && version != 0x74727565) return null
    val numTables = raf.readUnsignedShort()
    if (numTables > 512) return null
    raf.skipBytes(6)
    var nameOffset = -1L
    repeat(numTables) {
        val tag = raf.readInt()
        raf.skipBytes(4)
        val offset = raf.readInt().toLong() and 0xFFFFFFFFL
        raf.skipBytes(4)
        if (tag == 0x6E616D65) nameOffset = offset // "name"
    }
    if (nameOffset < 0 || nameOffset >= fileLength) return null
    raf.seek(nameOffset)
    raf.skipBytes(2)
    val count = raf.readUnsignedShort()
    val stringsOffset = nameOffset + raf.readUnsignedShort()
    var macFallback: String? = null
    for (i in 0 until count) {
        raf.seek(nameOffset + 6 + i * 12L)
        val platformId = raf.readUnsignedShort()
        val encodingId = raf.readUnsignedShort()
        val languageId = raf.readUnsignedShort()
        val nameId = raf.readUnsignedShort()
        val length = raf.readUnsignedShort()
        val offset = raf.readUnsignedShort()
        if (nameId != 1 || length == 0) continue
        if (stringsOffset + offset + length > fileLength) continue
        val bytes = ByteArray(length)
        raf.seek(stringsOffset + offset)
        raf.readFully(bytes)
        when {
            platformId == 3 || platformId == 0 -> {
                val name = String(bytes, Charsets.UTF_16BE).trim()
                if (name.isNotEmpty() && (platformId == 0 || languageId == 0x0409 || encodingId == 0)) {
                    return name
                }
                if (name.isNotEmpty() && macFallback == null) macFallback = name
            }
            platformId == 1 && encodingId == 0 && macFallback == null ->
                macFallback = String(bytes, Charsets.ISO_8859_1).trim().ifEmpty { null }
        }
    }
    macFallback
}

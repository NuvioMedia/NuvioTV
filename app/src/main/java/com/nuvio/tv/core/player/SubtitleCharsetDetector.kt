package com.nuvio.tv.core.player

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.mozilla.universalchardet.UniversalDetector

/**
 * Detects subtitle encoding and normalizes it to UTF-8.
 *
 * UTF-8 is preferred. Other encodings are detected with UniversalDetector,
 *    with Windows-1252 used as a fallback.
 */
object SubtitleCharsetDetector {

    /** Decodes subtitle bytes using the detected encoding. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0) return ""
        if (isValidUtf8(bytes, offset, length)) {
            return String(bytes, offset, length, Charsets.UTF_8)
        }
        val charset = detectLegacyCharset(bytes, offset, length)
        return String(bytes, offset, length, charset)
    }

    /**
     * Converts subtitle bytes to UTF-8.
     * Returns the original bytes when they are already valid UTF-8.
     */
    fun normalizeToUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (isValidUtf8(bytes, offset, length)) {
            return if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
        }
        val charset = detectLegacyCharset(bytes, offset, length)
        return String(bytes, offset, length, charset).toByteArray(Charsets.UTF_8)
    }

    /**
     * Checks UTF-8 validity without replacing malformed sequences.
     */
    private fun isValidUtf8(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun detectLegacyCharset(bytes: ByteArray, offset: Int, length: Int): Charset {
        val detectedName = try {
            val detector = UniversalDetector(null)
            detector.handleData(bytes, offset, length)
            detector.dataEnd()
            val name = detector.detectedCharset
            detector.reset()
            name
        } catch (e: Exception) {
            Log.w(TAG, "Charset detection failed, falling back to $FALLBACK_CHARSET_NAME", e)
            null
        }

        if (detectedName == null) return FALLBACK_CHARSET

        return runCatching { Charset.forName(detectedName) }
            .getOrElse {
                Log.w(TAG, "Detected charset '$detectedName' unavailable on-device, falling back to $FALLBACK_CHARSET_NAME")
                FALLBACK_CHARSET
            }
    }

    private const val TAG = "SubtitleCharsetDetector"
    private const val FALLBACK_CHARSET_NAME = "windows-1252"
    private val FALLBACK_CHARSET: Charset =
        runCatching { Charset.forName(FALLBACK_CHARSET_NAME) }.getOrDefault(Charsets.ISO_8859_1)
}

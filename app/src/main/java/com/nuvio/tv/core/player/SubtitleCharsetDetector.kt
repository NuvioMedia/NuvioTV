package com.nuvio.tv.core.player

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.mozilla.universalchardet.UniversalDetector

/**
 * Detects the character encoding of subtitle text (SRT/VTT/ASS dialogue bytes) and
 * normalizes it to UTF-8.
 *
 * Subtitle files rarely declare their own encoding (no BOM, no Content-Type charset
 * from the server), and are frequently authored in a legacy single-byte codepage
 * rather than UTF-8 -- which one depends on the subtitle's language. Decoding such
 * bytes as UTF-8 (the usual default of both `String(bytes)` and OkHttp's
 * `ResponseBody.string()`) produces mojibake or U+FFFD replacement characters.
 *
 * Detection strategy:
 * 1. If the bytes are already valid UTF-8, keep them as-is (covers the modern/common
 *    case, and any pure-ASCII text, with zero overhead and no risk of misdetection).
 * 2. Otherwise, defer to [UniversalDetector] (juniversalchardet, a Java port of
 *    Mozilla's statistical charset sniffer) to identify the actual encoding. Unlike
 *    a hand-rolled heuristic -- e.g. "does it decode without unmapped bytes", or
 *    "do the decoded characters fall in language X's Unicode block" -- this uses
 *    real per-language byte/n-gram frequency models, so it can actually tell
 *    Hebrew apart from Cyrillic apart from Greek on the same narrow high-byte
 *    range, which those simpler heuristics cannot (they were tried and rejected
 *    here for exactly that reason: single-byte Windows codepages are almost fully
 *    mapped and put each language's alphabet in a similar byte range, so
 *    "no unmapped bytes" or "lands in the expected Unicode block" is true for
 *    nearly every candidate simultaneously).
 * 3. If detection is inconclusive (short/ambiguous input) or names a charset not
 *    available on-device, fall back to Windows-1252, the most common legacy
 *    default for subtitle files.
 */
object SubtitleCharsetDetector {

    /** Decodes [bytes] to text, auto-detecting its encoding. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0) return ""
        if (isValidUtf8(bytes, offset, length)) {
            return String(bytes, offset, length, Charsets.UTF_8)
        }
        val charset = detectLegacyCharset(bytes, offset, length)
        return String(bytes, offset, length, charset)
    }

    /**
     * Same detection as [decode], but returns UTF-8 bytes instead of a [String].
     * Useful when the caller needs to hand raw bytes to a downstream consumer
     * (e.g. a native subtitle renderer) that assumes UTF-8 input.
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
     * Strict UTF-8 validity check. Unlike `String(bytes, Charsets.UTF_8)` (which
     * never throws and silently substitutes U+FFFD for invalid sequences), this
     * uses a [java.nio.charset.CharsetDecoder] configured to REPORT malformed /
     * unmappable input, so genuinely non-UTF-8 byte sequences are correctly
     * identified instead of being misdetected as "valid" UTF-8 full of
     * replacement characters.
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

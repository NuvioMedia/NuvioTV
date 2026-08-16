package com.nuvio.tv.core.player

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object SubtitleCharsetNormalizer {

    private val windows1252 = Charset.forName("windows-1252")

    fun normalizeToUtf8(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || isValidUtf8(bytes)) {
            return bytes
        }

        return bytes
            .toString(windows1252)
            .toByteArray(Charsets.UTF_8)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))

            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}

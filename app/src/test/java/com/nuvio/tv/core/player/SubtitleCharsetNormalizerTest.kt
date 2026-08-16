package com.nuvio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class SubtitleCharsetNormalizerTest {

    private val windows1252 = Charset.forName("windows-1252")

    @Test
    fun `empty input is left unchanged`() {
        val input = byteArrayOf()

        val result = SubtitleCharsetNormalizer.normalizeToUtf8(input)

        assertArrayEquals(input, result)
    }

    @Test
    fun `windows-1252 Spanish characters are converted to UTF-8`() {
        val original =
            "á é í ó ú Á É Í Ó Ú ñ Ñ ü Ü ¿ ¡"

        val input = original.toByteArray(windows1252)

        val result = SubtitleCharsetNormalizer.normalizeToUtf8(input)

        assertEquals(
            original,
            result.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `windows-1252 Western European characters are converted to UTF-8`() {
        val original =
            "à â æ ç è ê ë ï ô œ ù û ÿ " +
                "À Â Æ Ç È Ê Ë Ï Ô Œ Ù Û Ÿ " +
                "ä ö ß Ä Ö " +
                "ã õ Ã Õ " +
                "å Å ø Ø " +
                "€ “ ” ‘ ’ … – — ™"

        val input = original.toByteArray(windows1252)

        val result = SubtitleCharsetNormalizer.normalizeToUtf8(input)

        assertEquals(
            original,
            result.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `valid UTF-8 is left unchanged`() {
        val input = (
            "á é í ó ú ñ Ñ ü ¿ ¡ " +
                "Français: œ ç è " +
                "Deutsch: ä ö ü ß " +
                "Português: ã õ " +
                "Русский язык " +
                "Ελληνικά " +
                "日本語 " +
                "한국어 " +
                "العربية"
            ).toByteArray(Charsets.UTF_8)

        val result = SubtitleCharsetNormalizer.normalizeToUtf8(input)

        assertArrayEquals(input, result)
    }

    @Test
    fun `windows-1252 SRT content is normalized without altering its structure`() {
        val original = """
            1
            00:00:01,000 --> 00:00:03,000
            ¿Por qué está aquí el niño?

            2
            00:00:04,000 --> 00:00:06,000
            ¡Mañana será más fácil! <i>Señor</i>
        """.trimIndent()

        val input = original.toByteArray(windows1252)

        val result = SubtitleCharsetNormalizer.normalizeToUtf8(input)

        assertEquals(
            original,
            result.toString(Charsets.UTF_8)
        )
    }
}

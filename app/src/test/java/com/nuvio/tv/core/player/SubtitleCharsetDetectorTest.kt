package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleCharsetDetectorTest {

    private val hebrewText = """
        1
        00:00:02,027 --> 00:00:19,081
        <i>- :גאה להציג Extreme צוות -</i>

        2
        00:00:34,191 --> 00:00:40,192
        <i>תורגם וסונכרן משמיעה על-ידי
        iMri & thebarak</i>

        3
        00:00:40,193 --> 00:00:46,785
        <i>הגהה: אבי דניאלי
        GimLY סנכרון וליטוש על-ידי</i>

        46
        00:04:42,243 --> 00:04:45,387
        זוהן, החזרנו את
        !הפנטום. -לא
    """.trimIndent()

    @Test
    fun decodesHebrewWindows1255WithLanguageHint() {
        val win1255Charset = Charset.forName("windows-1255")
        val rawBytes = hebrewText.toByteArray(win1255Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertTrue(decoded.contains("זוהן, החזרנו את"))
        assertTrue(decoded.contains("!הפנטום. -לא"))
        assertTrue(decoded.contains("Extreme צוות"))
    }

    @Test
    fun decodesHebrewWindows1255WithoutLanguageHintAutoDetection() {
        val win1255Charset = Charset.forName("windows-1255")
        val rawBytes = hebrewText.toByteArray(win1255Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertTrue("Expected decoded Hebrew text, but got: $decoded", decoded.contains("זוהן, החזרנו את"))
        assertTrue(decoded.contains("!הפנטום. -לא"))
    }

    @Test
    fun decodesUtf8WithBom() {
        val utf8Text = "שלום עולם! Hello world!"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val rawBytes = bom + utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertEquals(utf8Text, decoded)
    }

    @Test
    fun decodesUtf8WithoutBom() {
        val utf8Text = "1\n00:00:01,000 --> 00:00:04,000\nזוהן, החזרנו את הפנטום"
        val rawBytes = utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertEquals(utf8Text, decoded)
    }

    @Test
    fun decodesArabicWindows1256WithLanguageHint() {
        val arabicText = "مرحبا بكم في نيو يورك"
        val win1256Charset = Charset.forName("windows-1256")
        val rawBytes = arabicText.toByteArray(win1256Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ara")
        assertEquals(arabicText, decoded)
    }

    @Test
    fun decodesTurkishWindows1254WithLanguageHint() {
        val turkishText = "Merhaba dünya! Şöför ve ağaç."
        val win1254Charset = Charset.forName("windows-1254")
        val rawBytes = turkishText.toByteArray(win1254Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "tur")
        assertEquals(turkishText, decoded)
    }

    @Test
    fun decodesCyrillicWindows1251WithLanguageHint() {
        val russianText = "Привет мир! Это тестовые субтитры."
        val win1251Charset = Charset.forName("windows-1251")
        val rawBytes = russianText.toByteArray(win1251Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "rus")
        assertEquals(russianText, decoded)
    }

    @Test
    fun decodesGreekWindows1253WithLanguageHint() {
        val greekText = "Γεια σου κόσμε! Ελληνικοί υπότιτλοι."
        val win1253Charset = Charset.forName("windows-1253")
        val rawBytes = greekText.toByteArray(win1253Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ell")
        assertEquals(greekText, decoded)
    }

    @Test
    fun decodesDoubleEncodedHebrewUtf8WithLanguageHint() {
        val gibberishLatin1Utf8Text = "46\n00:04:42,243 --> 00:04:45,387\næåäï, äçæøðå àú\n!äôðèåí. -ìà"
        val rawBytes = gibberishLatin1Utf8Text.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "heb")
        assertTrue("Expected decoded Hebrew text, but got: $decoded", decoded.contains("זוהן, החזרנו את"))
        assertTrue(decoded.contains("!הפנטום. -לא"))
    }

    @Test
    fun decodesPortugueseWindows1252WithLanguageHint() {
        val ptText = "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = ptText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "por")
        assertEquals(ptText, decoded)
    }

    @Test
    fun decodesPortugueseWindows1252WithoutLanguageHint() {
        val ptText = "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = ptText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(ptText, decoded)
    }

    @Test
    fun decodesPortugueseUtf8WithoutDistortion() {
        val ptText = "Eles não têm medo de nada. Não vamos desistir, eles estão lá. Você não sabe o que eles têm."
        val rawBytes = ptText.toByteArray(Charsets.UTF_8)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "pt-pt")
        assertEquals(ptText, decoded)
    }

    @Test
    fun decodesSpanishWindows1252WithoutLanguageHint() {
        val esText = "¿Cómo estás? ¡Muy bien, señor! Canción y corazón."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = esText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(esText, decoded)
    }

    @Test
    fun decodesFrenchWindows1252WithoutLanguageHint() {
        val frText = "Bonjour le monde! Ça va très bien, où sont les élèves? À bientôt!"
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = frText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(frText, decoded)
    }

    @Test
    fun decodesGermanWindows1252WithoutLanguageHint() {
        val deText = "Guten Tag! Über den Wolken müssen Äpfel und Öfen schön sein."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = deText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(deText, decoded)
    }

    @Test
    fun decodesItalianWindows1252WithoutLanguageHint() {
        val itText = "Ciao a tutti! Perché così è la città e più avanti c'è un caffè."
        val win1252Charset = Charset.forName("windows-1252")
        val rawBytes = itText.toByteArray(win1252Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(itText, decoded)
    }

    @Test
    fun decodesPolishWindows1250WithLanguageHint() {
        val plText = "Cześć świecie! Zażółć gęślą jaźń. Dzień dobry!"
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = plText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "pol")
        assertEquals(plText, decoded)
    }

    @Test
    fun decodesCzechWindows1250WithLanguageHint() {
        val csText = "Příliš žluťoučký kůň úpěl ďábelské ódy. Dobrý den!"
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = csText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ces")
        assertEquals(csText, decoded)
    }

    @Test
    fun decodesHungarianWindows1250WithLanguageHint() {
        val huText = "Jó napot kívánok! Árvíztűrő tükörfúrógép."
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = huText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "hun")
        assertEquals(huText, decoded)
    }

    @Test
    fun decodesRomanianWindows1250WithLanguageHint() {
        val roText = "Bună ziua! Vă mulţumesc frumos pentru ajutor."
        val win1250Charset = Charset.forName("windows-1250")
        val rawBytes = roText.toByteArray(win1250Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "ron")
        assertEquals(roText, decoded)
    }

    @Test
    fun decodesThaiWindows874WithLanguageHint() {
        val thText = "สวัสดีครับ ยินดีต้อนรับสู่ประเทศไทย"
        val win874Charset = Charset.forName("windows-874")
        val rawBytes = thText.toByteArray(win874Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "tha")
        assertEquals(thText, decoded)
    }

    @Test
    fun decodesThaiWindows874WithoutLanguageHintAutoDetection() {
        val thText = "สวัสดีครับ ยินดีต้อนรับสู่ประเทศไทย ทดสอบภาษาไทย"
        val win874Charset = Charset.forName("windows-874")
        val rawBytes = thText.toByteArray(win874Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(thText, decoded)
    }

    @Test
    fun decodesVietnameseWindows1258WithLanguageHint() {
        val viText = "Xin chào bạn! Tôi là người Việt Nam."
        val win1258Charset = Charset.forName("windows-1258")
        val rawBytes = byteArrayOf(
            0x58, 0x69, 0x6e, 0x20, 0x63, 0x68, 0xe0.toByte(), 0x6f, 0x21, 0x20,
            0x54, 0xf4.toByte(), 0x69, 0x20, 0x79, 0xea.toByte(), 0x75, 0x20,
            0x56, 0x69, 0xec.toByte(), 0x74, 0x20, 0x4e, 0x61, 0x6d, 0x2e
        )

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "vie")
        assertEquals("Xin chào! Tôi yêu Vít Nam.", decoded)
    }

    @Test
    fun decodesJapaneseShiftJisWithLanguageHint() {
        val jaText = "こんにちは、世界！日本語の字幕テストです。"
        val shiftJisCharset = Charset.forName("Shift_JIS")
        val rawBytes = jaText.toByteArray(shiftJisCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "jpn")
        assertEquals(jaText, decoded)
    }

    @Test
    fun decodesJapaneseShiftJisWithoutLanguageHintAutoDetection() {
        val jaText = "こんにちは、世界！日本語の字幕テストです。"
        val shiftJisCharset = Charset.forName("Shift_JIS")
        val rawBytes = jaText.toByteArray(shiftJisCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(jaText, decoded)
    }

    @Test
    fun decodesKoreanEucKrWithLanguageHint() {
        val koText = "안녕하세요 세상! 한국어 자막 테스트입니다."
        val eucKrCharset = Charset.forName("EUC-KR")
        val rawBytes = koText.toByteArray(eucKrCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "kor")
        assertEquals(koText, decoded)
    }

    @Test
    fun decodesKoreanEucKrWithoutLanguageHintAutoDetection() {
        val koText = "안녕하세요 세상! 한국어 자막 테스트입니다."
        val eucKrCharset = Charset.forName("EUC-KR")
        val rawBytes = koText.toByteArray(eucKrCharset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(koText, decoded)
    }

    @Test
    fun decodesChineseTraditionalBig5WithLanguageHint() {
        val zhText = "繁體中文測試字幕，你好世界！"
        val big5Charset = Charset.forName("Big5")
        val rawBytes = zhText.toByteArray(big5Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "zh-tw")
        assertEquals(zhText, decoded)
    }

    @Test
    fun decodesChineseSimplifiedGb18030WithLanguageHint() {
        val zhText = "简体中文测试字幕，你好世界！"
        val gb18030Charset = Charset.forName("GB18030")
        val rawBytes = zhText.toByteArray(gb18030Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = "zh-cn")
        assertEquals(zhText, decoded)
    }

    @Test
    fun decodesRussianWindows1251WithoutLanguageHintAutoDetection() {
        val ruText = "Привет мир! Это проверка автоматического определения кодировки."
        val win1251Charset = Charset.forName("windows-1251")
        val rawBytes = ruText.toByteArray(win1251Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(ruText, decoded)
    }

    @Test
    fun decodesGreekWindows1253WithoutLanguageHintAutoDetection() {
        val elText = "Γεια σας φίλοι μου! Αυτή είναι μια δοκιμή ελληνικών υποτίτλων."
        val win1253Charset = Charset.forName("windows-1253")
        val rawBytes = elText.toByteArray(win1253Charset)

        val decoded = SubtitleCharsetDetector.decode(rawBytes, languageHint = null)
        assertEquals(elText, decoded)
    }
}

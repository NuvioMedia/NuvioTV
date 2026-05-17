package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerForcedSubtitleLanguageMatcherTest {

    @Test
    fun `pt BR uses generic Portuguese forced subtitle before full subtitle`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Portuguese", language = "pt", forced = true),
                subtitle(index = 1, name = "Portuguese", language = "pt", forced = false)
            ),
            target = "pt-BR",
            selectedAudioTrack = audio("pt-BR")
        )

        assertEquals(0, selected)
    }

    @Test
    fun `pt BR provider alias pob beats generic full Portuguese`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Portuguese", language = "pt", forced = false),
                subtitle(index = 1, name = "Forced", language = "pob", forced = true)
            ),
            target = "pt-BR",
            selectedAudioTrack = audio("pt-BR")
        )

        assertEquals(1, selected)
    }

    @Test
    fun `pt BR chooses Brazilian Portuguese forced over generic Portuguese forced`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Portuguese", language = "pt", forced = true),
                subtitle(index = 1, name = "Brazilian Portuguese", language = "pt", forced = true)
            ),
            target = "pt-BR",
            selectedAudioTrack = audio("pt-BR")
        )

        assertEquals(1, selected)
    }

    @Test
    fun `pt BR full Portuguese only preserves forced only no selection`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Portuguese", language = "pt", forced = false)
            ),
            target = "pt-BR",
            selectedAudioTrack = audio("pt-BR")
        )

        assertEquals(-1, selected)
    }

    @Test
    fun `English full subtitle is preferred over signs and songs when forced subtitles are off`() {
        val selected = findBestInternalSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "English Signs & Songs Forced", language = "en", forced = true),
                subtitle(index = 1, name = "English Full Subtitles [Asakura]", language = "en", forced = false)
            ),
            targets = listOf("en"),
            normalOnly = true
        )

        assertEquals(1, selected)
    }

    @Test
    fun `English forced subtitle is used when forced subtitles are on`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "English Signs & Songs Forced", language = "en", forced = true),
                subtitle(index = 1, name = "English Full Subtitles [Asakura]", language = "en", forced = false)
            ),
            target = "en",
            selectedAudioTrack = audio("en")
        )

        assertEquals(0, selected)
    }

    @Test
    fun `pt PT specific forced beats pt BR forced`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Brazilian Portuguese", language = "pt", forced = true),
                subtitle(index = 1, name = "Portuguese Portugal", language = "pt", forced = true)
            ),
            target = "pt-PT",
            selectedAudioTrack = audio("pt-PT")
        )

        assertEquals(1, selected)
    }

    @Test
    fun `pt PT forced uses bare embedded regional tags to avoid Brazilian`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Brazilian", language = "por", forced = true),
                subtitle(index = 1, name = "Iberian", language = "por", forced = true)
            ),
            target = "pt-PT",
            selectedAudioTrack = audio("pt-PT")
        )

        assertEquals(1, selected)
        assertEquals("pt-br", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "Brazilian", "sub-0"))
        assertEquals("pt-pt", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "Iberian", "sub-1"))
    }

    @Test
    fun `pt PT primary beats pt BR secondary for embedded generic Portuguese tracks`() {
        val selected = findBestInternalSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Brazilian", language = "por", forced = false),
                subtitle(index = 1, name = "Iberian", language = "por", forced = false)
            ),
            targets = listOf("pt-PT", "pt-BR")
        )

        assertEquals(1, selected)
    }

    @Test
    fun `pt PT uses generic Portuguese subtitle when no regional track exists`() {
        val selected = findBestInternalSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Portuguese", language = "por", forced = false)
            ),
            targets = listOf("pt-PT")
        )

        assertEquals(0, selected)
    }

    @Test
    fun `short Portuguese regional tags from embedded labels are detected`() {
        assertEquals("pt-br", PlayerSubtitleUtils.detectTrackLanguageVariant(null, "Brazilian", "sub-0"))
        assertEquals("pt-br", PlayerSubtitleUtils.detectTrackLanguageVariant("und", "Brazilian", "sub-1"))
        assertEquals("pt-br", PlayerSubtitleUtils.detectTrackLanguageVariant("pt-PT", "Brazilian", "sub-2"))
        assertEquals("pt-pt", PlayerSubtitleUtils.detectTrackLanguageVariant("pt-BR", "Iberian", "sub-3"))
        assertEquals("pt-pt", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "European", "sub-4"))
        assertEquals("pt-pt", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "Portuguese Portugal", "sub-5"))
        assertEquals("pt-br", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "BTM BR", "sub-0"))
        assertEquals("pt-br", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "BTM (BR)", "sub-1"))
        assertEquals("pt-pt", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "BTM EU", "sub-2"))
        assertEquals("pt-pt", PlayerSubtitleUtils.detectTrackLanguageVariant("por", "BTM (EU)", "sub-3"))
        assertTrue(
            PlayerSubtitleUtils.containsAnyLanguageTag(
                name = "BTM pt-br",
                language = "por",
                trackId = "sub-4",
                tags = PlayerSubtitleUtils.BRAZILIAN_TAGS
            )
        )
    }

    @Test
    fun `Spanish Latino uses generic Spanish forced subtitle before full subtitle`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Spanish", language = "es", forced = true),
                subtitle(index = 1, name = "Spanish", language = "es", forced = false)
            ),
            target = "es-419",
            selectedAudioTrack = audio("es-419")
        )

        assertEquals(0, selected)
    }

    @Test
    fun `Spanish Latino specific forced beats generic Spanish forced`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Spanish", language = "es", forced = true),
                subtitle(index = 1, name = "Spanish Latino", language = "es", forced = true)
            ),
            target = "es-419",
            selectedAudioTrack = audio("es-419")
        )

        assertEquals(1, selected)
    }

    @Test
    fun `Castilian preference chooses Castilian over Spanish Latino`() {
        val selected = findBestForcedSubtitleTrackIndex(
            subtitleTracks = listOf(
                subtitle(index = 0, name = "Spanish Latino", language = "es", forced = true),
                subtitle(index = 1, name = "Castilian", language = "es", forced = true)
            ),
            target = "es-ES",
            selectedAudioTrack = audio("es-ES")
        )

        assertEquals(1, selected)
    }

    @Test
    fun `Chinese script preferences choose matching script and allow generic fallback`() {
        val simplified = subtitle(index = 0, name = "Chinese Simplified", language = "zh", forced = true)
        val traditional = subtitle(index = 1, name = "Chinese Traditional", language = "zh", forced = true)

        assertEquals(
            0,
            findBestForcedSubtitleTrackIndex(listOf(simplified, traditional), "zh-Hans", audio("zh-Hans"))
        )
        assertEquals(
            1,
            findBestForcedSubtitleTrackIndex(listOf(simplified, traditional), "zh-Hant", audio("zh-Hant"))
        )
        assertEquals(
            0,
            findBestForcedSubtitleTrackIndex(
                subtitleTracks = listOf(subtitle(index = 0, name = "Chinese", language = "zh", forced = true)),
                target = "zh-Hant",
                selectedAudioTrack = audio("zh-Hant")
            )
        )
    }

    @Test
    fun `French Canadian prefers regional forced then generic forced`() {
        assertEquals(
            0,
            findBestForcedSubtitleTrackIndex(
                subtitleTracks = listOf(
                    subtitle(index = 0, name = "French Canadian", language = "fr", forced = true),
                    subtitle(index = 1, name = "French", language = "fr", forced = true)
                ),
                target = "fr-CA",
                selectedAudioTrack = audio("fr-CA")
            )
        )
        assertEquals(
            0,
            findBestForcedSubtitleTrackIndex(
                subtitleTracks = listOf(subtitle(index = 0, name = "French", language = "fr", forced = true)),
                target = "fr-CA",
                selectedAudioTrack = audio("fr-CA")
            )
        )
    }

    @Test
    fun `Norwegian Bokmal and Nynorsk prefer matching variant over generic Norwegian`() {
        val generic = subtitle(index = 0, name = "Norwegian", language = "no", forced = true)
        val bokmal = subtitle(index = 1, name = "Norwegian Bokmål", language = "no", forced = true)
        val nynorsk = subtitle(index = 2, name = "Norwegian Nynorsk", language = "no", forced = true)

        assertEquals(1, findBestForcedSubtitleTrackIndex(listOf(generic, bokmal), "nb", audio("nb")))
        assertEquals(2, findBestForcedSubtitleTrackIndex(listOf(generic, nynorsk), "nn", audio("nn")))
    }

    @Test
    fun `legacy provider codes score as language aliases`() {
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("iw", "he") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("in", "id") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("jw", "jv") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("Farsi", "fa") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("Persian", "fa") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("fas", "fa") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
        assertTrue(PlayerSubtitleUtils.scoreLanguageMatch("per", "fa") >= PlayerSubtitleUtils.LANGUAGE_MATCH_ALIAS)
    }

    @Test
    fun `related cross language fallbacks are not added`() {
        assertEquals(0, PlayerSubtitleUtils.scoreLanguageMatch("Malay", "id"))
        assertEquals(0, PlayerSubtitleUtils.scoreLanguageMatch("Indonesian", "ms"))
        assertEquals(0, PlayerSubtitleUtils.scoreLanguageMatch("Croatian", "sr-Cyrl"))
        assertEquals(0, PlayerSubtitleUtils.scoreLanguageMatch("Urdu", "hi"))
    }

    @Test
    fun `technical track ids are not treated as languages`() {
        assertEquals("", PlayerSubtitleUtils.detectTrackLanguageVariant(null, "0:6", "sub-6"))
        assertEquals("nl", PlayerSubtitleUtils.detectTrackLanguageVariant("nl", "0:5", "sub-5"))
        assertEquals(0, PlayerSubtitleUtils.scoreLanguageMatch("0:6", "en"))
        assertTrue(PlayerSubtitleUtils.isTechnicalTrackLabel("0:5"))
        assertTrue(PlayerSubtitleUtils.isTechnicalTrackLabel("0:6"))
        assertTrue(PlayerSubtitleUtils.isTechnicalTrackLabel("0:7"))
        assertTrue(PlayerSubtitleUtils.isTechnicalTrackLabel("0:8"))
    }

    private fun subtitle(
        index: Int,
        name: String,
        language: String?,
        forced: Boolean
    ): TrackInfo {
        return TrackInfo(
            index = index,
            name = name,
            language = language,
            trackId = "sub-$index",
            isForced = forced
        )
    }

    private fun audio(language: String): TrackInfo {
        return TrackInfo(
            index = 0,
            name = language,
            language = language,
            trackId = "audio-$language"
        )
    }
}

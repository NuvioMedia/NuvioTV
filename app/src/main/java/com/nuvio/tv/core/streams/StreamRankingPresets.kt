package com.nuvio.tv.core.streams

import kotlinx.serialization.Serializable

@Serializable enum class StreamRankingPreset { ORIGINAL, BALANCED, BEST_QUALITY, SMALLER_FILES, CUSTOM }

@Serializable data class StreamRankingSettings(
    val preset: StreamRankingPreset = StreamRankingPreset.ORIGINAL,
    val custom: StreamRankingProfile? = null
) {
    fun validate() {
        custom?.validate()
        require(preset != StreamRankingPreset.CUSTOM || custom != null) { "Save a custom profile before selecting it" }
    }
    fun profile(): StreamRankingProfile? = when (preset) {
        StreamRankingPreset.ORIGINAL -> null
        StreamRankingPreset.CUSTOM -> custom
        else -> StreamRankingPresets.profile(preset)
    }
}

/**
 * Nuvio's own profiles. Quality tiers are inspired by Tam-Taro's approach; release-group membership
 * was checked against TRaSH Guides' Radarr tiers (October 2026). These are source-specific boosts,
 * not guarantees about a release, and unknown groups remain eligible.
 * See docs/USENET_SORTING.md for sources and the profile's exact behaviour.
 */
object StreamRankingPresets {
    private fun condition(field: StreamRankingField, value: String) = StreamRankingCondition(field, StreamRankingOperator.IS, value)
    private fun groups(name: String, qualities: String, names: String, score: Int) = StreamRankingRule(
        name = name, score = score, conditions = listOf(condition(StreamRankingField.QUALITY, qualities), condition(StreamRankingField.GROUP, names))
    )
    private val groupRules = listOf(
        groups("Remux • leading groups", "REMUX", "3L,ATELiER,BiZKiT,BLURANiUM,BMF,CiNEPHiLES,FraMeSToR,PiRAMiDHEAD,PmP,WiLDCAT,ZQ", 300),
        groups("Remux • established groups", "REMUX", "NCmt,playBD,SiCFoI,SURFINBIRD,TEPES", 200),
        groups("Remux • other recommended groups", "REMUX", "12GaugeShotgun,decibeL,EPSiLON,HiFi,iFT,KRaLiMaRKo,NTb,PTP,SumVision,TOA,TRiToN", 100),
        groups("WEB • leading groups", "WEB_DL,WEBRIP", "ABBIE,AJP69,APEX,PAXA,PEXA,XEPA,BLUTONiUM,BYNDR,CMRG,CRFW,CRUD,FLUX,GNOME,HONE,KiNGS,Kitsune,MADSKY,NOSiViD,NTb,NTG,RAWR,SiC,TEPES,TheFarm,ZoroSenpai", 300),
        groups("WEB • established groups", "WEB_DL,WEBRIP", "dB,Flights,MiU,monkee,MZABI,PHOENiX,playWEB,SbR,SMURF,TOMMY,XEBEC,4KBEC,CEBEX", 200),
        groups("WEB • other recommended groups", "WEB_DL,WEBRIP", "BLOOM,Dooky,GNOMiSSiON,NINJACENTRAL,NPMS,ROCCaT,SiGMA,SLiGNOME,SwAgLaNdEr", 100),
        groups("Blu-ray • leading groups", "BLURAY", "ATELiER,BBQ,BMF,c0kE,Chotab,CRiSC,CtrlHD,D-Z0N3,Dariush,decibeL,DON,EbP,EDPH,Geek,LolHD,NCmt,PTer,TayTO,TDD,TnP,VietHD,ZQ,ZoroSenpai,MainFrame,W4NK3R", 250)
    )
    private val compressedGroups = "aXXo,MeGusta,mSD,mHD,nHD,nSD,YIFY,YTS"
    private val upscalePattern = "(?:^|[ ._-])(?:upscaled?|ai[ ._-]?enhanced|ai[ ._-]?upscale)(?:$|[ ._-])"
    private val baseRules = groupRules + listOf(
        StreamRankingRule("Avoid heavily compressed groups when alternatives exist", action = StreamRankingAction.EXCLUDE,
            minimumRemaining = 5, conditions = listOf(condition(StreamRankingField.GROUP, compressedGroups))),
        StreamRankingRule("Avoid labelled upscales when alternatives exist", action = StreamRankingAction.EXCLUDE,
            minimumRemaining = 5, conditions = listOf(StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, upscalePattern))),
        StreamRankingRule("Heavily compressed release group", score = -1000,
            conditions = listOf(condition(StreamRankingField.GROUP, compressedGroups))),
        StreamRankingRule("Repack / proper", score = 15, conditions = listOf(
            StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, "(?:^|[ ._-])(?:repack[0-9]*|proper[0-9]*)(?:$|[ ._-])"))),
        StreamRankingRule("Hybrid HDR / Dolby Vision", score = 10, conditions = listOf(condition(StreamRankingField.VISUAL, "HDR_DV"))),
        StreamRankingRule("Lossless / immersive audio", score = 10, conditions = listOf(condition(StreamRankingField.AUDIO, "ATMOS,TRUEHD,DTS_HD"))),
        StreamRankingRule("Upscale / AI label", score = -500, conditions = listOf(
            StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, upscalePattern)))
    )
    private fun tier(name: String, resolution: Int, vararg quality: StreamReleaseQuality) =
        StreamRankingTier(name, listOf(resolution), quality.toList())
    private val qualityTiers = listOf(
        tier("4K remux", 2160, StreamReleaseQuality.REMUX),
        tier("4K Blu-ray encode", 2160, StreamReleaseQuality.BLURAY),
        tier("1080p remux", 1080, StreamReleaseQuality.REMUX),
        tier("4K WEB-DL", 2160, StreamReleaseQuality.WEB_DL),
        tier("4K WEBRip", 2160, StreamReleaseQuality.WEBRIP),
        tier("1080p Blu-ray encode", 1080, StreamReleaseQuality.BLURAY),
        tier("1080p WEB-DL", 1080, StreamReleaseQuality.WEB_DL),
        tier("1080p WEBRip", 1080, StreamReleaseQuality.WEBRIP),
        tier("720p and HD", 720, StreamReleaseQuality.REMUX, StreamReleaseQuality.BLURAY, StreamReleaseQuality.WEB_DL,
            StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV),
        StreamRankingTier("Other known HD", listOf(2160, 1440, 1080), listOf(StreamReleaseQuality.REMUX,
            StreamReleaseQuality.BLURAY, StreamReleaseQuality.WEB_DL, StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV))
    )
    private val balanced = StreamRankingProfile(
        name = "Balanced", tiers = qualityTiers, preferredMovieSizeGb = 30.0, preferredEpisodeSizeGb = 5.0,
        rules = baseRules, topPerBucket = 3
    )
    private val best = balanced.copy(name = "Best Quality", preferredMovieSizeGb = 0.0, preferredEpisodeSizeGb = 0.0,
        topPerBucket = 4, sort = listOf(StreamRankingSort(StreamRankingKey.TIER), StreamRankingSort(StreamRankingKey.SCORE),
            StreamRankingSort(StreamRankingKey.LANGUAGE), StreamRankingSort(StreamRankingKey.SIZE)))
    private val smaller = balanced.copy(name = "Smaller Files", preferredMovieSizeGb = 12.0, preferredEpisodeSizeGb = 2.0,
        topPerBucket = 2,
        tiers = listOf(
            tier("1080p WEB-DL", 1080, StreamReleaseQuality.WEB_DL),
            tier("1080p WEBRip", 1080, StreamReleaseQuality.WEBRIP),
            tier("1080p Blu-ray encode", 1080, StreamReleaseQuality.BLURAY),
            tier("4K WEB-DL", 2160, StreamReleaseQuality.WEB_DL),
            tier("4K WEBRip", 2160, StreamReleaseQuality.WEBRIP),
            tier("720p and HD", 720, StreamReleaseQuality.REMUX, StreamReleaseQuality.BLURAY, StreamReleaseQuality.WEB_DL,
            StreamReleaseQuality.WEBRIP, StreamReleaseQuality.HDTV),
            tier("1080p remux", 1080, StreamReleaseQuality.REMUX),
            tier("4K Blu-ray encode", 2160, StreamReleaseQuality.BLURAY),
            tier("4K remux", 2160, StreamReleaseQuality.REMUX)
        ),
        rules = baseRules + StreamRankingRule("Efficient video codec", score = 30,
            conditions = listOf(condition(StreamRankingField.CODEC, "HEVC,AV1")))
    )
    fun profile(preset: StreamRankingPreset): StreamRankingProfile = when (preset) {
        StreamRankingPreset.BEST_QUALITY -> best
        StreamRankingPreset.SMALLER_FILES -> smaller
        else -> balanced
    }
}

/** A small process cache; editing a profile replaces its compiled matchers instead of growing it forever. */
object StreamRankingEngines {
    private val engines = object : LinkedHashMap<StreamRankingProfile, StreamRankingEngine>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StreamRankingProfile, StreamRankingEngine>?) = size > 8
    }
    @Synchronized fun get(profile: StreamRankingProfile): StreamRankingEngine = engines.getOrPut(profile) { StreamRankingEngine(profile) }
}

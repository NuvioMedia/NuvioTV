package com.nuvio.tv.ui.navigation

import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Detail : Screen("detail/{itemId}/{itemType}?addonBaseUrl={addonBaseUrl}&returnFocusSeason={returnFocusSeason}&returnFocusEpisode={returnFocusEpisode}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            itemId: String,
            itemType: String,
            addonBaseUrl: String? = null,
            returnFocusSeason: Int? = null,
            returnFocusEpisode: Int? = null
        ): String {
            val encodedItemId = encode(itemId)
            val encodedItemType = encode(itemType)
            val encodedAddon = addonBaseUrl?.let { encode(it) } ?: ""
            return "detail/$encodedItemId/$encodedItemType?addonBaseUrl=$encodedAddon&returnFocusSeason=${returnFocusSeason ?: ""}&returnFocusEpisode=${returnFocusEpisode ?: ""}"
        }
    }
    data object Stream : Screen("stream/{videoId}/{contentType}/{title}?poster={poster}&backdrop={backdrop}&logo={logo}&season={season}&episode={episode}&episodeName={episodeName}&genres={genres}&year={year}&contentId={contentId}&contentName={contentName}&runtime={runtime}&manualSelection={manualSelection}&returnToDetailOnBack={returnToDetailOnBack}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            videoId: String,
            contentType: String,
            title: String,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeName: String? = null,
            genres: String? = null,
            year: String? = null,
            contentId: String? = null,
            contentName: String? = null,
            runtime: Int? = null,
            manualSelection: Boolean = false,
            returnToDetailOnBack: Boolean = false
        ): String {
            val encodedVideoId = encode(videoId)
            val encodedContentTypePath = encode(contentType)
            val encodedTitle = encode(title)
            val encodedPoster = poster?.let { encode(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encode(it) } ?: ""
            val encodedLogo = logo?.let { encode(it) } ?: ""
            val encodedEpisodeName = episodeName?.let { encode(it) } ?: ""
            val encodedGenres = genres?.let { encode(it) } ?: ""
            val encodedYear = year?.let { encode(it) } ?: ""
            val encodedContentId = contentId?.let { encode(it) } ?: ""
            val encodedContentName = contentName?.let { encode(it) } ?: ""
            return "stream/$encodedVideoId/$encodedContentTypePath/$encodedTitle?poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&season=${season ?: ""}&episode=${episode ?: ""}&episodeName=$encodedEpisodeName&genres=$encodedGenres&year=$encodedYear&contentId=$encodedContentId&contentName=$encodedContentName&runtime=${runtime ?: ""}&manualSelection=$manualSelection&returnToDetailOnBack=$returnToDetailOnBack"
        }
    }
    data object Player : Screen("player/{streamUrl}/{title}?streamName={streamName}&year={year}&headers={headers}&contentId={contentId}&contentType={contentType}&contentName={contentName}&poster={poster}&backdrop={backdrop}&logo={logo}&videoId={videoId}&season={season}&episode={episode}&episodeTitle={episodeTitle}&bingeGroup={bingeGroup}&rememberedAudioLanguage={rememberedAudioLanguage}&rememberedAudioName={rememberedAudioName}&autoPlayNav={autoPlayNav}&returnToDetailOnBack={returnToDetailOnBack}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            streamUrl: String,
            title: String,
            streamName: String? = null,
            year: String? = null,
            headers: Map<String, String>? = null,
            contentId: String? = null,
            contentType: String? = null,
            contentName: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            videoId: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            bingeGroup: String? = null,
            rememberedAudioLanguage: String? = null,
            rememberedAudioName: String? = null,
            autoPlayNav: Boolean = false,
            returnToDetailOnBack: Boolean = false
        ): String {
            val encodedUrl = encode(streamUrl)
            val encodedTitle = encode(title)
            val encodedStreamName = streamName?.let { encode(it) } ?: ""
            val encodedYear = year?.let { encode(it) } ?: ""
            val encodedHeaders = headers?.entries?.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }?.let { encode(it) } ?: ""
            val encodedContentId = contentId?.let { encode(it) } ?: ""
            val encodedContentType = contentType?.let { encode(it) } ?: ""
            val encodedContentName = contentName?.let { encode(it) } ?: ""
            val encodedPoster = poster?.let { encode(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encode(it) } ?: ""
            val encodedLogo = logo?.let { encode(it) } ?: ""
            val encodedVideoId = videoId?.let { encode(it) } ?: ""
            val encodedEpisodeTitle = episodeTitle?.let { encode(it) } ?: ""
            val encodedBingeGroup = bingeGroup?.let { encode(it) } ?: ""
            val encodedRememberedAudioLanguage = rememberedAudioLanguage?.let { encode(it) } ?: ""
            val encodedRememberedAudioName = rememberedAudioName?.let { encode(it) } ?: ""
            return "player/$encodedUrl/$encodedTitle?streamName=$encodedStreamName&year=$encodedYear&headers=$encodedHeaders&contentId=$encodedContentId&contentType=$encodedContentType&contentName=$encodedContentName&poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&videoId=$encodedVideoId&season=${season ?: ""}&episode=${episode ?: ""}&episodeTitle=$encodedEpisodeTitle&bingeGroup=$encodedBingeGroup&rememberedAudioLanguage=$encodedRememberedAudioLanguage&rememberedAudioName=$encodedRememberedAudioName&autoPlayNav=$autoPlayNav&returnToDetailOnBack=$returnToDetailOnBack"
        }
    }
    data object Search : Screen("search")
    data object Discover : Screen("discover")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Trakt : Screen("trakt")
    data object TmdbSettings : Screen("tmdb_settings")
    data object ThemeSettings : Screen("theme_settings")
    data object PlaybackSettings : Screen("playback_settings")
    data object About : Screen("about")
    data object AddonManager : Screen("addon_manager")
    data object CatalogOrder : Screen("catalog_order")
    data object Plugins : Screen("plugins")
    data object LayoutSelection : Screen("layout_selection")
    data object LayoutSettings : Screen("layout_settings")
    data object Account : Screen("account")
    data object AuthSignIn : Screen("auth_sign_in")
    data object AuthQrSignIn : Screen("auth_qr_sign_in")
    data object SyncCodeGenerate : Screen("sync_code_generate")
    data object SyncCodeClaim : Screen("sync_code_claim")
    data object CatalogSeeAll : Screen("catalog_see_all/{catalogId}/{addonId}/{type}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(catalogId: String, addonId: String, type: String): String {
            return "catalog_see_all/${encode(catalogId)}/${encode(addonId)}/${encode(type)}"
        }
    }

    data object ProfileSelection : Screen("profile_selection")

    data object CastDetail : Screen("cast_detail/{personId}/{personName}?preferCrew={preferCrew}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            personId: Int,
            personName: String,
            preferCrew: Boolean = false
        ): String {
            return "cast_detail/$personId/${encode(personName)}?preferCrew=$preferCrew"
        }
    }
}

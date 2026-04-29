package com.omnio.phone.ui.screens.player

import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder

/**
 * Mirrors the TV app's `Screen.Player.route` so that the same `PlayerNavigationArgs`
 * parser inside :core-player picks up our query keys verbatim. The path positions
 * and key names must match `PlayerNavigationArgs.from(SavedStateHandle)` exactly.
 */
object PhonePlayerRoute {

    const val KEY_STREAM_URL = "streamUrl"
    const val KEY_TITLE = "title"
    const val KEY_STREAM_NAME = "streamName"
    const val KEY_YEAR = "year"
    const val KEY_HEADERS = "headers"
    const val KEY_CONTENT_ID = "contentId"
    const val KEY_CONTENT_TYPE = "contentType"
    const val KEY_CONTENT_NAME = "contentName"
    const val KEY_POSTER = "poster"
    const val KEY_BACKDROP = "backdrop"
    const val KEY_LOGO = "logo"
    const val KEY_VIDEO_ID = "videoId"
    const val KEY_SEASON = "season"
    const val KEY_EPISODE = "episode"
    const val KEY_EPISODE_TITLE = "episodeTitle"
    const val KEY_BINGE_GROUP = "bingeGroup"
    const val KEY_FILENAME = "filename"
    const val KEY_VIDEO_HASH = "videoHash"
    const val KEY_VIDEO_SIZE = "videoSize"
    const val KEY_START_FROM_BEGINNING = "startFromBeginning"
    const val KEY_ADDON_NAME = "addonName"
    const val KEY_ADDON_LOGO = "addonLogo"
    const val KEY_STREAM_DESCRIPTION = "streamDescription"
    const val KEY_SOURCE_PROVIDER = "sourceProvider"
    const val KEY_PROVIDER_ITEM_ID = "providerItemId"
    const val KEY_PROVIDER_MEDIA_SOURCE_ID = "providerMediaSourceId"

    const val ROUTE: String = "player/{$KEY_STREAM_URL}/{$KEY_TITLE}" +
        "?$KEY_STREAM_NAME={$KEY_STREAM_NAME}" +
        "&$KEY_YEAR={$KEY_YEAR}" +
        "&$KEY_HEADERS={$KEY_HEADERS}" +
        "&$KEY_CONTENT_ID={$KEY_CONTENT_ID}" +
        "&$KEY_CONTENT_TYPE={$KEY_CONTENT_TYPE}" +
        "&$KEY_CONTENT_NAME={$KEY_CONTENT_NAME}" +
        "&$KEY_POSTER={$KEY_POSTER}" +
        "&$KEY_BACKDROP={$KEY_BACKDROP}" +
        "&$KEY_LOGO={$KEY_LOGO}" +
        "&$KEY_VIDEO_ID={$KEY_VIDEO_ID}" +
        "&$KEY_SEASON={$KEY_SEASON}" +
        "&$KEY_EPISODE={$KEY_EPISODE}" +
        "&$KEY_EPISODE_TITLE={$KEY_EPISODE_TITLE}" +
        "&$KEY_BINGE_GROUP={$KEY_BINGE_GROUP}" +
        "&$KEY_FILENAME={$KEY_FILENAME}" +
        "&$KEY_VIDEO_HASH={$KEY_VIDEO_HASH}" +
        "&$KEY_VIDEO_SIZE={$KEY_VIDEO_SIZE}" +
        "&$KEY_START_FROM_BEGINNING={$KEY_START_FROM_BEGINNING}" +
        "&$KEY_ADDON_NAME={$KEY_ADDON_NAME}" +
        "&$KEY_ADDON_LOGO={$KEY_ADDON_LOGO}" +
        "&$KEY_STREAM_DESCRIPTION={$KEY_STREAM_DESCRIPTION}" +
        "&$KEY_SOURCE_PROVIDER={$KEY_SOURCE_PROVIDER}" +
        "&$KEY_PROVIDER_ITEM_ID={$KEY_PROVIDER_ITEM_ID}" +
        "&$KEY_PROVIDER_MEDIA_SOURCE_ID={$KEY_PROVIDER_MEDIA_SOURCE_ID}"

    fun navArguments() = listOf(
        navArgument(KEY_STREAM_URL) { type = NavType.StringType },
        navArgument(KEY_TITLE) { type = NavType.StringType },
        nullableString(KEY_STREAM_NAME),
        nullableString(KEY_YEAR),
        nullableString(KEY_HEADERS),
        nullableString(KEY_CONTENT_ID),
        nullableString(KEY_CONTENT_TYPE),
        nullableString(KEY_CONTENT_NAME),
        nullableString(KEY_POSTER),
        nullableString(KEY_BACKDROP),
        nullableString(KEY_LOGO),
        nullableString(KEY_VIDEO_ID),
        nullableString(KEY_SEASON),
        nullableString(KEY_EPISODE),
        nullableString(KEY_EPISODE_TITLE),
        nullableString(KEY_BINGE_GROUP),
        nullableString(KEY_FILENAME),
        nullableString(KEY_VIDEO_HASH),
        nullableString(KEY_VIDEO_SIZE),
        nullableString(KEY_START_FROM_BEGINNING),
        nullableString(KEY_ADDON_NAME),
        nullableString(KEY_ADDON_LOGO),
        nullableString(KEY_STREAM_DESCRIPTION),
        nullableString(KEY_SOURCE_PROVIDER),
        nullableString(KEY_PROVIDER_ITEM_ID),
        nullableString(KEY_PROVIDER_MEDIA_SOURCE_ID)
    )

    private fun nullableString(name: String) = navArgument(name) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }

    fun create(
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
        filename: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        startFromBeginning: Boolean = false,
        addonName: String? = null,
        addonLogo: String? = null,
        streamDescription: String? = null,
        sourceProvider: String? = null,
        providerItemId: String? = null,
        providerMediaSourceId: String? = null
    ): String {
        val encodedHeaders = headers?.entries
            ?.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            ?.let { enc(it) }
            ?: ""
        val sb = StringBuilder("player/")
        sb.append(enc(streamUrl)).append('/').append(enc(title)).append('?')
        sb.appendQuery(KEY_STREAM_NAME, streamName?.let(::enc) ?: "")
        sb.appendQuery(KEY_YEAR, year?.let(::enc) ?: "")
        sb.appendQuery(KEY_HEADERS, encodedHeaders)
        sb.appendQuery(KEY_CONTENT_ID, contentId?.let(::enc) ?: "")
        sb.appendQuery(KEY_CONTENT_TYPE, contentType?.let(::enc) ?: "")
        sb.appendQuery(KEY_CONTENT_NAME, contentName?.let(::enc) ?: "")
        sb.appendQuery(KEY_POSTER, poster?.let(::enc) ?: "")
        sb.appendQuery(KEY_BACKDROP, backdrop?.let(::enc) ?: "")
        sb.appendQuery(KEY_LOGO, logo?.let(::enc) ?: "")
        sb.appendQuery(KEY_VIDEO_ID, videoId?.let(::enc) ?: "")
        sb.appendQuery(KEY_SEASON, season?.toString() ?: "")
        sb.appendQuery(KEY_EPISODE, episode?.toString() ?: "")
        sb.appendQuery(KEY_EPISODE_TITLE, episodeTitle?.let(::enc) ?: "")
        sb.appendQuery(KEY_BINGE_GROUP, bingeGroup?.let(::enc) ?: "")
        sb.appendQuery(KEY_FILENAME, filename?.let(::enc) ?: "")
        sb.appendQuery(KEY_VIDEO_HASH, videoHash ?: "")
        sb.appendQuery(KEY_VIDEO_SIZE, videoSize?.toString() ?: "")
        sb.appendQuery(KEY_START_FROM_BEGINNING, startFromBeginning.toString())
        sb.appendQuery(KEY_ADDON_NAME, addonName?.let(::enc) ?: "")
        sb.appendQuery(KEY_ADDON_LOGO, addonLogo?.let(::enc) ?: "")
        sb.appendQuery(KEY_STREAM_DESCRIPTION, streamDescription?.let(::enc) ?: "")
        sb.appendQuery(KEY_SOURCE_PROVIDER, sourceProvider?.let(::enc) ?: "")
        sb.appendQuery(KEY_PROVIDER_ITEM_ID, providerItemId?.let(::enc) ?: "")
        sb.appendQuery(KEY_PROVIDER_MEDIA_SOURCE_ID, providerMediaSourceId?.let(::enc) ?: "")
        return sb.toString().removeSuffix("&")
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun StringBuilder.appendQuery(key: String, value: String) {
        append(key).append('=').append(value).append('&')
    }
}

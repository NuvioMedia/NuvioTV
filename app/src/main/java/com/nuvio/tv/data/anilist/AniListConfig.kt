package com.nuvio.tv.data.anilist

import com.nuvio.tv.BuildConfig

object AniListConfig {
    val CLIENT_ID: String = BuildConfig.ANILIST_CLIENT_ID.trim()
    const val AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
    const val PIN_REDIRECT_URI = "https://anilist.co/api/v2/oauth/pin"
    const val GRAPHQL_URL = "https://graphql.anilist.co"
    const val WEBSITE_URL = "https://anilist.co"
    const val ARM_BASE_URL = "https://arm.haglund.dev/api/v2"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank() && CLIENT_ID.all(Char::isDigit)
}

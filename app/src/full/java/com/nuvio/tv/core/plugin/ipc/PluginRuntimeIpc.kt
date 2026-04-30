package com.nuvio.tv.core.plugin.ipc

internal object PluginRuntimeIpc {
    const val MSG_EXECUTE = 1
    const val MSG_RESULT = 2
    const val MSG_ERROR = 3
    const val MSG_CANCEL = 4

    const val KEY_REQUEST_ID = "requestId"
    const val KEY_CODE_PATH = "codePath"
    const val KEY_TMDB_ID = "tmdbId"
    const val KEY_MEDIA_TYPE = "mediaType"
    const val KEY_SEASON = "season"
    const val KEY_EPISODE = "episode"
    const val KEY_SCRAPER_ID = "scraperId"
    const val KEY_SETTINGS_JSON = "settingsJson"
    const val KEY_RESULTS_JSON = "resultsJson"
    const val KEY_ERROR_MESSAGE = "errorMessage"
}

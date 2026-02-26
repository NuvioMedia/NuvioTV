package com.nuvio.tv.core.image

import com.nuvio.tv.core.device.DeviceCapabilities
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbImageUrlOptimizer @Inject constructor(
    private val deviceCapabilities: DeviceCapabilities
) {
    companion object {
        private val TMDB_IMAGE_URL_REGEX =
            Regex("""https?://image\.tmdb\.org/t/p/(w\d+|original)(/[^?#\s]+)""")
    }

    fun optimizePosterUrl(url: String?): String? {
        url ?: return null
        return replaceSize(url, deviceCapabilities.tmdbPosterSize)
    }

    fun optimizeBackdropUrl(url: String?): String? {
        url ?: return null
        return replaceSize(url, deviceCapabilities.tmdbBackdropSize)
    }

    private fun replaceSize(url: String, targetSize: String): String {
        val match = TMDB_IMAGE_URL_REGEX.find(url) ?: return url
        val currentSize = match.groupValues[1]
        if (currentSize == targetSize) return url
        val path = match.groupValues[2]
        return "https://image.tmdb.org/t/p/$targetSize$path"
    }
}

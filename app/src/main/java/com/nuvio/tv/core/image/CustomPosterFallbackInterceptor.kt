package com.nuvio.tv.core.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/**
 * Coil interceptor that detects failed custom poster loads and retries with the
 * original poster URL stored in [ImageRequest.memoryCacheKeyExtra] under the
 * [FALLBACK_URL_KEY] key.
 *
 * This runs globally in the ImageLoader pipeline, so every composable that loads
 * posters (ModernHomeRows, ContentCard, GridContentCard, ContinueWatching, etc.)
 * gets automatic fallback without per-composable code.
 */
class CustomPosterFallbackInterceptor : Interceptor {

    companion object {
        const val FALLBACK_URL_KEY = "custom_poster_fallback_url"
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()

        if (result is ErrorResult) {
            val fallbackUrl = chain.request.memoryCacheKeyExtras[FALLBACK_URL_KEY]
            if (!fallbackUrl.isNullOrBlank()) {
                val fallbackRequest = chain.request.newBuilder()
                    .data(fallbackUrl)
                    .memoryCacheKeyExtras(
                        chain.request.memoryCacheKeyExtras - FALLBACK_URL_KEY
                    )
                    .build()
                return chain.withRequest(fallbackRequest).proceed()
            }
        }

        return result
    }
}

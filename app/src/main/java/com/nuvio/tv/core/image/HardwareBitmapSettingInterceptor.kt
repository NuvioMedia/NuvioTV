package com.nuvio.tv.core.image

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.allowHardware

class HardwareBitmapSettingInterceptor(
    private val enabled: () -> Boolean
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (!enabled() || chain.request.extras[Extras.Key.allowHardware] != null) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().allowHardware(true).build()).proceed()
    }
}

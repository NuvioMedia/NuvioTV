package com.nuvio.tv

import android.app.AppComponentFactory
import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import com.nuvio.tv.ui.trailer.TrailerApplication

/**
 * Custom AppComponentFactory that provides a lightweight [TrailerApplication]
 * for subprocess starts (e.g. the `:trailer` process).
 *
 * Problem: When a new process starts, Android instantiates the Application
 * class declared in the manifest ([NuvioApplication]). This triggers
 * Hilt/Dagger field initializers that load the entire DI component graph
 * (~420MB of heap). For the `:trailer` process, which only needs a WebView,
 * this causes OutOfMemoryError on devices with limited RAM.
 *
 * Solution: Intercept Application creation and return [TrailerApplication]
 * (a plain [Application]) for any subprocess. This prevents [NuvioApplication]
 * from being loaded at all, keeping the trailer process under ~50MB.
 *
 * This class is only loaded on API 28+ (where [AppComponentFactory] exists).
 * On API 24-27, [NuvioApplication.isSubprocess] provides a fallback.
 */
@RequiresApi(Build.VERSION_CODES.P)
class NuvioAppComponentFactory : AppComponentFactory() {

    override fun instantiateApplication(
        cl: ClassLoader,
        className: String
    ): Application {
        val processName = Application.getProcessName() ?: ""
        return if (":" in processName) {
            // Subprocess: use lightweight Application to avoid Hilt class loading
            TrailerApplication()
        } else {
            // Main process: use NuvioApplication with full Hilt DI
            super.instantiateApplication(cl, className)
        }
    }
}

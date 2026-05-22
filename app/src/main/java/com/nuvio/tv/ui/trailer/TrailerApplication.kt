package com.nuvio.tv.ui.trailer

import android.app.Application
import android.webkit.CookieManager

/**
 * Lightweight Application class used exclusively by the :trailer process.
 *
 * When the :trailer process starts, Android creates an Application instance.
 * The default NuvioApplication triggers Hilt/Dagger dependency injection,
 * loading 420MB+ of classes into the heap. By substituting this minimal
 * Application via [com.nuvio.tv.NuvioAppComponentFactory], the trailer
 * process only loads what it needs: this class + TrailerOverlayActivity + WebView.
 *
 * Memory: ~50MB vs ~575MB with NuvioApplication.
 */
class TrailerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("trailer")
            } catch (e: Exception) {
                // Suffix might already be set or WebView already initialized, ignore safely
            }
        }
        // Accept cookies early so YouTube guest session cookies persist across
        // trailer launches, preventing "Sign in to confirm you're not a bot" errors.
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {}
    }
}


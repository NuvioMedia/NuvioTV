package com.nuvio.tv.ui.trailer

import android.app.Application

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
class TrailerApplication : Application()

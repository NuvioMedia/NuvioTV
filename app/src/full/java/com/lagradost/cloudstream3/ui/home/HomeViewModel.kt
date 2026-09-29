@file:Suppress("unused")

package com.lagradost.cloudstream3.ui.home

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * Compatibility stub for CloudStream's HomeViewModel.
 *
 * NuvioTV's real home screen lives at [com.nuvio.tv.ui.screens.home.HomeViewModel].
 * original package at runtime. Without this class the plugin coroutine crashes the
 * process with NoClassDefFoundError when it calls [getResumeWatching].
 */
class HomeViewModel : ViewModel() {
    companion object {
        /**
         * CloudStream plugins call this to read continue-watching items for sync.
         * NuvioTV does not persist CS3 resume state, so this returns empty.
         */
        @JvmStatic
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? = emptyList()
    }
}

@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Compatibility stub for CloudStream's DataStoreHelper.
 *
 * CloudStream plugins (notably SyncPlugin) read resume-watching state through
 * [com.lagradost.cloudstream3.ui.home.HomeViewModel.getResumeWatching], whose
 * return type is [ResumeWatchingResult]. NuvioTV stores progress in its own
 * repositories, so these helpers are no-ops that keep the plugin classloader
 * from crashing on missing CS3 UI types.
 */
object DataStoreHelper {
    const val TAG = "data_store_helper"

    val currentAccount: String = "0"

    data class PosDur(
        val position: Long,
        val duration: Long,
    )

    data class ResumeWatchingResult(
        override val name: String,
        override val url: String,
        override val apiName: String,
        override var type: TvType? = null,
        override var posterUrl: String?,
        val watchPos: PosDur?,
        override var id: Int?,
        val parentId: Int?,
        val episode: Int?,
        val season: Int?,
        val isFromDownload: Boolean,
        override var quality: SearchQuality? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var score: Score? = null,
    ) : SearchResponse

    fun getAllResumeStateIds(): List<Int>? = emptyList()

    fun getViewPos(id: Int?): PosDur? = null

    fun setViewPos(id: Int?, pos: Long, dur: Long) {}

    fun removeLastWatched(parentId: Int?) {}
}

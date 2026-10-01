package com.nuvio.tv.domain.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps catalog provenance available while navigating from Home to Detail.
 * Persistent storage for process recreation is added with the playback flow.
 */
object PluginContentRegistry {
    private const val MAX_ITEMS = 512
    private val entries = ConcurrentHashMap<String, PluginContentRef>()
    private val streamSelections = ConcurrentHashMap<String, PluginSourceRef>()
    @Volatile private var activeStreamSelection: PluginSourceRef? = null

    fun put(items: Iterable<PluginCatalogItem>) {
        items.forEach { item -> entries[item.content.stableId()] = item.content }
        if (entries.size > MAX_ITEMS) {
            entries.keys.take(entries.size - MAX_ITEMS).forEach(entries::remove)
        }
    }

    fun get(stableId: String): PluginContentRef? = entries[stableId]

    /** Captures the Home source choice at navigation time, not catalog-load time. */
    fun selectStreamSource(stableId: String, source: PluginSourceRef?) {
        activeStreamSelection = source
        if (source == null) streamSelections.remove(stableId) else streamSelections[stableId] = source
    }

    /** Falls back to the current Home selection if this content was evicted before navigation. */
    fun selectedStreamSource(stableId: String): PluginSourceRef? =
        streamSelections[stableId] ?: activeStreamSelection
}

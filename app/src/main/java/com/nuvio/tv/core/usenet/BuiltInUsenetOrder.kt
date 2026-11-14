package com.nuvio.tv.core.usenet

import com.nuvio.tv.domain.model.Addon

/**
 * Where built-in Usenet sits among the installed addons. Its place is kept with the
 * device-local Usenet sources, never in the synced addon order, as the addons placed above
 * it: it follows the lowest of those still installed, so addons removed or reordered on
 * another device leave it close to where it was put.
 */
internal object BuiltInUsenetOrder {
    /** The index in [addonUrls] at which built-in Usenet is listed. */
    fun index(addonUrls: List<String>, addonsAbove: List<String>?): Int {
        if (addonsAbove == null) return addonUrls.size
        val above = addonsAbove.mapTo(HashSet(), ::key)
        return addonUrls.indexOfLast { key(it) in above } + 1
    }

    /** Addon names in stream-source order, with built-in Usenet at its place. */
    fun sourceOrder(addons: List<Addon>, addonsAbove: List<String>?): List<String> =
        addons.mapTo(ArrayList(addons.size + 1)) { it.displayName }.apply {
            add(index(addons.map { it.baseUrl }, addonsAbove), BuiltInUsenetService.GROUP_NAME)
        }

    data class Move(val addonUrls: List<String>, val addonsAbove: List<String>)

    /**
     * Moves the addon [url], or built-in Usenet when [url] is null, one place up (-1) or
     * down (+1) in the list of [addonUrls] with built-in Usenet at [usenetIndex].
     * Null when it is already at that end.
     */
    fun move(addonUrls: List<String>, usenetIndex: Int, url: String?, direction: Int): Move? {
        val combined = addonUrls.toMutableList<String?>().apply { add(usenetIndex.coerceIn(0, size), null) }
        val from = combined.indexOf(url)
        val to = from + direction
        if (from < 0 || to !in combined.indices) return null
        combined.add(to, combined.removeAt(from))
        val usenetAt = combined.indexOf(null)
        return Move(combined.filterNotNull(), combined.subList(0, usenetAt).filterNotNull())
    }

    private fun key(url: String) = url.trim().trimEnd('/').lowercase()
}

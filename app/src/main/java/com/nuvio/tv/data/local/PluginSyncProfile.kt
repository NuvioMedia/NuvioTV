package com.nuvio.tv.data.local

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Binds plugin store reads and writes to one profile for the current coroutine.
 * A sync that started for profile 1 keeps using that store after the active profile changes.
 */
internal class PluginSyncProfile(
    private val profileId: Int
) : ThreadContextElement<Int?>, AbstractCoroutineContextElement(Key) {

    override fun updateThreadContext(context: CoroutineContext): Int? {
        val previous = currentProfile.get()
        currentProfile.set(profileId)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int?) {
        if (oldState == null) currentProfile.remove() else currentProfile.set(oldState)
    }

    companion object Key : CoroutineContext.Key<PluginSyncProfile> {
        private val currentProfile = ThreadLocal<Int?>()

        fun current(): Int? = currentProfile.get()
    }
}

/** Profiles that use the primary plugin list share profile 1's store. */
internal fun effectivePluginProfileId(usesPrimaryPlugins: Boolean, activeProfileId: Int): Int =
    if (usesPrimaryPlugins) 1 else activeProfileId

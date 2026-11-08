package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

interface ServerPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

@Serializable
data class StoredServers(
    val connections: List<ServerConnection> = emptyList(),
    val tokens: Map<String, String> = emptyMap(),
    val pendingPush: Boolean = false,
    val syncedKeys: List<String>? = null
) {
    override fun toString(): String = "StoredServers(connections=${connections.size})"
}

@Singleton
class AndroidServerPersistence @Inject constructor(
    @ApplicationContext context: Context
) : ServerPersistence {
    private val preferences = ProtectedProfilePreferences(context, "media_servers", "com.nuvio.tv.mediaservers.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}

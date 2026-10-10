package com.nuvio.tv.core.usenet

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where [NewznabIndexerStates] persists; the app uses private SharedPreferences. */
interface NewznabStateStorage {
    fun load(): String?
    fun save(value: String)
}

@Singleton
class SharedPreferencesNewznabStateStorage @Inject constructor(
    @ApplicationContext context: Context
) : NewznabStateStorage, ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences("usenet_indexer_state", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }

    // Holds no credentials and is shared by profiles; only an account reset clears it.
    override fun removeProfile(profileId: Int) = Unit
    override fun clearAllProfiles() { preferences.edit().clear().apply() }

    private companion object { const val KEY = "indexers" }
}

@Serializable
internal data class NewznabIndexerState(
    val caps: NewznabCapabilities? = null,
    val capsFetchedAt: Long = 0,
    val blockedUntil: Long = 0,
    val touchedAt: Long = 0
)

/**
 * Capabilities and limit cooldowns per indexer, kept across restarts so neither
 * costs API hits again. Keyed by a hash of the endpoint and key: no secret is
 * stored, and an edited indexer starts fresh.
 */
internal class NewznabIndexerStates(private val storage: NewznabStateStorage, private val now: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true }
    private val states: MutableMap<String, NewznabIndexerState> by lazy {
        runCatching { json.decodeFromString<Map<String, NewznabIndexerState>>(storage.load() ?: "{}") }
            .getOrDefault(emptyMap()).toMutableMap()
    }

    @Synchronized
    fun get(indexer: UsenetIndexer): NewznabIndexerState? = states[key(indexer)]

    @Synchronized
    fun update(indexer: UsenetIndexer, change: (NewznabIndexerState) -> NewznabIndexerState) {
        val key = key(indexer)
        states[key] = change(states[key] ?: NewznabIndexerState()).copy(touchedAt = now())
        // Deleted or edited indexers stop being touched; forget them and bound the size.
        states.entries.removeAll { now() - it.value.touchedAt > RETENTION_MS }
        while (states.size > MAX_ENTRIES) states.remove(states.minBy { it.value.touchedAt }.key)
        storage.save(json.encodeToString(states.toMap()))
    }

    private fun key(indexer: UsenetIndexer) = MessageDigest.getInstance("SHA-256")
        .digest("${indexer.apiUrl}\n${indexer.apiKey}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
        const val MAX_ENTRIES = 100
    }
}

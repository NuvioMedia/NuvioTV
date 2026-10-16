package com.nuvio.tv.core.usenet

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.domain.model.Stream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where [UnplayableReleases] persists; the app uses private SharedPreferences. */
interface UnplayableReleaseStorage {
    fun load(): String?
    fun save(value: String)
    /** Changes when the stored list is cleared from outside, so a loaded copy is dropped. */
    val generation: Int get() = 0
}

class SharedPreferencesUnplayableReleaseStorage(context: Context) : UnplayableReleaseStorage, ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences("usenet_unplayable_releases", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    @Volatile override var generation = 0
        private set

    // Holds no credentials and is shared by profiles; only an account reset clears it.
    override fun removeProfile(profileId: Int) = Unit
    override fun clearAllProfiles() {
        preferences.edit().clear().apply()
        generation += 1
    }

    private companion object { const val KEY = "releases" }
}

@Serializable
internal data class UnplayableRelease(val kind: String, val at: Long, val providers: String)

/**
 * Releases the engine found unplayable with the current providers: articles removed or damaged.
 * Such a release fails again on every attempt, and only after its NZB was downloaded, so results
 * put it last and fallback skips it. An entry is keyed by a hash of the NZB URL and by one of the
 * title and size, so the same upload found on another indexer counts too; nothing secret is
 * stored. Other providers may still have the articles, so a change of providers forgets an entry,
 * as do 30 days.
 */
class UnplayableReleases internal constructor(
    private val storage: UnplayableReleaseStorage,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded: MutableMap<String, UnplayableRelease>? = null
    private var loadedGeneration = 0

    /** Loaded on first use, and again after an account reset cleared the storage. */
    private val entries: MutableMap<String, UnplayableRelease>
        get() {
            loaded?.takeIf { loadedGeneration == storage.generation }?.let { return it }
            loadedGeneration = storage.generation
            return runCatching { json.decodeFromString<Map<String, UnplayableRelease>>(storage.load() ?: "{}") }
                .getOrDefault(emptyMap()).toMutableMap().also { loaded = it }
        }

    /** Remembers a release whose open or playback failed with an engine [kind] in [KINDS]; others are ignored. */
    @Synchronized
    fun record(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, kind: String?) {
        if (kind !in KINDS) return
        val keys = keys(nzbUrl, title, size).ifEmpty { return }
        val entry = UnplayableRelease(requireNotNull(kind), now(), providersKey(servers))
        keys.forEach { entries[it] = entry }
        runCatching { android.util.Log.i("UsenetUnplayable", "remembered kind=$kind release=\"${title?.lineSequence()?.firstOrNull()}\" size=${size ?: 0}") }
        entries.entries.removeAll { now() - it.value.at > RETENTION_MS }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.minBy { it.value.at }.key)
        storage.save(json.encodeToString(entries.toMap()))
    }

    @Synchronized
    fun contains(nzbUrl: String?, title: String?, size: Long?, servers: List<String>): Boolean {
        val providers = providersKey(servers)
        return keys(nzbUrl, title, size).any { key ->
            entries[key]?.let { it.providers == providers && now() - it.at <= RETENTION_MS } == true
        }
    }

    fun record(stream: Stream, kind: String?) =
        record(stream.nzbUrl, stream.title, stream.behaviorHints?.videoSize, stream.servers.orEmpty(), kind)

    fun contains(stream: Stream): Boolean =
        contains(stream.nzbUrl, stream.title, stream.behaviorHints?.videoSize, stream.servers.orEmpty())

    private fun keys(nzbUrl: String?, title: String?, size: Long?): List<String> = listOfNotNull(
        nzbUrl?.takeIf { it.isNotBlank() }?.let { "u:" + sha256(it) },
        title?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && size != null && size > 0 }
            ?.let { "t:" + sha256("$it\n$size") }
    )

    /** The providers' addresses only: credentials, connections and priorities do not change what they hold. */
    private fun providersKey(servers: List<String>): String = sha256(servers.map { server ->
        runCatching { URI(server).let { "${it.scheme}://${it.host}:${it.port}" } }.getOrDefault("")
    }.distinct().sorted().joinToString("\n"))

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Engine failures that the same release repeats with the same providers. */
        val KINDS = setOf("missing-article", "hole-limit", "invalid-article")
        private const val RETENTION_MS = 30L * 24 * 3600 * 1000
        private const val MAX_ENTRIES = 2000

        @Volatile private var storage: SharedPreferencesUnplayableReleaseStorage? = null
        @Volatile private var instance: UnplayableReleases? = null

        fun storage(context: Context): SharedPreferencesUnplayableReleaseStorage = storage ?: synchronized(this) {
            storage ?: SharedPreferencesUnplayableReleaseStorage(context.applicationContext).also { storage = it }
        }

        fun get(context: Context): UnplayableReleases = instance ?: synchronized(this) {
            instance ?: UnplayableReleases(storage(context)).also { instance = it }
        }
    }
}

package com.nuvio.tv.core.usenet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Profile-local credentials. Keystore ciphertext is never part of profile/cloud sync. */
@Singleton
class UsenetSourceSettings @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: Lazy<ProfileManager>
) : ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences("usenet_sources", Context.MODE_PRIVATE)
    /** Told every provider list read or saved, so a provider's statistics follow it through edits. */
    private val stats = UsenetSourceStats.get(context)
    private val revision = MutableStateFlow(0)
    private val json = Json { ignoreUnknownKeys = true }
    val snapshots get() = combine(profiles.get().activeProfileId, revision) { id, _ -> id to runCatching { read(id) } }

    // Decrypting takes a keystore round trip, and screens read the sources on the main thread:
    // each profile is decrypted once, and every later read is this copy. Guarded by this.
    private val decrypted = HashMap<Int, UsenetSourceConfiguration>()

    @Synchronized
    fun read(profileId: Int = profiles.get().activeProfileId.value): UsenetSourceConfiguration {
        decrypted[profileId]?.let { return it }
        val encrypted = preferences.getString("profile_$profileId", null) ?: return UsenetSourceConfiguration()
        // Do not silently overwrite credentials if the device keystore becomes unavailable.
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("profile_$profileId".toByteArray())
        return json.decodeFromString<UsenetSourceConfiguration>(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        ).also {
            decrypted[profileId] = it
            stats.knowProviders(it.providers)
        }
    }

    @Synchronized
    fun update(value: UsenetSourceConfiguration, profileId: Int = profiles.get().activeProfileId.value) {
        value.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("profile_$profileId".toByteArray())
        val bytes = cipher.iv + cipher.doFinal(json.encodeToString(value).toByteArray())
        check(preferences.edit().putString("profile_$profileId", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "Could not save Usenet sources"
        }
        decrypted[profileId] = value
        stats.knowProviders(value.providers)
        revision.value += 1
    }

    @Synchronized
    override fun removeProfile(profileId: Int) {
        // Dropped first: a failed write must not leave removed credentials readable.
        decrypted.remove(profileId)
        check(preferences.edit().remove("profile_$profileId").commit())
        revision.value += 1
    }

    @Synchronized
    override fun clearAllProfiles() {
        decrypted.clear()
        check(preferences.edit().clear().commit())
        revision.value += 1
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "nuvio_usenet_sources_v1" }
}

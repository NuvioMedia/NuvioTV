package com.nuvio.tv.data.simkl

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.data.local.ProfileDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class AndroidSimklAuthStorage @Inject constructor(
    @ApplicationContext context: Context,
    profileDataStore: ProfileDataStore
) : SimklAuthStorage, ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SimklAuthState())
    private val stateLock = Any()
    @Volatile
    private var activeCredentials = ActiveCredentials(profileId = 1, generation = 0L)
    @Volatile
    private var storedTokenScope: String? = null

    override val state: StateFlow<SimklAuthState> = _state.asStateFlow()

    init {
        load(1)
        scope.launch {
            profileDataStore.activeProfileId
                .distinctUntilChanged()
                .collect { profileId ->
                    if (profileId != currentScope().profileId) load(profileId)
                }
        }
    }

    override fun currentScope(): SimklAuthScope = activeCredentials.scope()

    override fun isCurrent(scope: SimklAuthScope): Boolean = activeCredentials.scope() == scope

    override fun authorization(): SimklAuthorization? = activeCredentials.authorization()

    override fun savePinSession(
        session: SimklPinSession,
        deviceCode: String,
        scope: SimklAuthScope
    ): Boolean {
        val normalized = deviceCode.trim().takeIf(String::isNotBlank) ?: return false
        return mutate(scope) { current ->
            val metadata = metadata().copy(pinSession = session)
            activeCredentials = current.copy(deviceCode = normalized)
            saveEncrypted(DEVICE_CODE_KEY, normalized, current.profileId)
            saveMetadata(metadata, current.profileId)
            publish(metadata = metadata)
        }
    }

    override fun updatePinInterval(intervalSeconds: Int, scope: SimklAuthScope): Boolean =
        mutate(scope) { current ->
            val session = _state.value.pinSession ?: return@mutate
            val metadata = metadata().copy(pinSession = session.copy(intervalSeconds = intervalSeconds))
            saveMetadata(metadata, current.profileId)
            publish(metadata = metadata)
        }

    override fun pinDeviceCode(): String? = activeCredentials.deviceCode

    override fun clearPinSession(error: SimklAuthError?, scope: SimklAuthScope): Boolean =
        mutate(scope) { current ->
            val metadata = metadata().copy(pinSession = null)
            activeCredentials = current.copy(deviceCode = null)
            saveEncrypted(DEVICE_CODE_KEY, null, current.profileId)
            saveMetadata(metadata, current.profileId)
            publish(metadata = metadata, error = error)
        }

    override fun completeDeviceAuthorization(grant: SimklTokenGrant, scope: SimklAuthScope): Boolean =
        mutate(scope) { current ->
            val metadata = metadata().copy(
                pinSession = null,
                accessTokenExpiresAtEpochMs = grant.accessTokenExpiresAtEpochMs,
                tokenScope = grant.scope
            )
            activeCredentials = current.copy(
                accessToken = grant.accessToken,
                refreshToken = grant.refreshToken,
                accessTokenExpiresAtEpochMs = grant.accessTokenExpiresAtEpochMs,
                deviceCode = null
            )
            saveEncrypted(TOKEN_KEY, grant.accessToken, current.profileId)
            saveEncrypted(REFRESH_TOKEN_KEY, grant.refreshToken, current.profileId)
            saveEncrypted(DEVICE_CODE_KEY, null, current.profileId)
            saveMetadata(metadata, current.profileId)
            publish(metadata = metadata)
        }

    override fun saveRefreshedTokens(
        previous: SimklAuthorization,
        grant: SimklTokenGrant
    ): SimklAuthorization? = synchronized(stateLock) {
        val current = activeCredentials
        if (current.scope() != previous.scope || current.refreshToken != previous.refreshToken) {
            return@synchronized null
        }
        val updated = current.copy(
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken,
            accessTokenExpiresAtEpochMs = grant.accessTokenExpiresAtEpochMs
        )
        activeCredentials = updated
        saveEncrypted(TOKEN_KEY, grant.accessToken, current.profileId)
        saveEncrypted(REFRESH_TOKEN_KEY, grant.refreshToken, current.profileId)
        val metadata = metadata().copy(
            accessTokenExpiresAtEpochMs = grant.accessTokenExpiresAtEpochMs,
            tokenScope = grant.scope ?: storedTokenScope
        )
        saveMetadata(metadata, current.profileId)
        publish(metadata = metadata, error = _state.value.error)
        updated.authorization()
    }

    override fun saveIdentity(
        username: String?,
        accountId: Long?,
        settingsActivityWatermark: String?,
        scope: SimklAuthScope
    ): Boolean = mutate(scope) { current ->
        val stored = metadata()
        val metadata = stored.copy(
            username = username,
            accountId = accountId,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = settingsActivityWatermark ?: stored.settingsActivityWatermark
        )
        saveMetadata(metadata, current.profileId)
        publish(metadata = metadata)
    }

    override fun recordSettingsActivityWatermark(
        watermark: String,
        scope: SimklAuthScope
    ): Boolean = mutate(scope) { current ->
        val metadata = metadata().copy(settingsActivityWatermark = watermark)
        saveMetadata(metadata, current.profileId)
        publish(metadata = metadata)
    }

    override fun clearAuth(
        error: SimklAuthError?,
        scope: SimklAuthScope,
        expectedAccessToken: String?
    ): Boolean = synchronized(stateLock) {
        val current = activeCredentials
        if (current.scope() != scope) return@synchronized false
        if (expectedAccessToken != null && current.accessToken != expectedAccessToken) {
            return@synchronized false
        }
        activeCredentials = ActiveCredentials(current.profileId, current.generation + 1L)
        saveEncrypted(TOKEN_KEY, null, current.profileId)
        saveEncrypted(REFRESH_TOKEN_KEY, null, current.profileId)
        saveEncrypted(DEVICE_CODE_KEY, null, current.profileId)
        val metadata = SimklStoredAuthMetadata()
        saveMetadata(metadata, current.profileId)
        publish(metadata = metadata, error = error)
        true
    }

    override fun removeProfile(profileId: Int) {
        preferences.edit()
            .remove(profileKey(METADATA_KEY, profileId))
            .remove(profileKey(TOKEN_KEY, profileId))
            .remove(profileKey(REFRESH_TOKEN_KEY, profileId))
            .remove(profileKey(DEVICE_CODE_KEY, profileId))
            .apply()
        synchronized(stateLock) {
            val current = activeCredentials
            if (profileId == current.profileId) {
                activeCredentials = ActiveCredentials(current.profileId, current.generation + 1L)
                publish(metadata = SimklStoredAuthMetadata())
            }
        }
    }

    override fun clearAllProfiles() {
        preferences.edit().clear().apply()
        synchronized(stateLock) {
            val current = activeCredentials
            activeCredentials = ActiveCredentials(current.profileId, current.generation + 1L)
            publish(metadata = SimklStoredAuthMetadata())
        }
    }

    private fun load(profileId: Int) {
        var metadata = preferences.getString(profileKey(METADATA_KEY, profileId), null)
            ?.let { runCatching { json.decodeFromString<SimklStoredAuthMetadata>(it) }.getOrNull() }
            ?: SimklStoredAuthMetadata()
        var accessToken = loadEncrypted(TOKEN_KEY, profileId)
        var refreshToken = loadEncrypted(REFRESH_TOKEN_KEY, profileId)
        var deviceCode = loadEncrypted(DEVICE_CODE_KEY, profileId)
        var error: SimklAuthError? = null
        if (accessToken != null && refreshToken == null) {
            // An AUTH V1 token (no refresh token) belongs to the retired client_id and is
            // rejected by the AUTH V2 app, so ask the user to connect again.
            accessToken = null
            metadata = SimklStoredAuthMetadata()
            error = SimklAuthError.REAUTHORIZATION_REQUIRED
            saveEncrypted(TOKEN_KEY, null, profileId)
            saveEncrypted(DEVICE_CODE_KEY, null, profileId)
            saveMetadata(metadata, profileId)
        }
        if (accessToken == null) refreshToken = null
        if (metadata.pinSession != null && deviceCode == null) {
            // A V1 PIN session has no device code to poll with.
            metadata = metadata.copy(pinSession = null)
            saveMetadata(metadata, profileId)
        }
        if (metadata.pinSession == null) deviceCode = null
        synchronized(stateLock) {
            val current = activeCredentials
            val generation = if (profileId == current.profileId) {
                current.generation
            } else {
                current.generation + 1L
            }
            activeCredentials = ActiveCredentials(
                profileId = profileId,
                generation = generation,
                accessToken = accessToken,
                refreshToken = refreshToken,
                accessTokenExpiresAtEpochMs = metadata.accessTokenExpiresAtEpochMs,
                deviceCode = deviceCode
            )
            publish(metadata = metadata, error = error)
        }
    }

    private fun metadata(): SimklStoredAuthMetadata = SimklStoredAuthMetadata(
        username = _state.value.username,
        accountId = _state.value.accountId,
        hasFetchedUserSettings = _state.value.hasFetchedUserSettings,
        settingsActivityWatermark = _state.value.settingsActivityWatermark,
        pinSession = _state.value.pinSession,
        accessTokenExpiresAtEpochMs = activeCredentials.accessTokenExpiresAtEpochMs,
        tokenScope = storedTokenScope
    )

    private fun publish(
        metadata: SimklStoredAuthMetadata = metadata(),
        error: SimklAuthError? = null
    ) {
        storedTokenScope = metadata.tokenScope
        _state.value = SimklAuthState(
            isAuthenticated = !activeCredentials.accessToken.isNullOrBlank(),
            username = metadata.username,
            accountId = metadata.accountId,
            hasFetchedUserSettings = metadata.hasFetchedUserSettings,
            settingsActivityWatermark = metadata.settingsActivityWatermark,
            pinSession = metadata.pinSession,
            error = error
        )
    }

    private fun saveMetadata(metadata: SimklStoredAuthMetadata, profileId: Int) {
        preferences.edit()
            .putString(profileKey(METADATA_KEY, profileId), json.encodeToString(metadata))
            .apply()
    }

    private fun loadEncrypted(key: String, profileId: Int): String? {
        val scopedKey = profileKey(key, profileId)
        val stored = preferences.getString(scopedKey, null) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { preferences.edit().remove(scopedKey).apply() }
            .getOrNull()
    }

    private fun saveEncrypted(key: String, value: String?, profileId: Int) {
        val scopedKey = profileKey(key, profileId)
        val editor = preferences.edit()
        if (value.isNullOrBlank()) editor.remove(scopedKey) else editor.putString(scopedKey, encrypt(value))
        editor.apply()
    }

    private fun mutate(
        scope: SimklAuthScope,
        block: (ActiveCredentials) -> Unit
    ): Boolean = synchronized(stateLock) {
        val current = activeCredentials
        if (current.scope() != scope) return@synchronized false
        block(current)
        true
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return "${cipher.iv.toBase64()}.${cipher.doFinal(value.toByteArray()).toBase64()}"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_BITS, value.substring(0, separator).fromBase64())
        )
        return cipher.doFinal(value.substring(separator + 1).fromBase64()).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun profileKey(key: String, profileId: Int): String = "$key.p$profileId"

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private data class ActiveCredentials(
        val profileId: Int,
        val generation: Long,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val accessTokenExpiresAtEpochMs: Long? = null,
        val deviceCode: String? = null
    ) {
        fun scope() = SimklAuthScope(profileId = profileId, generation = generation)

        fun authorization(): SimklAuthorization? {
            val token = accessToken?.takeIf(String::isNotBlank) ?: return null
            return SimklAuthorization(
                scope = scope(),
                accessToken = token,
                refreshToken = refreshToken,
                accessTokenExpiresAtEpochMs = accessTokenExpiresAtEpochMs
            )
        }

        override fun toString(): String = "ActiveCredentials(profileId=$profileId, generation=$generation)"
    }

    private companion object {
        const val PREFERENCES_NAME = "nuvio_simkl_auth"
        const val METADATA_KEY = "metadata"
        const val TOKEN_KEY = "access_token"
        const val REFRESH_TOKEN_KEY = "refresh_token"
        const val DEVICE_CODE_KEY = "device_code"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.nuvio.tv.simkl.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

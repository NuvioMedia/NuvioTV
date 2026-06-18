package com.nuvio.tv.core.network

import android.util.Log
import com.nuvio.tv.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val SYNC_BACKEND_REPOSITORY_TAG = "SyncBackendRepository"

@Singleton
class SyncBackendRepository @Inject constructor(
    private val storage: SyncBackendStorage,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(SyncBackendState())
    val state: StateFlow<SyncBackendState> = _state.asStateFlow()

    val selectedBackend: SyncBackendConfig
        get() {
            ensureLoaded()
            return _state.value.selectedBackend
        }

    fun ensureLoaded() {
        if (_state.value.isLoaded) return

        val storedSelection = storage.loadSelectionPayload()
            ?.takeIf { it.isNotBlank() }
            ?.let { payload ->
                runCatching { json.decodeFromString<StoredSyncBackendSelection>(payload) }
                    .onFailure { error -> Log.w(SYNC_BACKEND_REPOSITORY_TAG, "Failed to parse stored sync backend selection", error) }
                    .getOrNull()
            }

        val backend = storedSelection
            ?.let { selection ->
                selection.backendId.ifBlank { selection.backend?.id.orEmpty() }
            }
            ?.let(SyncBackendDefaults::byId)
            ?: SyncBackendDefaults.hosted()

        _state.value = SyncBackendState(
            selectedBackend = backend,
            appliedRevision = storedSelection?.appliedRevision.orEmpty(),
            isLoaded = true,
        )
    }

    suspend fun refreshFromManifest(): SyncBackendRefreshResult {
        ensureLoaded()

        val manifestUrl = BuildConfig.SYNC_BACKEND_MANIFEST_URL.trim()
        if (manifestUrl.isBlank()) {
            return SyncBackendRefreshResult.NotConfigured
        }

        val manifest = runCatching {
            json.decodeFromString<SyncBackendManifest>(fetchManifestText(manifestUrl))
        }.onFailure { error ->
            val message = error.message ?: "Failed to fetch sync backend manifest"
            Log.w(SYNC_BACKEND_REPOSITORY_TAG, message, error)
            _state.value = _state.value.copy(lastManifestError = message)
        }.getOrNull() ?: return SyncBackendRefreshResult.Failed(
            _state.value.lastManifestError ?: "Failed to fetch sync backend manifest",
        )

        val targetBackend = manifest.backendConfigForActiveBackend()
            ?: return SyncBackendRefreshResult.Failed("Sync backend manifest is invalid")
        val revision = manifest.revision.trim()
        val currentBackend = _state.value.selectedBackend

        if (currentBackend.hasSameConnectionIdentity(targetBackend)) {
            saveSelection(targetBackend, revision)
            return SyncBackendRefreshResult.Unchanged
        }

        if (!manifest.forceLogoutOnChange) {
            saveSelection(targetBackend, revision)
            return SyncBackendRefreshResult.Applied(targetBackend, revision)
        }

        return SyncBackendRefreshResult.RequiresLogout(
            currentBackend = currentBackend,
            targetBackend = targetBackend,
            revision = revision,
            forceLogout = true,
        )
    }

    fun applyBackendAfterLogout(
        backend: SyncBackendConfig,
        revision: String,
    ): SyncBackendConfig {
        val normalizedBackend = backend.normalized()
        saveSelection(normalizedBackend, revision)
        return normalizedBackend
    }

    private fun saveSelection(
        backend: SyncBackendConfig,
        revision: String,
    ) {
        val normalizedBackend = backend.normalized()
        val payload = json.encodeToString(
            StoredSyncBackendSelection(
                backendId = normalizedBackend.id,
                appliedRevision = revision,
            ),
        )
        storage.saveSelectionPayload(payload)
        _state.value = SyncBackendState(
            selectedBackend = normalizedBackend,
            appliedRevision = revision,
            isLoaded = true,
        )
    }

    private suspend fun fetchManifestText(manifestUrl: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(manifestUrl)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Sync backend manifest request failed (${response.code}): $body")
            }
            body
        }
    }
}

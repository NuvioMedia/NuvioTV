package com.nuvio.tv.core.activity

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.currentDeviceClientMetadata
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget reporter for the self-hosted `record_activity_event` RPC
 * (public.user_activity_events). Drops the event silently if signed out, and
 * never throws into the caller.
 */
@Singleton
class ActivityEventReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val syncClientIdentity: SyncClientIdentity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun report(
        eventType: String,
        status: String,
        entityType: String? = null,
        entityKey: String? = null,
        action: String? = null,
        durationMs: Int? = null,
        itemCount: Int? = null,
        metadata: JsonObject = JsonObject(emptyMap()),
    ) {
        if (authManager.authState.value !is AuthState.FullAccount) return
        scope.launch {
            runCatching {
                send(eventType, status, entityType, entityKey, action, durationMs, itemCount, metadata)
            }
        }
    }

    private suspend fun send(
        eventType: String,
        status: String,
        entityType: String?,
        entityKey: String?,
        action: String?,
        durationMs: Int?,
        itemCount: Int?,
        metadata: JsonObject,
    ) {
        val device = currentDeviceClientMetadata(context)
        val params = buildJsonObject {
            put("p_event_type", eventType)
            put("p_status", status)
            put("p_platform", device.platform)
            put("p_app_version", BuildConfig.VERSION_NAME)
            put("p_device_id", syncClientIdentity.currentClientId())
            put("p_device_name", device.deviceName)
            entityType?.let { put("p_entity_type", it) }
            entityKey?.let { put("p_entity_key", it) }
            action?.let { put("p_action", it) }
            durationMs?.let { put("p_duration_ms", it) }
            itemCount?.let { put("p_item_count", it) }
            put("p_metadata", metadata)
        }
        try {
            postgrest.rpc("record_activity_event", params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            postgrest.rpc("record_activity_event", params)
        }
    }
}

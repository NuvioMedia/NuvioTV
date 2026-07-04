package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.remote.supabase.ClaimSyncResult
import com.nuvio.tv.data.remote.supabase.SupabaseLinkedDevice
import com.nuvio.tv.data.remote.supabase.SyncCodeResult
import com.nuvio.tv.domain.repository.SyncRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncRepositoryImpl"

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager
) : SyncRepository {

    override suspend fun generateSyncCode(pin: String): Result<String> {
        return try {
            val params = buildJsonObject { put("p_pin", pin) }
            val response = postgrest.rpc("generate_sync_code", params)
            val results = response.decodeList<SyncCodeResult>()
            val result = results.firstOrNull()
                ?: return Result.failure(Exception("Empty response from generate_sync_code"))
            Result.success(result.code)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate sync code", e)
            Result.failure(e)
        }
    }

    override suspend fun getSyncCode(pin: String): Result<String> {
        return try {
            val params = buildJsonObject { put("p_pin", pin) }
            val response = postgrest.rpc("get_sync_code", params)
            val results = response.decodeList<SyncCodeResult>()
            val result = results.firstOrNull()
                ?: return Result.failure(Exception("No sync code found"))
            Result.success(result.code)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sync code", e)
            Result.failure(e)
        }
    }

    override suspend fun claimSyncCode(
        code: String,
        pin: String,
        deviceName: String?
    ): Result<ClaimSyncResult> {
        return try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_pin", pin)
                if (deviceName != null) put("p_device_name", deviceName)
            }
            val response = postgrest.rpc("claim_sync_code", params)
            val results = response.decodeList<ClaimSyncResult>()
            val result = results.firstOrNull()
                ?: return Result.failure(Exception("Empty response from claim_sync_code"))
            if (result.success) {
                authManager.clearEffectiveUserIdCache()
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to claim sync code", e)
            Result.failure(e)
        }
    }

    override suspend fun unlinkDevice(deviceUserId: String): Result<Unit> {
        return try {
            val params = buildJsonObject { put("p_device_user_id", deviceUserId) }
            postgrest.rpc("unlink_device", params)
            authManager.clearEffectiveUserIdCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unlink device", e)
            Result.failure(e)
        }
    }

    override suspend fun getLinkedDevices(): Result<List<SupabaseLinkedDevice>> {
        return try {
            val userId = authManager.getEffectiveUserId()
                ?: return Result.failure(Exception("Not authenticated"))
            val result = postgrest.from("linked_devices")
                .select { filter { eq("owner_id", userId) } }
                .decodeList<SupabaseLinkedDevice>()
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get linked devices", e)
            Result.failure(e)
        }
    }
}

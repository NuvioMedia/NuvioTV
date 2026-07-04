package com.nuvio.tv.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val CLIENT_ID_LENGTH = 32
private const val CLIENT_ID_PREFIX = "nuvio-tv-"
private const val CLIENT_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val ORIGIN_CLIENT_ID_PARAM = "p_origin_client_id"

@Singleton
class SyncClientIdentity @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(
        "nuvio_sync_client_identity",
        Context.MODE_PRIVATE
    )
    private var cachedClientId: String? = null

    @Synchronized
    fun currentClientId(): String {
        cachedClientId?.let { return it }

        val stored = preferences.getString("client_instance_id", null)
            ?.trim()
            ?.takeIf { it.isValidSyncClientId() }
        if (stored != null) {
            cachedClientId = stored
            return stored
        }

        val generated = generateClientId()
        preferences.edit().putString("client_instance_id", generated).apply()
        cachedClientId = generated
        return generated
    }

    private fun generateClientId(): String =
        CLIENT_ID_PREFIX + buildString(CLIENT_ID_LENGTH) {
            repeat(CLIENT_ID_LENGTH) {
                append(CLIENT_ID_ALPHABET[Random.nextInt(CLIENT_ID_ALPHABET.length)])
            }
        }

    private fun String.isValidSyncClientId(): Boolean =
        length in 16..96 && all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

internal fun JsonObjectBuilder.putSyncOriginClientId(syncClientIdentity: SyncClientIdentity) {
    put(ORIGIN_CLIENT_ID_PARAM, syncClientIdentity.currentClientId())
}

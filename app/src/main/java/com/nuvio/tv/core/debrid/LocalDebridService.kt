package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?
)

@Singleton
class LocalDebridService @Inject constructor(
    private val torboxApi: TorboxApi,
    private val premiumizeApi: PremiumizeApi
) {
    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        return checkCached(account, listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val response = torboxApi.checkCached(
                authorization = "Bearer ${apiKey.trim()}",
                body = TorboxCheckCachedRequestDto(hashes = normalizedHashes)
            )
            val body = response.body()
            if (!response.isSuccessful || body?.success == false) {
                null
            } else {
                body?.data.orEmpty().mapKeys { it.key.lowercase() }.mapValues { (_, value) ->
                    LocalDebridCachedItem(
                        name = value.name,
                        size = value.size
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes.normalizedHashes()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val response = premiumizeApi.checkCache(
                authorization = "Bearer ${apiKey.trim()}",
                items = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            )
            val body = response.body()
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private fun List<String>.normalizedHashes(): List<String> =
        map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
}

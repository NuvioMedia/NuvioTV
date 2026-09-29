package com.nuvio.tv.data.remote.supabase

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.MemberCatalogStorage
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MemberAvatarBucket = "membership-profile-avatars"
private const val MemberAvatarTag = "MemberAvatars"
private const val AvatarCatalogRefreshIntervalMs = 15 * 60_000L

internal fun isAvatarCatalogRefreshDue(lastRefreshAtMs: Long, nowMs: Long): Boolean {
    return lastRefreshAtMs <= 0L ||
        nowMs < lastRefreshAtMs ||
        nowMs - lastRefreshAtMs >= AvatarCatalogRefreshIntervalMs
}

@Serializable
private data class StoredAvatarCatalogPayload(
    val standardItems: List<SupabaseAvatarCatalogItem> = emptyList(),
    val memberItems: List<SupabaseMemberAvatarCatalogItem> = emptyList(),
    val standardLoaded: Boolean = false,
    val memberLoaded: Boolean = false
)

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null,
    val memberOnly: Boolean = false
)

@Singleton
class AvatarRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val storage: Storage,
    private val serverConfiguration: ServerConfiguration,
    private val memberCatalogStorage: MemberCatalogStorage,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storedCatalog = loadStoredCatalog()
    private var standardMetadata = storedCatalog?.standardItems.orEmpty()
    private var memberMetadata = storedCatalog?.memberItems.orEmpty()
    private var standardCatalogLoaded = storedCatalog?.standardLoaded == true
    private var memberCatalogLoaded = storedCatalog?.memberLoaded == true
    private var cachedStandardCatalog = if (standardCatalogLoaded) {
        standardMetadata.map(::toStandardCatalogItem)
    } else {
        null
    }
    private var cachedMemberCatalog = if (memberCatalogLoaded) {
        memberMetadata.mapNotNull(::loadCachedMemberAvatar)
    } else {
        null
    }
    private var standardRefreshJob: Job? = null
    private var memberRefreshJob: Job? = null
    private var lastStandardRefreshAtMs = 0L
    private var lastMemberRefreshAtMs = 0L

    suspend fun getAvatarCatalog(hasMemberAccess: Boolean = false): List<AvatarCatalogItem> {
        val hadStandardCache = cachedStandardCatalog != null
        val standardCatalog = getStandardAvatarCatalog()
        if (hadStandardCache) refreshStandardCatalogInBackground()
        if (!hasMemberAccess) return standardCatalog
        val hadMemberCache = cachedMemberCatalog != null
        val memberCatalog = getMemberAvatarCatalog()
        if (hadMemberCache) refreshMemberCatalogInBackground()
        return standardCatalog + memberCatalog
    }

    private suspend fun getStandardAvatarCatalog(): List<AvatarCatalogItem> {
        cachedStandardCatalog?.let { return it }
        return fetchStandardAvatarCatalog()
    }

    private suspend fun fetchStandardAvatarCatalog(): List<AvatarCatalogItem> {
        val response = postgrest.rpc("get_avatar_catalog")
        val remote = response.decodeList<SupabaseAvatarCatalogItem>()
        standardMetadata = remote
        standardCatalogLoaded = true
        val catalog = remote.map(::toStandardCatalogItem)
        cachedStandardCatalog = catalog
        lastStandardRefreshAtMs = SystemClock.elapsedRealtime()
        saveStoredCatalog()
        return catalog
    }

    private suspend fun getMemberAvatarCatalog(): List<AvatarCatalogItem> {
        cachedMemberCatalog?.let { return it }
        return fetchMemberAvatarCatalog()
    }

    private suspend fun fetchMemberAvatarCatalog(): List<AvatarCatalogItem> {
        val remote = try {
            postgrest.rpc("get_member_profile_avatar_catalog")
                .decodeList<SupabaseMemberAvatarCatalogItem>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(MemberAvatarTag, "Unable to load supporter avatar catalog", error)
            return emptyList()
        }
        memberMetadata = remote
        memberCatalogLoaded = true
        lastMemberRefreshAtMs = SystemClock.elapsedRealtime()
        saveStoredCatalog()
        val catalog = coroutineScope {
            remote.map { item ->
                async {
                    try {
                        toMemberCatalogItem(item, cacheMemberAvatar(item))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(MemberAvatarTag, "Unable to load supporter avatar ${item.id}", error)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        cachedMemberCatalog = catalog
        return catalog
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
            ?: latestCachedMemberAvatar(avatarId)?.toURI()?.toString()
    }

    fun invalidateCache() {
        standardRefreshJob?.cancel()
        standardRefreshJob = null
        memberRefreshJob?.cancel()
        memberRefreshJob = null
        cachedStandardCatalog = null
        cachedMemberCatalog = null
        standardMetadata = emptyList()
        memberMetadata = emptyList()
        standardCatalogLoaded = false
        memberCatalogLoaded = false
        lastStandardRefreshAtMs = 0L
        lastMemberRefreshAtMs = 0L
    }

    private fun refreshStandardCatalogInBackground() {
        if (standardRefreshJob?.isActive == true) return
        if (!isAvatarCatalogRefreshDue(lastStandardRefreshAtMs, SystemClock.elapsedRealtime())) return
        standardRefreshJob = scope.launch {
            try {
                fetchStandardAvatarCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(MemberAvatarTag, "Unable to refresh avatar catalog", error)
            }
        }
    }

    private fun refreshMemberCatalogInBackground() {
        if (memberRefreshJob?.isActive == true) return
        if (!isAvatarCatalogRefreshDue(lastMemberRefreshAtMs, SystemClock.elapsedRealtime())) return
        memberRefreshJob = scope.launch {
            try {
                fetchMemberAvatarCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(MemberAvatarTag, "Unable to refresh supporter avatars", error)
            }
        }
    }

    private fun loadStoredCatalog(): StoredAvatarCatalogPayload? {
        val payload = memberCatalogStorage.loadAvatarCatalogPayload().orEmpty().trim()
        if (payload.isEmpty()) return null
        return runCatching { json.decodeFromString<StoredAvatarCatalogPayload>(payload) }.getOrNull()
    }

    private fun saveStoredCatalog() {
        memberCatalogStorage.saveAvatarCatalogPayload(
            json.encodeToString(
                StoredAvatarCatalogPayload(
                    standardItems = standardMetadata,
                    memberItems = memberMetadata,
                    standardLoaded = standardCatalogLoaded,
                    memberLoaded = memberCatalogLoaded
                )
            )
        )
    }

    private fun toStandardCatalogItem(item: SupabaseAvatarCatalogItem): AvatarCatalogItem =
        AvatarCatalogItem(
            id = item.id,
            displayName = item.displayName,
            imageUrl = avatarImageUrl(item.storagePath),
            category = item.category,
            sortOrder = item.sortOrder,
            bgColor = item.bgColor
        )

    private fun loadCachedMemberAvatar(item: SupabaseMemberAvatarCatalogItem): AvatarCatalogItem? {
        val imageFile = memberAvatarFile(item).takeIf { it.isFile && it.length() > 0L } ?: return null
        return toMemberCatalogItem(item, imageFile)
    }

    private fun toMemberCatalogItem(item: SupabaseMemberAvatarCatalogItem, imageFile: File) =
        AvatarCatalogItem(
            id = item.id,
            displayName = item.displayName,
            imageUrl = imageFile.toURI().toString(),
            category = item.category,
            sortOrder = item.sortOrder,
            bgColor = item.bgColor,
            memberOnly = true
        )

    private fun avatarImageUrl(storagePath: String): String {
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) return storagePath
        val baseUrl = serverConfiguration.avatarPublicBaseUrl.orEmpty().trimEnd('/')
        return if (baseUrl.isNotEmpty()) "$baseUrl/$storagePath" else storagePath
    }

    private suspend fun cacheMemberAvatar(item: SupabaseMemberAvatarCatalogItem): File = withContext(Dispatchers.IO) {
        val imageFile = memberAvatarFile(item)
        if (imageFile.isFile && imageFile.length() > 0L) return@withContext imageFile

        val directory = imageFile.parentFile ?: return@withContext imageFile
        directory.mkdirs()
        val imageBytes = storage[MemberAvatarBucket].downloadAuthenticated(item.storagePath)
        val temporaryFile = directory.resolve(".${imageFile.name}.tmp")
        temporaryFile.writeBytes(imageBytes)
        if (!temporaryFile.renameTo(imageFile)) {
            temporaryFile.copyTo(imageFile, overwrite = true)
            temporaryFile.delete()
        }
        imageFile
    }

    private fun memberAvatarFile(item: SupabaseMemberAvatarCatalogItem): File {
        val extension = item.storagePath.substringAfterLast('.', "img")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        return context.cacheDir.resolve("member_profile_avatars/${item.id}-v${item.assetVersion}.$extension")
    }

    private fun latestCachedMemberAvatar(avatarId: String): File? {
        val prefix = "$avatarId-v"
        return context.cacheDir.resolve("member_profile_avatars").listFiles()
            ?.filter { file -> file.isFile && file.length() > 0L && file.name.startsWith(prefix) }
            ?.maxByOrNull { file ->
                file.name.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: Int.MIN_VALUE
            }
    }
}

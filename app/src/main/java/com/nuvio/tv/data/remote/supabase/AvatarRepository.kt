package com.nuvio.tv.data.remote.supabase

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MemberAvatarBucket = "membership-profile-avatars"
private const val MemberAvatarTag = "MemberAvatars"

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
    @ApplicationContext private val context: Context
) {
    private var cachedStandardCatalog: List<AvatarCatalogItem>? = null
    private var cachedMemberCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(hasMemberAccess: Boolean = false): List<AvatarCatalogItem> {
        val standardCatalog = getStandardAvatarCatalog()
        if (!hasMemberAccess) return standardCatalog
        return standardCatalog + getMemberAvatarCatalog()
    }

    private suspend fun getStandardAvatarCatalog(): List<AvatarCatalogItem> {
        cachedStandardCatalog?.let { return it }
        val response = postgrest.rpc("get_avatar_catalog")
        val remote = response.decodeList<SupabaseAvatarCatalogItem>()
        val catalog = remote.map { item ->
            AvatarCatalogItem(
                id = item.id,
                displayName = item.displayName,
                imageUrl = avatarImageUrl(item.storagePath),
                category = item.category,
                sortOrder = item.sortOrder,
                bgColor = item.bgColor
            )
        }
        cachedStandardCatalog = catalog
        return catalog
    }

    private suspend fun getMemberAvatarCatalog(): List<AvatarCatalogItem> {
        cachedMemberCatalog?.let { return it }
        val remote = try {
            postgrest.rpc("get_member_profile_avatar_catalog")
                .decodeList<SupabaseMemberAvatarCatalogItem>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(MemberAvatarTag, "Unable to load supporter avatar catalog", error)
            return emptyList()
        }
        val catalog = coroutineScope {
            remote.map { item ->
                async {
                    try {
                        AvatarCatalogItem(
                            id = item.id,
                            displayName = item.displayName,
                            imageUrl = cacheMemberAvatar(item).toURI().toString(),
                            category = item.category,
                            sortOrder = item.sortOrder,
                            bgColor = item.bgColor,
                            memberOnly = true
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(MemberAvatarTag, "Unable to load supporter avatar ${item.id}", error)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (catalog.size == remote.size) cachedMemberCatalog = catalog
        return catalog
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedStandardCatalog = null
        cachedMemberCatalog = null
    }

    private fun avatarImageUrl(storagePath: String): String {
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) return storagePath
        val baseUrl = serverConfiguration.avatarPublicBaseUrl.orEmpty().trimEnd('/')
        return if (baseUrl.isNotEmpty()) "$baseUrl/$storagePath" else storagePath
    }

    private suspend fun cacheMemberAvatar(item: SupabaseMemberAvatarCatalogItem): File = withContext(Dispatchers.IO) {
        val directory = context.cacheDir.resolve("member_profile_avatars")
        val extension = item.storagePath.substringAfterLast('.', "img")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        val imageFile = directory.resolve("${item.id}-v${item.assetVersion}.$extension")
        if (imageFile.isFile && imageFile.length() > 0L) return@withContext imageFile

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
}

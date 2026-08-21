package com.nuvio.tv.data.remote.supabase

import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemberAccessRemoteDataSource @Inject constructor(
    private val postgrest: Postgrest
) {
    suspend fun getMemberAccess(): MemberAccess {
        val remote = postgrest.rpc("get_my_member_access")
            .decodeList<MemberAccessResponse>()
            .firstOrNull()
            ?: return MemberAccess.None
        val tier = MemberTier.entries.firstOrNull { it.name == remote.tier }
            ?: return MemberAccess.None
        val entitlements = remote.entitlements
            .mapNotNull { value -> CosmeticEntitlement.entries.firstOrNull { it.name == value } }
            .toSet()

        return MemberAccess(
            tier = tier,
            entitlements = CosmeticEntitlements(entitlements)
        )
    }
}

@Serializable
private data class MemberAccessResponse(
    val tier: String,
    val entitlements: List<String>
)

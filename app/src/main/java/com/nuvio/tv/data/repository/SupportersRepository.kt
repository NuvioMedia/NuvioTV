package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.SupportersApi
import com.nuvio.tv.domain.model.MemberTier
import javax.inject.Inject
import javax.inject.Singleton

data class SupporterMember(
    val key: String,
    val name: String,
    val avatarUrl: String?,
    val membershipLevel: MemberTier,
    val supporterSince: String?
)

@Singleton
class SupportersRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val supportersApi: SupportersApi
) {

    suspend fun getSupporters(): Result<List<SupporterMember>> = runCatching {
        val response = supportersApi.getSupportersWall()
        if (!response.isSuccessful) {
            error(appContext.getString(com.nuvio.tv.R.string.supporters_error_api_http, response.code()))
        }

        response.body()
            ?.top
            ?.members
            .orEmpty()
            .mapNotNull { member ->
                val name = member.displayName?.trim().orEmpty()
                val membershipLevel = MemberTier.entries.firstOrNull {
                    it.name == member.membershipLevel?.trim()
                }
                if (name.isBlank() || membershipLevel == null) return@mapNotNull null

                SupporterMember(
                    key = "$name|${member.supporterSince.orEmpty()}",
                    name = name,
                    avatarUrl = member.avatarUrl?.trim()?.takeIf { it.isNotBlank() },
                    membershipLevel = membershipLevel,
                    supporterSince = member.supporterSince?.trim()?.takeIf { it.isNotBlank() }
                )
            }
            .mapIndexed { index, supporter ->
                supporter.copy(key = "${supporter.key}#$index")
            }
    }
}

package com.nuvio.tv.data.mdblist

import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRequestDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

sealed interface MdbListRatingsCredential {
    data class ApiKey(val value: String) : MdbListRatingsCredential {
        override fun toString(): String = "ApiKey()"
    }

    data class Account(val scope: MdbListAuthScope) : MdbListRatingsCredential
}

@Singleton
class MdbListRatingsClient @Inject constructor(
    private val api: MDBListApi,
    private val accountApi: MdbListApiClient,
    private val authStore: MdbListAuthStore,
    moshi: Moshi
) {
    private val mediaAdapter = moshi.adapter(MDBListMediaResponseDto::class.java)
    private val batchAdapter = moshi.adapter<List<MDBListMediaResponseDto>>(
        Types.newParameterizedType(List::class.java, MDBListMediaResponseDto::class.java)
    )
    private val requestAdapter = moshi.adapter(MDBListMediaRequestDto::class.java)

    fun credential(apiKey: String): MdbListRatingsCredential? {
        apiKey.trim().takeIf { it.isNotEmpty() }?.let { return MdbListRatingsCredential.ApiKey(it) }
        val state = authStore.state.value
        return if (state.isAuthenticated) MdbListRatingsCredential.Account(state.scope) else null
    }

    fun checkCredential(credential: MdbListRatingsCredential) {
        if (credential is MdbListRatingsCredential.Account) authStore.checkScope(credential.scope)
    }

    suspend fun getMedia(
        mediaType: String,
        imdbId: String,
        credential: MdbListRatingsCredential
    ): MDBListMediaResponseDto? = when (credential) {
        is MdbListRatingsCredential.ApiKey -> api.getMedia(mediaType, imdbId, credential.value).bodyOrThrow()
        is MdbListRatingsCredential.Account -> mediaAdapter.fromJson(
            accountApi.get(
                "/imdb/$mediaType/$imdbId/",
                query = mapOf("append_to_response" to "keyword"),
                scope = credential.scope
            ).body
        )
    }

    suspend fun getMediaBatch(
        mediaType: String,
        imdbIds: List<String>,
        credential: MdbListRatingsCredential
    ): List<MDBListMediaResponseDto>? {
        val body = MDBListMediaRequestDto(imdbIds)
        return when (credential) {
            is MdbListRatingsCredential.ApiKey -> api.getMediaBatch(mediaType, credential.value, body).bodyOrThrow()
            is MdbListRatingsCredential.Account -> batchAdapter.fromJson(
                accountApi.post(
                    "/imdb/$mediaType/",
                    body = requestAdapter.toJson(body),
                    scope = credential.scope
                ).body
            )
        }
    }

    private fun <T> Response<T>.bodyOrThrow(): T? {
        if (!isSuccessful) throw MdbListApiException(code())
        return body()
    }
}

package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.DonationsResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface DonationsApi {

    @GET("api/donations")
    suspend fun getDonations(
        @Query("limit") limit: Int = 200
    ): Response<DonationsResponseDto>
}

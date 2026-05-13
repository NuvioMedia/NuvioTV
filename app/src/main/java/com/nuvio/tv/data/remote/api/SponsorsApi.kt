package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.SponsorsResponseDto
import retrofit2.Response
import retrofit2.http.GET

interface SponsorsApi {

    @GET("api/sponsors")
    suspend fun getSponsors(): Response<SponsorsResponseDto>
}

package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.GitHubContributorDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GitHubReleaseApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubReleaseDto>

    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100
    ): Response<List<GitHubReleaseDto>>

    @GET("repos/{owner}/{repo}/contributors")
    suspend fun getContributors(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<List<GitHubContributorDto>>
}

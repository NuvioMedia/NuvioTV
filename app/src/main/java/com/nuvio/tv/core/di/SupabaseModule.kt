package com.nuvio.tv.core.di

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.TransientAuthRefreshException
import com.nuvio.tv.core.auth.shouldRetryAuthRefreshResponse
import com.nuvio.tv.core.network.BackendRateLimitCoordinator
import com.nuvio.tv.core.network.BackendRateLimitPlugin
import com.nuvio.tv.core.network.backendRetryDelayMillis
import com.nuvio.tv.core.network.isRetryableBackendResponse
import com.nuvio.tv.core.network.isSafeBackendRetryRequest
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideActiveServerConfiguration(
        configurationStore: ServerConfigurationStore
    ): ServerConfiguration = configurationStore.loadActive()

    @Provides
    @Singleton
    @OptIn(SupabaseInternal::class)
    fun provideSupabaseClient(
        serverConfiguration: ServerConfiguration
    ): SupabaseClient = runBlocking(Dispatchers.IO) {
        val userAgent = "NuvioTV/${BuildConfig.VERSION_NAME.ifBlank { "dev" }}"
        val rateLimitCoordinator = BackendRateLimitCoordinator()
        createSupabaseClient(
            supabaseUrl = serverConfiguration.backendUrl,
            supabaseKey = serverConfiguration.publishableKey
        ) {
            httpConfig {
                install(BackendRateLimitPlugin) {
                    coordinator = rateLimitCoordinator
                }
                install(HttpRequestRetry) {
                    retryIf(maxRetries = 1) { request, response ->
                        isSafeBackendRetryRequest(
                            method = request.method.value,
                            encodedPath = request.url.encodedPath
                        ) && isRetryableBackendResponse(response.status.value)
                    }
                    delayMillis(respectRetryAfterHeader = false) { retryCount ->
                        val retryResponse = response
                        if (retryResponse != null && isRetryableBackendResponse(retryResponse.status.value)) {
                            backendRetryDelayMillis(
                                retryCount = retryCount,
                                retryAfterHeader = retryResponse.headers[HttpHeaders.RetryAfter]
                            )
                        } else {
                            0L
                        }
                    }
                }
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
                HttpResponseValidator {
                    validateResponse { response ->
                        val requestUrl = response.request.url
                        if (
                            shouldRetryAuthRefreshResponse(
                                statusCode = response.status.value,
                                path = requestUrl.encodedPath,
                                grantType = requestUrl.parameters.get("grant_type"),
                                server = response.headers[HttpHeaders.Server],
                                cloudflareRay = response.headers["CF-Ray"]
                            )
                        ) {
                            throw TransientAuthRefreshException(response.status.value)
                        }
                    }
                }
            }
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            install(Storage)
        }
    }


    @Provides
    @Singleton
    fun provideSupabaseAuth(client: SupabaseClient): Auth = client.auth

    @Provides
    @Singleton
    fun provideSupabasePostgrest(client: SupabaseClient): Postgrest = client.postgrest

    @Provides
    @Singleton
    fun provideSupabaseStorage(client: SupabaseClient): Storage = client.storage
}

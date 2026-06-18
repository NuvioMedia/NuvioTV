package com.nuvio.tv.core.network

import com.nuvio.tv.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncBackendSupabaseProvider @Inject constructor(
    private val syncBackendRepository: SyncBackendRepository,
) {
    private data class ClientHolder(
        val backend: SyncBackendConfig,
        val client: SupabaseClient,
    )

    @Volatile
    private var clientHolder: ClientHolder? = null

    val selectedBackend: SyncBackendConfig
        get() = syncBackendRepository.selectedBackend

    val client: SupabaseClient
        get() = clientFor(selectedBackend)

    val auth: Auth
        get() = client.auth

    val postgrest: Postgrest
        get() = client.postgrest

    @Synchronized
    fun rebuildClient() {
        clientHolder = null
    }

    @Synchronized
    @OptIn(SupabaseInternal::class)
    private fun clientFor(backend: SyncBackendConfig): SupabaseClient {
        clientHolder
            ?.takeIf { holder -> holder.backend.hasSameConnectionIdentity(backend) }
            ?.let { return it.client }

        val userAgent = "NuvioTV/${BuildConfig.VERSION_NAME.ifBlank { "dev" }}"
        val client = createSupabaseClient(
            supabaseUrl = backend.normalizedSupabaseUrl,
            supabaseKey = backend.anonKey,
        ) {
            httpConfig {
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
        }
        clientHolder = ClientHolder(backend = backend, client = client)
        return client
    }
}

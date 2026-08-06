package com.nuvio.tv.core.tracking

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

data class TrackingProgressRefreshFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingProgressRefreshCoordinator @Inject constructor(
    private val providers: TrackingProgressProviderRegistry
) {
    suspend fun refreshConnected(intent: TrackingRefreshIntent): List<TrackingProgressRefreshFailure> =
        supervisorScope {
            providers.providers()
                .filter { provider -> provider.isAuthenticated.first() }
                .map { provider ->
                    async {
                        try {
                            provider.refresh(intent)
                            null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            TrackingProgressRefreshFailure(provider.providerId, error)
                        }
                    }
                }
                .mapNotNull { operation -> operation.await() }
        }
}

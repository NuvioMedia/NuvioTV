package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface TrackingRatingProvider {
    val providerId: TrackingProviderId
    val changes: Flow<Any?>

    fun supports(media: TrackingMediaReference): Boolean

    fun rating(media: TrackingMediaReference): Double?

    suspend fun rate(profileId: Int, media: TrackingMediaReference, rating: Int?): TrackingMutationResult

    fun onEnabled() = Unit
}

data class TrackingRatingState(
    val provider: TrackingProviderId? = null,
    val rating: Double? = null
) {
    val isAvailable: Boolean
        get() = provider != null
}

@Singleton
class TrackingRatings @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingRatingProvider>,
    private val trackingProviders: TrackingProviderRegistry,
    private val settings: TraktSettingsDataStore,
    private val profiles: ProfileManager
) {
    private val providersById = providers.associateBy(TrackingRatingProvider::providerId)
    private val recent = MutableStateFlow<Map<String, Double?>>(emptyMap())

    val source: StateFlow<TrackingProviderId?> = settings.ratingSource

    fun observe(media: TrackingMediaReference): Flow<TrackingRatingState> =
        combine(
            listOf(source, recent) +
                trackingProviders.providers().map(TrackingProvider::isAuthenticated) +
                providersById.values.map(TrackingRatingProvider::changes)
        ) { current(media) }.distinctUntilChanged()

    fun current(media: TrackingMediaReference): TrackingRatingState {
        val provider = provider(media) ?: return TrackingRatingState()
        val key = media.ratingKey(provider.providerId)
        val stored = provider.rating(media)
        val overrides = recent.value
        val rating = if (key in overrides) {
            if (overrides[key] == stored) recent.update { it - key }
            overrides[key]
        } else {
            stored
        }
        return TrackingRatingState(provider.providerId, rating)
    }

    suspend fun rate(media: TrackingMediaReference, rating: Int?): Boolean = withContext(NonCancellable) {
        require(rating == null || rating in 1..MAX)
        val provider = provider(media) ?: return@withContext false
        val saved = try {
            provider.rate(profiles.activeProfileId.value, media, rating).isComplete
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (saved) recent.update { it + (media.ratingKey(provider.providerId) to rating?.toDouble()) }
        saved
    }

    suspend fun setSource(source: TrackingProviderId?) {
        settings.setRatingSource(source)
        withTimeoutOrNull(SOURCE_APPLY_TIMEOUT_MS) { this@TrackingRatings.source.first { it == source } }
        source?.let(providersById::get)?.onEnabled()
    }

    fun availableSources(connected: Set<TrackingProviderId>): List<TrackingProviderId> =
        providersById.keys.filter { it in connected }.sortedBy { it.ordinal }

    private fun provider(media: TrackingMediaReference): TrackingRatingProvider? {
        val source = source.value ?: return null
        if (!trackingProviders.isAuthenticated(source)) return null
        return providersById[source]?.takeIf { it.supports(media) }
    }

    private fun TrackingMediaReference.ratingKey(provider: TrackingProviderId): String = buildString {
        append(profiles.activeProfileId.value)
        append(':')
        append(provider.storageId)
        append(':')
        append(stableKey)
        episode?.let {
            append(':')
            append(it.season ?: -1)
            append(':')
            append(it.number)
        }
    }

    companion object {
        const val MAX = 10
        private const val SOURCE_APPLY_TIMEOUT_MS = 2_000L
    }
}

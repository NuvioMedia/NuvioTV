package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingTrackingAddonInstall(
    val installUrl: String,
    val addon: Addon
)

@Singleton
class TrackingAddonConsentController @Inject constructor(
    private val addonRepository: AddonRepository
) {
    private val _pending = MutableStateFlow<PendingTrackingAddonInstall?>(null)
    val pending: StateFlow<PendingTrackingAddonInstall?> = _pending.asStateFlow()
    private var decision: CompletableDeferred<Boolean>? = null

    fun request(installUrl: String, addon: Addon): Deferred<Boolean> {
        decision?.complete(false)
        val newDecision = CompletableDeferred<Boolean>()
        decision = newDecision
        _pending.value = PendingTrackingAddonInstall(installUrl, addon)
        return newDecision
    }

    fun dismiss() {
        _pending.value = null
        decision?.complete(false)
        decision = null
    }

    suspend fun install(): Addon? {
        val pending = _pending.value ?: return null
        _pending.value = null
        addonRepository.addAddon(pending.installUrl)
        decision?.complete(true)
        decision = null
        return pending.addon
    }
}

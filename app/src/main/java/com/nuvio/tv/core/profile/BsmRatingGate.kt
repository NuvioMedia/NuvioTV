package com.nuvio.tv.core.profile

import com.nuvio.tv.data.remote.api.BsmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side soft rating gate: fetches each Nuvio profile's content-rating ceiling
 * from BSM (`GET /api/nuvio/profile-ratings`) and exposes the active profile's ceiling
 * as a [StateFlow]. When BSM is unreachable (e.g. off-LAN) or a rating is unknown the
 * gate fails open — this is a convenience filter, not a security boundary.
 */
@Singleton
class BsmRatingGate @Inject constructor(
    private val bsmApi: BsmApi,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ceilings = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** Content-rating ceiling for the currently active profile (null = no ceiling). */
    val activeCeiling: StateFlow<String?> = combine(
        profileManager.activeProfileId,
        _ceilings
    ) { profileId, ceilings -> ceilings[profileId] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            profileManager.activeProfileId.collect { refresh() }
        }
    }

    fun refresh() {
        scope.launch {
            try {
                val resp = bsmApi.getProfileRatings()
                if (resp.isSuccessful) {
                    val body = resp.body().orEmpty()
                    _ceilings.value = body.associate { dto ->
                        dto.profileIndex to (dto.contentRating ?: "G")
                    }
                }
            } catch (_: Throwable) {
                // BSM is LAN-only; leave ceilings empty and fail open.
            }
        }
    }

    fun isAllowed(ageRating: String?): Boolean =
        RatingOrdinal.isAllowed(ageRating, activeCeiling.value)
}

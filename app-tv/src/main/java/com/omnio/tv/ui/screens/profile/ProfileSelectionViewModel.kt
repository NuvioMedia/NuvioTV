package com.omnio.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.profile.ProfileManager
import com.omnio.tv.core.sync.ProfileSyncService
import com.omnio.tv.data.local.AddonPreferences
import com.omnio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.omnio.tv.data.remote.supabase.AvatarCatalogItem
import com.omnio.tv.data.remote.supabase.AvatarRepository
import com.omnio.tv.domain.model.AgeRatingTier
import com.omnio.tv.domain.model.AioSharingMode
import com.omnio.tv.domain.model.TraktSharingMode
import com.omnio.tv.domain.model.UserProfile
import com.omnio.tv.domain.repository.AioMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProfileAddonInitMode {
    LIVE_MIRROR,
    COPY_FROM_MAIN,
    FRESH
}

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val avatarRepository: AvatarRepository,
    private val addonPreferences: AddonPreferences,
    private val aioMetadataRepository: AioMetadataRepository
) : ViewModel() {
    private var isAvatarCatalogLoading = false

    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId
    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val canAddProfile: Boolean
        get() = profileManager.profiles.value.size < 4

    private val _avatarCatalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatarCatalog: StateFlow<List<AvatarCatalogItem>> = _avatarCatalog.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _profilePinEnabled = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = _profilePinEnabled.asStateFlow()

    private val _isPinOperationInProgress = MutableStateFlow(false)
    val isPinOperationInProgress: StateFlow<Boolean> = _isPinOperationInProgress.asStateFlow()

    init {
        loadAvatarCatalog()
        refreshProfilePinStates()
    }

    fun loadAvatarCatalog() {
        if (isAvatarCatalogLoading || _avatarCatalog.value.isNotEmpty()) return
        viewModelScope.launch {
            isAvatarCatalogLoading = true
            try {
                _avatarCatalog.value = avatarRepository.getAvatarCatalog()
            } catch (e: Exception) {
                Log.e("ProfileSelectionVM", "Failed to load avatar catalog", e)
            } finally {
                isAvatarCatalogLoading = false
            }
        }
    }

    fun getAvatarImageUrl(avatarId: String?): String? {
        if (avatarId == null) return null
        return avatarRepository.getAvatarImageUrl(avatarId, _avatarCatalog.value)
    }

    fun selectProfile(id: Int, onComplete: () -> Unit) {
        viewModelScope.launch {
            profileManager.setActiveProfile(id)
            onComplete()
        }
    }

    fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        addonInitMode: ProfileAddonInitMode = ProfileAddonInitMode.LIVE_MIRROR,
        isKids: Boolean = false,
        maxAgeRating: AgeRatingTier? = null,
        traktSharing: TraktSharingMode = TraktSharingMode.OWN,
        aioSharing: AioSharingMode = AioSharingMode.INDEPENDENT
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            // Kids profiles cannot live-mirror Main's addon list (their
            // kid-tuned AIO manifest would be shadowed). If the user picked
            // LIVE_MIRROR for a Kids profile, snapshot Main's addons instead
            // so the profile inherits stream addons (Torrentio, RD, etc.) —
            // otherwise Play would find no stream provider on the new
            // profile.
            val effectiveAddonInitMode = if (isKids && addonInitMode == ProfileAddonInitMode.LIVE_MIRROR) {
                ProfileAddonInitMode.COPY_FROM_MAIN
            } else addonInitMode

            val newId = profileManager.createProfile(
                name = name,
                avatarColorHex = avatarColorHex,
                avatarId = avatarId,
                usesPrimaryAddons = effectiveAddonInitMode == ProfileAddonInitMode.LIVE_MIRROR,
                isKids = isKids,
                maxAgeRating = if (isKids) maxAgeRating else null,
                traktSharing = traktSharing,
                aioSharing = aioSharing
            )
            if (newId != null) {
                if (effectiveAddonInitMode == ProfileAddonInitMode.COPY_FROM_MAIN) {
                    addonPreferences.copyAddonsToProfile(newId)
                }
                // Spawn a per-profile AIO config when this profile needs its
                // own (Kids always; or whenever the user picked any sharing
                // mode other than INDEPENDENT for a regular profile). The
                // provisioning step also swaps Main's AIO manifest out of
                // the addon list and inserts the new per-profile one.
                if (isKids || aioSharing != AioSharingMode.INDEPENDENT) {
                    aioMetadataRepository.provisionFromMain(
                        targetProfileId = newId,
                        kidsMaxAgeRating = if (isKids) maxAgeRating else null,
                    )
                }
                profileSyncService.pushToRemote()
                refreshProfilePinStates()
            }
            _isCreating.value = false
        }
    }

    fun updateProfile(profile: UserProfile) {
        if (_isSaving.value) return
        viewModelScope.launch {
            _isSaving.value = true
            val previous = profileManager.profiles.value.firstOrNull { it.id == profile.id }
            profileManager.updateProfile(profile)
            val becameKids = profile.id != 1 && profile.isKids && previous?.isKids != true
            // When a profile transitions into Kids, ProfileManager has just
            // forced usesPrimaryAddons=false. If the profile was previously
            // live-mirroring Main, its own addon list is likely empty —
            // snapshot Main's addons now so the profile keeps its stream
            // providers (Torrentio, RD, etc.). The next provisionFromMain
            // step will swap Main's AIO manifest out of that list.
            if (becameKids && previous?.usesPrimaryAddons == true) {
                addonPreferences.copyAddonsToProfile(profile.id)
            }
            // Provision a per-profile AIOMetadata config when a non-primary
            // profile gains its own AIO presence — either by becoming Kids,
            // or by switching from INDEPENDENT to a mirror mode. We don't
            // auto-tear-down on the reverse: the user can manage that config
            // via the standard AIOMetadata settings screen if they want to.
            val needsProvision = profile.id != 1 && (
                becameKids ||
                (
                    profile.aioSharing != AioSharingMode.INDEPENDENT &&
                    previous?.aioSharing == AioSharingMode.INDEPENDENT
                )
            )
            if (needsProvision) {
                aioMetadataRepository.provisionFromMain(
                    targetProfileId = profile.id,
                    kidsMaxAgeRating = if (profile.isKids) profile.maxAgeRating else null,
                )
            }
            profileSyncService.pushToRemote()
            refreshProfilePinStates()
            _isSaving.value = false
        }
    }

    fun deleteProfile(id: Int) {
        viewModelScope.launch {
            profileManager.deleteProfile(id)
            profileSyncService.deleteProfileData(id)
            profileSyncService.pushToRemote()
            refreshProfilePinStates()
        }
    }

    fun refreshProfilePinStates() {
        viewModelScope.launch {
            profileSyncService.pullProfileLockStates()
                .onSuccess { states ->
                    _profilePinEnabled.value = states
                }
                .onFailure { e ->
                    Log.e("ProfileSelectionVM", "Failed to refresh profile PIN states", e)
                }
        }
    }

    fun isProfilePinEnabled(profileId: Int): Boolean {
        return _profilePinEnabled.value[profileId] == true
    }

    fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null, onComplete: (Boolean) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val success = profileSyncService.setProfilePin(profileId, pin, currentPin).isSuccess
            if (success) {
                _profilePinEnabled.value = _profilePinEnabled.value + (profileId to true)
            }
            _isPinOperationInProgress.value = false
            onComplete(success)
        }
    }

    fun clearProfilePin(profileId: Int, currentPin: String? = null, onComplete: (Boolean) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val success = profileSyncService.clearProfilePin(profileId, currentPin).isSuccess
            if (success) {
                _profilePinEnabled.value = _profilePinEnabled.value + (profileId to false)
            }
            _isPinOperationInProgress.value = false
            onComplete(success)
        }
    }

    fun verifyProfilePin(profileId: Int, pin: String, onComplete: (Result<SupabaseProfilePinVerifyResult>) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profileSyncService.verifyProfilePin(profileId, pin)
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }
}

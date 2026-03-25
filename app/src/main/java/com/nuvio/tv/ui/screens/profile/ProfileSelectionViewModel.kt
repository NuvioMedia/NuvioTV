package com.nuvio.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val avatarRepository: AvatarRepository
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
        avatarId: String? = null
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            val success = profileManager.createProfile(
                name = name,
                avatarColorHex = avatarColorHex,
                avatarId = avatarId
            )
            if (success) {
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
            profileManager.updateProfile(profile)
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
